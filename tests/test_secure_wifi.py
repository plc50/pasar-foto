from __future__ import annotations

import json
from pathlib import Path
import secrets
import sys
import threading
import time
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

import receiver_wifi  # noqa: E402
from receiver import validate_image_signature  # noqa: E402
from receiver_wifi import (  # noqa: E402
    ReceiverState,
    SecureThreadingHTTPServer,
    WifiHandler,
)
from secure_protocol import (  # noqa: E402
    PAIR_INFO,
    b64u,
    b64u_decode,
    decrypt,
    derive_session_keys,
    encrypt,
    generate_server_keypair,
    hkdf,
    load_public_key,
    pack_photo,
    pair_request_aad,
    pair_response_aad,
    pairing_salt,
    photo_aad,
    request_canonical,
    sha256,
    sign_request,
)
from cryptography.hazmat.primitives import serialization  # noqa: E402
from cryptography.hazmat.primitives.asymmetric import ec  # noqa: E402


class ImageSignatureTest(unittest.TestCase):
    def test_supported_signatures_are_accepted(self):
        samples = {
            "image/jpeg": b"\xff\xd8\xff" + b"\x00" * 9,
            "image/png": b"\x89PNG\r\n\x1a\n" + b"\x00" * 4,
            "image/webp": b"RIFF\x00\x00\x00\x00WEBP",
            "image/heic": b"\x00\x00\x00\x18ftypheic",
            "image/heif": b"\x00\x00\x00\x18ftypheif",
        }
        for image_type, signature in samples.items():
            with self.subTest(image_type=image_type):
                validate_image_signature(signature, image_type)

    def test_mismatched_signature_is_rejected(self):
        with self.assertRaises(ValueError):
            validate_image_signature(b"not-an-image", "image/png")


class SecureWifiProtocolTest(unittest.TestCase):
    def setUp(self):
        private_key, public_b64 = generate_server_keypair()
        now = int(time.time())
        self.state = ReceiverState(
            bind_host="127.0.0.1",
            allowed_network=__import__("ipaddress").ip_network("127.0.0.0/8"),
            server_private=private_key,
            server_public_b64=public_b64,
            session_id=b64u(secrets.token_bytes(16)),
            qr_secret=secrets.token_bytes(32),
            pairing_code="1234567890",
            pairing_expires_at=now + 120,
        )
        self.server = SecureThreadingHTTPServer(
            ("127.0.0.1", 0),
            WifiHandler,
            self.state,
        )
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(
            target=self.server.serve_forever,
            daemon=True,
        )
        self.thread.start()
        self.copied_photo = None
        self.original_copy = receiver_wifi.copy_to_clipboard

        def fake_copy(path, content_type):
            self.copied_photo = (Path(path).read_bytes(), content_type)

        receiver_wifi.copy_to_clipboard = fake_copy

    def tearDown(self):
        receiver_wifi.copy_to_clipboard = self.original_copy
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def request(self, method, path, body=b"", headers=None):
        request = Request(
            f"http://127.0.0.1:{self.port}{path}",
            data=body if method == "POST" else None,
            method=method,
            headers=headers or {},
        )
        try:
            with urlopen(request, timeout=3) as response:
                return response.status, response.read()
        except HTTPError as error:
            try:
                return error.code, error.read()
            finally:
                error.close()

    def build_pair_request(self, pairing_code):
        client_private = ec.generate_private_key(ec.SECP256R1())
        client_public = b64u(
            client_private.public_key().public_bytes(
                serialization.Encoding.DER,
                serialization.PublicFormat.SubjectPublicKeyInfo,
            )
        )
        shared_secret = client_private.exchange(
            ec.ECDH(),
            load_public_key(self.state.server_public_b64),
        )
        pairing_key = hkdf(
            shared_secret,
            pairing_salt(self.state.qr_secret, pairing_code),
            PAIR_INFO,
        )
        client_nonce = secrets.token_bytes(16)
        proof = json.dumps(
            {
                "session_id": self.state.session_id,
                "client_nonce": b64u(client_nonce),
                "client": "test-client",
            },
            separators=(",", ":"),
        ).encode()
        nonce = secrets.token_bytes(12)
        ciphertext = encrypt(
            pairing_key,
            nonce,
            proof,
            pair_request_aad(
                self.state.session_id,
                client_public,
                self.state.pairing_expires_at,
            ),
        )
        body = json.dumps(
            {
                "session_id": self.state.session_id,
                "client_public": client_public,
                "nonce": b64u(nonce),
                "ciphertext": b64u(ciphertext),
            },
            separators=(",", ":"),
        ).encode()
        return body, pairing_key, client_public, client_nonce

    def pair(self):
        body, pairing_key, client_public, client_nonce = self.build_pair_request(
            self.state.pairing_code
        )
        status, response_body = self.request(
            "POST",
            "/v2/pair",
            body,
            {"Content-Type": "application/json"},
        )
        self.assertEqual(status, 200)
        response = json.loads(response_body)
        plaintext = decrypt(
            pairing_key,
            b64u_decode(response["nonce"]),
            b64u_decode(response["ciphertext"]),
            pair_response_aad(self.state.session_id, client_public),
        )
        session_data = json.loads(plaintext)
        self.assertEqual(
            b64u_decode(session_data["client_nonce"]),
            client_nonce,
        )
        encryption_key, authentication_key = derive_session_keys(
            b64u_decode(session_data["session_secret"]),
            self.state.session_id,
        )
        return session_data, encryption_key, authentication_key

    def authenticated_headers(
        self,
        session_data,
        auth_key,
        method,
        path,
        counter,
        logical_type,
        body,
        nonce,
        timestamp,
    ):
        nonce_b64 = b64u(nonce)
        canonical = request_canonical(
            method,
            path,
            self.state.session_id,
            session_data["device_id"],
            counter,
            timestamp,
            nonce_b64,
            logical_type,
            body,
        )
        return {
            "Authorization": (
                "PasarFoto-HMAC "
                + session_data["device_id"]
                + ":"
                + sign_request(auth_key, canonical)
            ),
            "X-PF-Session": self.state.session_id,
            "X-PF-Counter": str(counter),
            "X-PF-Timestamp": str(timestamp),
            "X-PF-Nonce": nonce_b64,
        }

    def test_pair_health_encrypted_photo_and_replay_rejection(self):
        session_data, encryption_key, auth_key = self.pair()

        health_nonce = secrets.token_bytes(12)
        timestamp = int(time.time())
        headers = self.authenticated_headers(
            session_data,
            auth_key,
            "GET",
            "/v2/health",
            0,
            "application/json",
            b"",
            health_nonce,
            timestamp,
        )
        status, body = self.request("GET", "/v2/health", headers=headers)
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)["status"], "ok")

        photo = b"\x89PNG\r\n\x1a\n" + secrets.token_bytes(200)
        nonce = secrets.token_bytes(12)
        nonce_b64 = b64u(nonce)
        timestamp = int(time.time())
        ciphertext = encrypt(
            encryption_key,
            nonce,
            pack_photo(photo),
            photo_aad(
                self.state.session_id,
                session_data["device_id"],
                1,
                timestamp,
                nonce_b64,
                "image/png",
            ),
        )
        headers = self.authenticated_headers(
            session_data,
            auth_key,
            "POST",
            "/v2/photo",
            1,
            "image/png",
            ciphertext,
            nonce,
            timestamp,
        )
        headers["Content-Type"] = "application/octet-stream"
        headers["X-PF-Image-Type"] = "image/png"

        status, _ = self.request("POST", "/v2/photo", ciphertext, headers)
        self.assertEqual(status, 204)
        self.assertEqual(self.copied_photo, (photo, "image/png"))

        replay_status, replay_body = self.request(
            "POST",
            "/v2/photo",
            ciphertext,
            headers,
        )
        self.assertEqual(replay_status, 409)
        self.assertEqual(json.loads(replay_body)["error"], "replayed_request")

    @patch("receiver_wifi.time.sleep", return_value=None)
    def test_pairing_attempt_limit_is_atomic(self, _sleep):
        for attempt in range(5):
            body, _, _, _ = self.build_pair_request("0000000000")
            status, response_body = self.request(
                "POST",
                "/v2/pair",
                body,
                {"Content-Type": "application/json"},
            )
            self.assertEqual(status, 401)
            self.assertEqual(
                json.loads(response_body)["attempts_left"],
                4 - attempt,
            )

        body, _, _, _ = self.build_pair_request(self.state.pairing_code)
        status, response_body = self.request(
            "POST",
            "/v2/pair",
            body,
            {"Content-Type": "application/json"},
        )
        self.assertEqual(status, 410)
        self.assertEqual(json.loads(response_body)["error"], "pairing_closed")
        self.assertIsNone(self.state.active_session)

    def test_tampered_ciphertext_is_rejected_before_decryption(self):
        session_data, encryption_key, auth_key = self.pair()
        photo = b"\x89PNG\r\n\x1a\n" + secrets.token_bytes(200)
        nonce = secrets.token_bytes(12)
        nonce_b64 = b64u(nonce)
        timestamp = int(time.time())
        ciphertext = encrypt(
            encryption_key,
            nonce,
            pack_photo(photo),
            photo_aad(
                self.state.session_id,
                session_data["device_id"],
                0,
                timestamp,
                nonce_b64,
                "image/png",
            ),
        )
        headers = self.authenticated_headers(
            session_data,
            auth_key,
            "POST",
            "/v2/photo",
            0,
            "image/png",
            ciphertext,
            nonce,
            timestamp,
        )
        headers["Content-Type"] = "application/octet-stream"
        headers["X-PF-Image-Type"] = "image/png"
        tampered = bytearray(ciphertext)
        tampered[-1] ^= 1

        status, response_body = self.request(
            "POST",
            "/v2/photo",
            bytes(tampered),
            headers,
        )
        self.assertEqual(status, 401)
        self.assertEqual(json.loads(response_body)["error"], "invalid_signature")
        self.assertIsNone(self.copied_photo)

    def test_session_keys_are_domain_separated(self):
        secret = secrets.token_bytes(32)
        encryption_key, authentication_key = derive_session_keys(
            secret,
            self.state.session_id,
        )
        self.assertNotEqual(encryption_key, authentication_key)
        self.assertEqual(len(encryption_key), 32)
        self.assertEqual(len(authentication_key), 32)

    def test_photo_padding_hides_exact_size(self):
        first = pack_photo(b"a" * 1000)
        second = pack_photo(b"a" * 2000)
        self.assertEqual(len(first), 64 * 1024)
        self.assertEqual(len(second), 64 * 1024)
        self.assertNotEqual(sha256(first), sha256(second))


if __name__ == "__main__":
    unittest.main()

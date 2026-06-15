#!/usr/bin/env python3
"""Ephemeral, encrypted Wi-Fi receiver for Pasar Foto."""

from __future__ import annotations

from collections import deque
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import ipaddress
import json
import os
from pathlib import Path
import secrets
import shutil
import signal
import subprocess
import tempfile
import threading
import time
from urllib.parse import urlencode

from cryptography.exceptions import InvalidTag

from receiver import copy_to_clipboard, validate_image_signature
from secure_protocol import (
    PROTOCOL_VERSION,
    b64u,
    b64u_decode,
    decrypt,
    derive_pairing_key,
    derive_session_keys,
    encrypt,
    generate_server_keypair,
    pair_request_aad,
    pair_response_aad,
    photo_aad,
    request_canonical,
    unpack_photo,
    verify_request_signature,
)

MAX_PHOTO_BYTES = 30 * 1024 * 1024
MAX_ENCRYPTED_BYTES = MAX_PHOTO_BYTES + 64 * 1024 + 64
PAIRING_LIFETIME_SECONDS = 120
SESSION_LIFETIME_SECONDS = 2 * 60 * 60
MAX_PAIR_ATTEMPTS = 5
MAX_REQUESTS_PER_MINUTE = 90
MAX_CLOCK_SKEW_SECONDS = 45
ALLOWED_IMAGE_TYPES = {
    "image/jpeg",
    "image/png",
    "image/webp",
    "image/heic",
    "image/heif",
}


@dataclass
class ActiveSession:
    session_id: str
    device_id: str
    encryption_key: bytes
    authentication_key: bytes
    client_ip: str
    expires_at: int
    highest_counter: int = -1
    seen_counters: set[int] = field(default_factory=set)
    request_times: deque[float] = field(default_factory=deque)


@dataclass
class ReceiverState:
    bind_host: str
    allowed_network: ipaddress.IPv4Network
    server_private: object
    server_public_b64: str
    session_id: str
    qr_secret: bytes | None
    pairing_code: str | None
    pairing_expires_at: int
    pair_attempts: int = 0
    active_session: ActiveSession | None = None
    lock: threading.Lock = field(default_factory=threading.Lock)

    def client_allowed(self, client_ip: str) -> bool:
        try:
            return ipaddress.ip_address(client_ip) in self.allowed_network
        except ValueError:
            return False

    def pairing_available(self) -> bool:
        return (
            self.active_session is None
            and self.qr_secret is not None
            and self.pairing_code is not None
            and int(time.time()) <= self.pairing_expires_at
            and self.pair_attempts < MAX_PAIR_ATTEMPTS
        )


class SecureThreadingHTTPServer(ThreadingHTTPServer):
    daemon_threads = True
    # Rebind immediately after a clean shutdown while the previous TCP
    # connections remain in TIME_WAIT. The kernel still rejects a second
    # listener while the active process owns the port.
    allow_reuse_address = True
    request_queue_size = 8

    def __init__(self, address, handler, state: ReceiverState):
        self.state = state
        super().__init__(address, handler)


class WifiHandler(BaseHTTPRequestHandler):
    server: SecureThreadingHTTPServer

    def setup(self):
        super().setup()
        self.connection.settimeout(10)

    def do_GET(self):
        if self.path == "/v2/health":
            self.handle_authenticated_health()
            return
        self.json_error(404, "not_found")

    def do_POST(self):
        if self.path == "/v2/pair":
            self.handle_pair()
            return
        if self.path == "/v2/photo":
            self.handle_photo()
            return
        self.json_error(404, "not_found")

    def handle_pair(self):
        state = self.server.state
        client_ip = self.client_address[0]
        if not state.client_allowed(client_ip):
            self.json_error(403, "client_outside_allowed_network")
            return

        with state.lock:
            if not state.pairing_available():
                self.json_error(410, "pairing_closed")
                return
            # Reserve the attempt before doing expensive crypto so concurrent
            # requests cannot bypass the five-attempt limit.
            state.pair_attempts += 1
            qr_secret = state.qr_secret
            pairing_code = state.pairing_code
            server_private = state.server_private

        body = self.read_body(16 * 1024)
        if body is None:
            return

        try:
            request = json.loads(body)
            session_id = str(request["session_id"])
            client_public = str(request["client_public"])
            nonce = b64u_decode(str(request["nonce"]))
            ciphertext = b64u_decode(str(request["ciphertext"]))
            if session_id != state.session_id:
                raise ValueError("session mismatch")

            assert (
                qr_secret is not None
                and pairing_code is not None
                and server_private is not None
            )
            pairing_key = derive_pairing_key(
                server_private,
                client_public,
                qr_secret,
                pairing_code,
            )
            plaintext = decrypt(
                pairing_key,
                nonce,
                ciphertext,
                pair_request_aad(
                    state.session_id,
                    client_public,
                    state.pairing_expires_at,
                ),
            )
            proof = json.loads(plaintext)
            if proof.get("session_id") != state.session_id:
                raise ValueError("invalid encrypted proof")
            client_nonce = b64u_decode(str(proof["client_nonce"]))
            if len(client_nonce) != 16:
                raise ValueError("invalid client nonce")
        except (InvalidTag, KeyError, TypeError, ValueError, json.JSONDecodeError):
            with state.lock:
                attempts_left = max(0, MAX_PAIR_ATTEMPTS - state.pair_attempts)
            time.sleep(1.25)
            self.json_error(401, "pairing_failed", {"attempts_left": attempts_left})
            return

        now = int(time.time())
        session_secret = secrets.token_bytes(32)
        device_id = b64u(secrets.token_bytes(16))
        encryption_key, authentication_key = derive_session_keys(
            session_secret,
            state.session_id,
        )
        active_session = ActiveSession(
            session_id=state.session_id,
            device_id=device_id,
            encryption_key=encryption_key,
            authentication_key=authentication_key,
            client_ip=client_ip,
            expires_at=now + SESSION_LIFETIME_SECONDS,
        )

        response_plaintext = json.dumps(
            {
                "device_id": device_id,
                "session_secret": b64u(session_secret),
                "session_expires_at": active_session.expires_at,
                "server_time": now,
                "protocol": PROTOCOL_VERSION,
                "client_nonce": b64u(client_nonce),
            },
            separators=(",", ":"),
        ).encode("utf-8")
        response_nonce = secrets.token_bytes(12)
        response_ciphertext = encrypt(
            pairing_key,
            response_nonce,
            response_plaintext,
            pair_response_aad(state.session_id, client_public),
        )

        with state.lock:
            if (
                state.active_session is not None
                or state.qr_secret is None
                or state.pairing_code is None
                or int(time.time()) > state.pairing_expires_at
            ):
                self.json_error(409, "pairing_already_consumed")
                return
            state.active_session = active_session
            state.qr_secret = None
            state.pairing_code = None
            state.server_private = None

        print(
            f"[{time.strftime('%H:%M:%S')}] paired device "
            f"{device_id[:8]} from {client_ip}; secrets remain in memory only"
        )
        self.json_response(
            200,
            {
                "nonce": b64u(response_nonce),
                "ciphertext": b64u(response_ciphertext),
            },
        )

    def handle_authenticated_health(self):
        auth = self.authenticate_request("GET", "/v2/health", "application/json", b"")
        if auth is None:
            return
        self.json_response(
            200,
            {
                "service": "pasar-foto-secure-wifi",
                "status": "ok",
                "session_expires_at": auth.expires_at,
            },
        )

    def handle_photo(self):
        image_type = self.headers.get("X-PF-Image-Type", "")
        if image_type not in ALLOWED_IMAGE_TYPES:
            self.json_error(415, "unsupported_image_type")
            return

        body = self.read_body(MAX_ENCRYPTED_BYTES)
        if body is None:
            return

        session = self.authenticate_request("POST", "/v2/photo", image_type, body)
        if session is None:
            return

        try:
            counter = int(self.headers["X-PF-Counter"])
            timestamp = int(self.headers["X-PF-Timestamp"])
            nonce_b64 = self.headers["X-PF-Nonce"]
            nonce = b64u_decode(nonce_b64)
            plaintext = decrypt(
                session.encryption_key,
                nonce,
                body,
                photo_aad(
                    session.session_id,
                    session.device_id,
                    counter,
                    timestamp,
                    nonce_b64,
                    image_type,
                ),
            )
            photo = unpack_photo(plaintext, MAX_PHOTO_BYTES)
            validate_image_signature(photo, image_type)
        except (InvalidTag, KeyError, TypeError, ValueError):
            self.json_error(400, "invalid_encrypted_photo")
            return

        suffix = {
            "image/jpeg": ".jpg",
            "image/png": ".png",
            "image/webp": ".webp",
            "image/heic": ".heic",
            "image/heif": ".heif",
        }[image_type]
        temp_path = None
        try:
            with tempfile.NamedTemporaryFile(
                prefix="pasar-foto-secure-",
                suffix=suffix,
                delete=False,
            ) as temp:
                temp.write(photo)
                temp_path = Path(temp.name)
            copy_to_clipboard(temp_path, image_type)
        except subprocess.CalledProcessError:
            self.json_error(500, "clipboard_failed")
            return
        finally:
            if temp_path is not None:
                temp_path.unlink(missing_ok=True)

        print(
            f"[{time.strftime('%H:%M:%S')}] accepted encrypted photo "
            f"counter={counter} bytes={len(photo)} from {session.client_ip}"
        )
        self.send_response(204)
        self.security_headers()
        self.end_headers()

    def authenticate_request(
        self,
        method: str,
        path: str,
        logical_type: str,
        body: bytes,
    ) -> ActiveSession | None:
        state = self.server.state
        client_ip = self.client_address[0]

        try:
            authorization = self.headers["Authorization"]
            scheme, credential = authorization.split(" ", 1)
            device_id, signature = credential.split(":", 1)
            session_id = self.headers["X-PF-Session"]
            counter = int(self.headers["X-PF-Counter"])
            timestamp = int(self.headers["X-PF-Timestamp"])
            nonce_b64 = self.headers["X-PF-Nonce"]
            nonce = b64u_decode(nonce_b64)
            if scheme != "PasarFoto-HMAC" or len(nonce) != 12:
                raise ValueError("invalid authentication headers")
        except (KeyError, TypeError, ValueError):
            self.json_error(401, "authentication_required")
            return None

        with state.lock:
            session = state.active_session
            if (
                session is None
                or session.session_id != session_id
                or session.device_id != device_id
            ):
                self.json_error(401, "invalid_session")
                return None
            if client_ip != session.client_ip:
                self.json_error(403, "session_ip_mismatch")
                return None
            now = int(time.time())
            if now > session.expires_at:
                self.json_error(401, "session_expired")
                return None
            if abs(now - timestamp) > MAX_CLOCK_SKEW_SECONDS:
                self.json_error(401, "timestamp_outside_window")
                return None

            canonical = request_canonical(
                method,
                path,
                session_id,
                device_id,
                counter,
                timestamp,
                nonce_b64,
                logical_type,
                body,
            )
            if not verify_request_signature(
                session.authentication_key,
                canonical,
                signature,
            ):
                self.json_error(401, "invalid_signature")
                return None
            if counter in session.seen_counters:
                self.json_error(409, "replayed_request")
                return None
            if session.highest_counter >= 0 and counter < session.highest_counter - 64:
                self.json_error(409, "stale_request")
                return None

            cutoff = time.monotonic() - 60
            while session.request_times and session.request_times[0] < cutoff:
                session.request_times.popleft()
            if len(session.request_times) >= MAX_REQUESTS_PER_MINUTE:
                self.json_error(429, "rate_limit_exceeded")
                return None

            session.seen_counters.add(counter)
            session.highest_counter = max(session.highest_counter, counter)
            minimum_counter = session.highest_counter - 64
            session.seen_counters = {
                value for value in session.seen_counters if value >= minimum_counter
            }
            session.request_times.append(time.monotonic())
            return session

    def read_body(self, maximum: int) -> bytes | None:
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            self.json_error(400, "invalid_content_length")
            return None
        if length <= 0:
            self.json_error(400, "empty_request")
            return None
        if length > maximum:
            self.json_error(413, "request_too_large")
            return None
        body = self.rfile.read(length)
        if len(body) != length:
            self.json_error(400, "incomplete_request")
            return None
        return body

    def json_response(self, status: int, payload: dict):
        body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.security_headers()
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def json_error(self, status: int, code: str, extra: dict | None = None):
        payload = {"error": code}
        if extra:
            payload.update(extra)
        self.json_response(status, payload)

    def security_headers(self):
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")

    def log_message(self, fmt, *args):
        # Do not place pairing material, headers, or URLs in logs.
        return


def pairing_uri(state: ReceiverState, port: int) -> str:
    assert state.qr_secret is not None
    query = urlencode(
        {
            "v": PROTOCOL_VERSION,
            "host": state.bind_host,
            "port": port,
            "sid": state.session_id,
            "spk": state.server_public_b64,
            "qs": b64u(state.qr_secret),
            "exp": state.pairing_expires_at,
        }
    )
    return f"pasarfoto://pair?{query}"


def display_pairing(state: ReceiverState, port: int) -> Path:
    if shutil.which("qrencode") is None:
        raise SystemExit(
            "qrencode is required for Wi-Fi pairing. Install package 'qrencode'."
        )

    uri = pairing_uri(state, port)
    runtime_root = os.getenv("XDG_RUNTIME_DIR")
    if runtime_root and not os.access(runtime_root, os.W_OK):
        runtime_root = None
    runtime_dir = Path(
        tempfile.mkdtemp(
            prefix="pasar-foto-pair-",
            dir=runtime_root,
        )
    )
    os.chmod(runtime_dir, 0o700)
    qr_path = runtime_dir / "pairing.png"
    subprocess.run(
        ["qrencode", "-l", "H", "-s", "8", "-o", str(qr_path)],
        input=uri.encode("utf-8"),
        check=True,
    )
    os.chmod(qr_path, 0o600)

    code = state.pairing_code or ""
    grouped_code = f"{code[:5]} {code[5:]}"
    fingerprint = b64u(
        __import__("hashlib").sha256(b64u_decode(state.server_public_b64)).digest()
    )[:16]

    print("\nPasar Foto - Wi-Fi cifrado")
    print("=" * 40)
    print(f"Escucha:        {state.bind_host}:{port}")
    print(f"Red permitida: {state.allowed_network}")
    print(f"Caduca en:     {PAIRING_LIFETIME_SECONDS} segundos")
    print(f"Fingerprint:   {fingerprint}")
    print(f"\nCODIGO DE CONFIRMACION:  {grouped_code}\n")
    print("1. Escanea el QR con la camara del movil.")
    print("2. Abre Pasar Foto.")
    print("3. Escribe el codigo mostrado arriba.")
    print("4. El QR y el codigo se invalidan tras emparejar.\n")
    subprocess.run(
        ["qrencode", "-t", "ANSIUTF8", "-l", "H"],
        input=uri.encode("utf-8"),
        check=True,
    )
    print(f"\nQR guardado temporalmente en: {qr_path}")
    print("Pulsa Ctrl+C para descartar la sesion y cerrar el receptor.\n")
    return runtime_dir


def main():
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument("--host", required=True)
    parser.add_argument("--network", required=True)
    parser.add_argument("--port", type=int, default=0)
    args = parser.parse_args()

    host = ipaddress.ip_address(args.host)
    network = ipaddress.ip_network(args.network, strict=False)
    if not isinstance(host, ipaddress.IPv4Address) or not host.is_private:
        raise SystemExit("Wi-Fi mode requires a private IPv4 address")
    if host not in network:
        raise SystemExit("bind host is outside the allowed network")

    private_key, public_b64 = generate_server_keypair()
    now = int(time.time())
    state = ReceiverState(
        bind_host=str(host),
        allowed_network=network,
        server_private=private_key,
        server_public_b64=public_b64,
        session_id=b64u(secrets.token_bytes(16)),
        qr_secret=secrets.token_bytes(32),
        pairing_code=f"{secrets.randbelow(10_000_000_000):010d}",
        pairing_expires_at=now + PAIRING_LIFETIME_SECONDS,
    )

    server = SecureThreadingHTTPServer((str(host), args.port), WifiHandler, state)
    port = server.server_address[1]
    runtime_dir = display_pairing(state, port)

    def request_shutdown(signum, frame):
        raise KeyboardInterrupt

    signal.signal(signal.SIGTERM, request_shutdown)
    signal.signal(signal.SIGINT, request_shutdown)
    try:
        server.serve_forever(poll_interval=0.25)
    except KeyboardInterrupt:
        print("\nClosing secure session and discarding in-memory key references.")
    finally:
        server.server_close()
        shutil.rmtree(runtime_dir, ignore_errors=True)
        with state.lock:
            state.qr_secret = None
            state.pairing_code = None
            state.server_private = None
            state.active_session = None


if __name__ == "__main__":
    main()

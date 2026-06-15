"""Cryptographic protocol primitives shared by the Wi-Fi receiver and tests."""

from __future__ import annotations

import base64
import hashlib
import hmac
import os
import struct

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

PROTOCOL_VERSION = 2
PAIR_INFO = b"pasar-foto/v2/pair-handshake"
PHOTO_ENCRYPTION_INFO = b"pasar-foto/v2/photo-encryption"
REQUEST_AUTH_INFO = b"pasar-foto/v2/request-authentication"
PADDING_BLOCK = 64 * 1024


def b64u(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def b64u_decode(value: str) -> bytes:
    padding = "=" * (-len(value) % 4)
    return base64.urlsafe_b64decode(value + padding)


def sha256(data: bytes) -> bytes:
    return hashlib.sha256(data).digest()


def hkdf(ikm: bytes, salt: bytes, info: bytes, length: int = 32) -> bytes:
    return HKDF(
        algorithm=hashes.SHA256(),
        length=length,
        salt=salt,
        info=info,
    ).derive(ikm)


def generate_server_keypair() -> tuple[ec.EllipticCurvePrivateKey, str]:
    private_key = ec.generate_private_key(ec.SECP256R1())
    public_der = private_key.public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    return private_key, b64u(public_der)


def load_public_key(public_key_b64: str) -> ec.EllipticCurvePublicKey:
    key = serialization.load_der_public_key(b64u_decode(public_key_b64))
    if not isinstance(key, ec.EllipticCurvePublicKey):
        raise ValueError("expected an elliptic-curve public key")
    if not isinstance(key.curve, ec.SECP256R1):
        raise ValueError("expected a P-256 public key")
    return key


def pairing_salt(qr_secret: bytes, pairing_code: str) -> bytes:
    if len(qr_secret) != 32:
        raise ValueError("QR secret must contain 256 bits")
    if len(pairing_code) != 10 or not pairing_code.isdigit():
        raise ValueError("pairing code must contain exactly 10 digits")
    return sha256(b"PF2-PAIR-SALT\x00" + qr_secret + pairing_code.encode("ascii"))


def derive_pairing_key(
    private_key: ec.EllipticCurvePrivateKey,
    peer_public_b64: str,
    qr_secret: bytes,
    pairing_code: str,
) -> bytes:
    shared_secret = private_key.exchange(ec.ECDH(), load_public_key(peer_public_b64))
    return hkdf(shared_secret, pairing_salt(qr_secret, pairing_code), PAIR_INFO)


def derive_session_keys(session_secret: bytes, session_id: str) -> tuple[bytes, bytes]:
    if len(session_secret) != 32:
        raise ValueError("session secret must contain 256 bits")
    salt = sha256(b"PF2-SESSION-SALT\x00" + session_id.encode("ascii"))
    encryption_key = hkdf(session_secret, salt, PHOTO_ENCRYPTION_INFO)
    authentication_key = hkdf(session_secret, salt, REQUEST_AUTH_INFO)
    return encryption_key, authentication_key


def pair_request_aad(session_id: str, client_public_b64: str, expires_at: int) -> bytes:
    return (f"PF2\nPAIR\n{session_id}\n{client_public_b64}\n{expires_at}").encode(
        "ascii"
    )


def pair_response_aad(session_id: str, client_public_b64: str) -> bytes:
    return f"PF2\nPAIR-RESPONSE\n{session_id}\n{client_public_b64}".encode("ascii")


def photo_aad(
    session_id: str,
    device_id: str,
    counter: int,
    timestamp: int,
    nonce_b64: str,
    image_type: str,
) -> bytes:
    return (
        f"PF2\nPHOTO\n{session_id}\n{device_id}\n{counter}\n"
        f"{timestamp}\n{nonce_b64}\n{image_type}"
    ).encode("ascii")


def request_canonical(
    method: str,
    path: str,
    session_id: str,
    device_id: str,
    counter: int,
    timestamp: int,
    nonce_b64: str,
    logical_type: str,
    body: bytes,
) -> bytes:
    return (
        f"PF2\n{method}\n{path}\n{session_id}\n{device_id}\n{counter}\n"
        f"{timestamp}\n{nonce_b64}\n{logical_type}\n{b64u(sha256(body))}"
    ).encode("ascii")


def sign_request(authentication_key: bytes, canonical: bytes) -> str:
    return b64u(hmac.new(authentication_key, canonical, hashlib.sha256).digest())


def verify_request_signature(
    authentication_key: bytes,
    canonical: bytes,
    provided_signature: str,
) -> bool:
    try:
        provided = b64u_decode(provided_signature)
    except Exception:
        return False
    expected = hmac.new(authentication_key, canonical, hashlib.sha256).digest()
    return hmac.compare_digest(expected, provided)


def encrypt(key: bytes, nonce: bytes, plaintext: bytes, aad: bytes) -> bytes:
    if len(nonce) != 12:
        raise ValueError("AES-GCM nonce must contain 96 bits")
    return AESGCM(key).encrypt(nonce, plaintext, aad)


def decrypt(key: bytes, nonce: bytes, ciphertext: bytes, aad: bytes) -> bytes:
    if len(nonce) != 12:
        raise ValueError("AES-GCM nonce must contain 96 bits")
    return AESGCM(key).decrypt(nonce, ciphertext, aad)


def pack_photo(photo: bytes) -> bytes:
    """Hide the exact image size by padding plaintext to 64 KiB boundaries."""
    framed_size = 4 + len(photo)
    padded_size = ((framed_size + PADDING_BLOCK - 1) // PADDING_BLOCK) * PADDING_BLOCK
    padding_size = padded_size - framed_size
    return struct.pack(">I", len(photo)) + photo + os.urandom(padding_size)


def unpack_photo(payload: bytes, maximum_size: int) -> bytes:
    if len(payload) < 4:
        raise ValueError("encrypted photo frame is truncated")
    (photo_size,) = struct.unpack(">I", payload[:4])
    if photo_size <= 0 or photo_size > maximum_size:
        raise ValueError("invalid photo size")
    if photo_size > len(payload) - 4:
        raise ValueError("encrypted photo frame is incomplete")
    return payload[4 : 4 + photo_size]

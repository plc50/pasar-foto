#!/usr/bin/env python3
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import json
import os
import shutil
import subprocess
import tempfile
import time

HOST = "127.0.0.1"
PORT = 48765
MAX_BYTES = 30 * 1024 * 1024
SERVICE_NAME = "pasar-foto-receiver"
ALLOWED_IMAGE_TYPES = {
    "image/jpeg": ".jpg",
    "image/png": ".png",
    "image/webp": ".webp",
    "image/heic": ".heic",
    "image/heif": ".heif",
}


def validate_image_signature(photo: bytes, image_type: str) -> None:
    valid = False
    if image_type == "image/jpeg":
        valid = photo.startswith(b"\xff\xd8\xff")
    elif image_type == "image/png":
        valid = photo.startswith(b"\x89PNG\r\n\x1a\n")
    elif image_type == "image/webp":
        valid = len(photo) >= 12 and photo[:4] == b"RIFF" and photo[8:12] == b"WEBP"
    elif image_type in {"image/heic", "image/heif"}:
        valid = len(photo) >= 12 and photo[4:8] == b"ftyp"
    if not valid:
        raise ValueError("image signature does not match declared content type")


class PhotoHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path != "/health":
            self.send_error(404)
            return

        body = json.dumps(
            {
                "service": SERVICE_NAME,
                "status": "ok",
            }
        ).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        if self.path != "/photo":
            self.send_error(404)
            return

        received = 0
        tmp_path = None
        try:
            try:
                content_length = int(self.headers.get("Content-Length", "0"))
            except ValueError:
                self.send_error(400, "invalid content length")
                return

            if content_length <= 0:
                self.send_error(400, "empty photo")
                return
            if content_length > MAX_BYTES:
                self.send_error(413, "photo too large")
                return

            content_type = (
                self.headers.get("Content-Type", "application/octet-stream")
                .split(";", 1)[0]
                .strip()
                .lower()
            )
            if content_type == "image/jpg":
                content_type = "image/jpeg"
            if content_type not in ALLOWED_IMAGE_TYPES:
                self.send_error(415, "unsupported image type")
                return

            suffix = ALLOWED_IMAGE_TYPES[content_type]
            with tempfile.NamedTemporaryFile(
                prefix="pasar-foto-", suffix=suffix, delete=False
            ) as tmp:
                tmp_path = Path(tmp.name)
                remaining = content_length
                while remaining > 0:
                    chunk = self.rfile.read(min(64 * 1024, remaining))
                    if not chunk:
                        break
                    received += len(chunk)
                    remaining -= len(chunk)
                    tmp.write(chunk)

            if received != content_length:
                self.send_error(400, "incomplete photo")
                return

            try:
                with tmp_path.open("rb") as photo:
                    validate_image_signature(photo.read(12), content_type)
            except ValueError:
                self.send_error(415, "image signature does not match content type")
                return

            copy_to_clipboard(tmp_path, content_type)

            self.send_response(204)
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.end_headers()
            print(f"[{time.strftime('%H:%M:%S')}] copied {received} bytes to clipboard")
        except subprocess.CalledProcessError as error:
            self.send_error(500, f"wl-copy failed: {error}")
        finally:
            if tmp_path is not None:
                try:
                    tmp_path.unlink()
                except FileNotFoundError:
                    pass

    def log_message(self, fmt, *args):
        print(f"[{time.strftime('%H:%M:%S')}] {self.client_address[0]} {fmt % args}")


def main():
    print(f"Pasar Foto receiver listening on http://{HOST}:{PORT}")
    print("Run: adb reverse tcp:48765 tcp:48765")
    print(f"Clipboard backend: {clipboard_backend()}")
    server = ThreadingHTTPServer((HOST, PORT), PhotoHandler)
    server.daemon_threads = True
    server.serve_forever()


def clipboard_backend():
    if os.getenv("WAYLAND_DISPLAY") and shutil.which("wl-copy"):
        return "Wayland (wl-copy)"
    if os.getenv("DISPLAY") and shutil.which("xclip"):
        return "X11 (xclip)"
    if shutil.which("wl-copy"):
        return "Wayland (wl-copy)"
    if shutil.which("xclip"):
        return "X11 (xclip)"
    raise RuntimeError("no clipboard backend found")


def copy_to_clipboard(photo_path, content_type):
    backend = clipboard_backend()
    magick = shutil.which("magick")
    if magick is not None and backend.startswith("Wayland"):
        converter = subprocess.Popen(
            [magick, str(photo_path), "png:-"],
            stdout=subprocess.PIPE,
        )
        try:
            subprocess.run(
                ["wl-copy", "--type", "image/png"],
                stdin=converter.stdout,
                check=True,
            )
        finally:
            if converter.stdout is not None:
                converter.stdout.close()

        if converter.wait() == 0:
            return

    with photo_path.open("rb") as photo:
        if backend.startswith("Wayland"):
            command = ["wl-copy", "--type", content_type]
        else:
            command = ["xclip", "-selection", "clipboard", "-t", content_type, "-i"]
        subprocess.run(command, stdin=photo, check=True)


if __name__ == "__main__":
    main()

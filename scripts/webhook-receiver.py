#!/usr/bin/env python3
"""
Zero-dependency webhook receiver for local testing: prints every POST the app sends and
appends it to a JSON Lines file. Optionally verifies the X-Signature header.

    python3 scripts/webhook-receiver.py                    # listen on 0.0.0.0:8765
    python3 scripts/webhook-receiver.py --port 9000 --secret mysecret --out received.jsonl

In the app: add http://<this machine's LAN IP>:8765/health as a webhook URL and switch on
Advanced > Allow plain HTTP. The LAN IP is printed at start.
"""
from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import socket
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def lan_ip() -> str:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


class Handler(BaseHTTPRequestHandler):
    secret: str | None = None
    out_path: str | None = None

    def do_POST(self) -> None:
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length)
        received_at = time.strftime("%H:%M:%S")

        signature_ok = None
        if self.secret:
            expected = "sha256=" + hmac.new(self.secret.encode(), body, hashlib.sha256).hexdigest()
            signature_ok = hmac.compare_digest(expected, self.headers.get("X-Signature", ""))

        try:
            payload = json.loads(body)
            pretty = json.dumps(payload, indent=2, ensure_ascii=False)
        except json.JSONDecodeError:
            payload = None
            pretty = body.decode("utf-8", errors="replace")

        summary = summarise(payload)
        print(f"\n=== {received_at} POST {self.path} from {self.client_address[0]} ({length} bytes) {summary}")
        for name in ("Content-Type", "User-Agent", "X-Signature", "X-Api-Key", "Cookie", "Authorization"):
            if name in self.headers:
                print(f"  {name}: {self.headers[name]}")
        if signature_ok is not None:
            print("  signature:", "valid" if signature_ok else "INVALID")
        print(pretty[:4000] + ("\n  ... (truncated in the terminal, full body in the file)" if len(pretty) > 4000 else ""))
        sys.stdout.flush()

        if self.out_path:
            with open(self.out_path, "a", encoding="utf-8") as f:
                f.write(json.dumps({
                    "received_at": received_at,
                    "path": self.path,
                    "client": self.client_address[0],
                    "headers": dict(self.headers),
                    "signature_ok": signature_ok,
                    "body": payload if payload is not None else body.decode("utf-8", errors="replace"),
                }, ensure_ascii=False) + "\n")

        status = 401 if signature_ok is False else 200
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(json.dumps({"ok": status == 200}).encode())

    def do_GET(self) -> None:
        self.send_response(200)
        self.send_header("Content-Type", "text/plain")
        self.end_headers()
        self.wfile.write(b"Life Dashboard webhook receiver is up. POST JSON here.\n")

    def log_message(self, *_args) -> None:  # keep the terminal to our own output
        pass


def summarise(payload) -> str:
    """One line with what is in the payload, so the terminal stays readable."""
    if not isinstance(payload, dict):
        return ""
    parts = []
    if payload.get("test"):
        parts.append("test ping")
    if "data_type" in payload:
        parts.append(str(payload["data_type"]))
    for key in ("steps", "heart_rate", "sleep", "distance", "active_calories", "total_calories", "weight"):
        value = payload.get(key)
        if isinstance(value, list):
            parts.append(f"{key}={len(value)}")
    if isinstance(payload.get("daily_totals"), list):
        parts.append(f"daily_totals={len(payload['daily_totals'])}")
    if isinstance(payload.get("days"), list):
        parts.append(f"days={len(payload['days'])}")
    if isinstance(payload.get("apps"), list):
        parts.append(f"apps={len(payload['apps'])}")
    return "[" + ", ".join(parts) + "]" if parts else ""


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--secret", help="HMAC secret to verify X-Signature (the app's webhook secret)")
    parser.add_argument("--out", default="received.jsonl", help="JSON Lines file to append to ('' to disable)")
    args = parser.parse_args()

    Handler.secret = args.secret
    Handler.out_path = args.out or None
    server = ThreadingHTTPServer(("0.0.0.0", args.port), Handler)
    ip = lan_ip()
    print(f"Listening on http://{ip}:{args.port}/  (use http://{ip}:{args.port}/health in the app)")
    print("Signature check:", "on" if args.secret else "off (pass --secret to verify X-Signature)")
    print("Logging to:", args.out or "terminal only")
    print("Ctrl+C to stop.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nstopped")


if __name__ == "__main__":
    main()

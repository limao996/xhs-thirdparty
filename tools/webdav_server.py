"""Minimal WebDAV server for verifying the client's backup upload/download.

Supports exactly what WebDavClient uses: PUT, GET, MKCOL, and Basic auth.

Usage: python tools/webdav_server.py <port> <root-dir> [user:pass]

Passing "user:pass" enforces Basic auth, so the client's failure path (wrong
credentials) can be exercised for real; omitting it accepts anything.
"""
import base64
import os
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8081
ROOT = os.path.abspath(sys.argv[2]) if len(sys.argv) > 2 else os.path.abspath("webdav_root")
EXPECTED = sys.argv[3] if len(sys.argv) > 3 else None
os.makedirs(ROOT, exist_ok=True)
LOG = []

REALM = "webdav"


def check_auth(headers):
    if EXPECTED is None:
        return True
    auth = headers.get("Authorization", "")
    if not auth.lower().startswith("basic "):
        return False
    try:
        got = base64.b64decode(auth.split(" ", 1)[1]).decode()
    except Exception:
        return False
    return got == EXPECTED


def local_path(url_path):
    p = url_path.split("?", 1)[0]
    if p.startswith("/dav"):
        p = p[4:]
    p = p.lstrip("/")
    full = os.path.abspath(os.path.join(ROOT, p))
    if not full.startswith(ROOT):
        raise ValueError("path escapes root")
    return full


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

    def _record(self, verb, path, code, length=None):
        auth = self.headers.get("Authorization", "")
        scheme = auth.split(" ", 1)[0] if auth else "-"
        user = "-"
        if scheme.lower() == "basic" and " " in auth:
            try:
                user = base64.b64decode(auth.split(" ", 1)[1]).decode().split(":")[0]
            except Exception:
                user = "?"
        entry = "%s %s -> %s auth=%s user=%s" % (verb, path, code, scheme, user)
        if length is not None:
            entry += " bytes=%d" % length
        LOG.append(entry)
        print(entry, flush=True)

    def _empty(self, code):
        self.send_response(code)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def _deny(self, verb):
        self._record(verb, self.path, 401)
        self.send_response(401)
        self.send_header("WWW-Authenticate", 'Basic realm="%s"' % REALM)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_MKCOL(self):
        if not check_auth(self.headers):
            return self._deny("MKCOL")
        try:
            full = local_path(self.path)
        except ValueError:
            return self._empty(403)
        os.makedirs(full, exist_ok=True)
        self._record("MKCOL", self.path, 201)
        self._empty(201)

    def do_PUT(self):
        if not check_auth(self.headers):
            return self._deny("PUT")
        try:
            full = local_path(self.path)
        except ValueError:
            return self._empty(403)
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "wb") as fh:
            fh.write(body)
        self._record("PUT", self.path, 201, len(body))
        self._empty(201)

    def do_GET(self):
        if not check_auth(self.headers):
            return self._deny("GET")
        try:
            full = local_path(self.path)
        except ValueError:
            return self._empty(403)
        if not os.path.isfile(full):
            self._record("GET", self.path, 404)
            return self._empty(404)
        with open(full, "rb") as fh:
            data = fh.read()
        self._record("GET", self.path, 200, len(data))
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_PROPFIND(self):
        if not check_auth(self.headers):
            return self._deny("PROPFIND")
        self._empty(207)


if __name__ == "__main__":
    print("webdav root = %s  auth=%s" % (ROOT, "enforced" if EXPECTED else "open"), flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()

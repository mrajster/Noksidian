#!/usr/bin/env python3
"""Noksidian GitHub TLS bridge.

The Nokia E71 only speaks TLS 1.0; api.github.com requires TLS 1.2+, so the phone
cannot reach GitHub directly. Run this on any PC/Raspberry Pi on the SAME LAN as the
phone's WiFi, then set  Settings -> API URL  in Noksidian to  http://<this-machine-ip>:8180

It forwards every request verbatim (method, path, headers incl. Authorization, body)
to https://api.github.com over modern TLS and relays the response back.

SECURITY: the phone->proxy hop is plain HTTP, so your GitHub token crosses your LAN
unencrypted. Use only on a network you trust, and use a fine-grained PAT restricted
to the single vault repo.

Usage:  python3 ghproxy.py [port]                     (default 8180, open bridge)
        python3 ghproxy.py [port] --secret WORD       (only /WORD/... paths forwarded)

With --secret, only requests whose path starts with /WORD/ are forwarded (the
prefix is stripped before the upstream call); everything else gets 403. Set the
phone's API URL to  http://<this-machine-ip>:<port>/WORD  -- no app change needed.
"""
import http.client
import http.server
import socket
import socketserver
import sys

UPSTREAM = "api.github.com"
HOP = {"host", "connection", "keep-alive", "proxy-connection", "transfer-encoding", "upgrade"}
SECRET = None  # set from --secret; when set, only /SECRET/... paths are forwarded


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _reject(self):
        msg = b'{"message":"forbidden"}'
        self.send_response(403)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(msg)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(msg)

    def _forward(self):
        path = self.path
        if SECRET is not None:
            prefix = "/" + SECRET + "/"
            if not path.startswith(prefix):
                self._reject()
                return
            path = path[len(prefix) - 1:]  # strip /SECRET, keep leading /
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else None
        conn = http.client.HTTPSConnection(UPSTREAM, timeout=60)
        headers = {k: v for k, v in self.headers.items() if k.lower() not in HOP}
        headers["Host"] = UPSTREAM
        try:
            conn.request(self.command, path, body=body, headers=headers)
            resp = conn.getresponse()
            data = resp.read()
            self.send_response(resp.status)
            for k, v in resp.getheaders():
                if k.lower() not in HOP and k.lower() != "content-length":
                    self.send_header(k, v)
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(data)
        except Exception as e:
            msg = ('{"message":"proxy error: %s"}' % str(e).replace('"', "'")).encode()
            self.send_response(502)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(msg)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(msg)
        finally:
            conn.close()

    do_GET = do_POST = do_PUT = do_DELETE = do_PATCH = _forward

    def log_message(self, fmt, *args):
        sys.stderr.write("%s %s\n" % (self.command if hasattr(self, "command") else "-", fmt % args))


class Server(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True


def _parse_args(argv):
    """Return (port, secret). Positional port and --secret WORD in any order."""
    port, secret = 8180, None
    args = list(argv)
    i = 0
    while i < len(args):
        a = args[i]
        if a == "--secret":
            if i + 1 >= len(args):
                sys.exit("ghproxy: --secret requires a WORD argument")
            secret = args[i + 1]
            i += 2
        elif a.startswith("--secret="):
            secret = a[len("--secret="):]
            i += 1
        else:
            try:
                port = int(a)
            except ValueError:
                sys.exit("ghproxy: unrecognized argument %r\n"
                         "Usage: python3 ghproxy.py [port]\n"
                         "       python3 ghproxy.py [port] --secret WORD" % a)
            i += 1
    if secret is not None and (not secret or "/" in secret):
        sys.exit("ghproxy: --secret WORD must be non-empty and contain no '/'")
    return port, secret


def _lan_ip():
    """Best-effort LAN IP for the banner; falls back to a placeholder."""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            s.connect(("8.8.8.8", 80))
            return s.getsockname()[0]
        finally:
            s.close()
    except OSError:
        return "<this-machine-LAN-ip>"


if __name__ == "__main__":
    port, SECRET = _parse_args(sys.argv[1:])
    print("Noksidian GitHub bridge on 0.0.0.0:%d -> https://%s" % (port, UPSTREAM))
    print("Usage: python3 ghproxy.py [port]                 (open bridge, default port 8180)")
    print("       python3 ghproxy.py [port] --secret WORD   (only /WORD/... paths forwarded)")
    if SECRET is not None:
        print("Secret prefix enabled: only paths under /%s/ are forwarded; all else -> 403" % SECRET)
        print("Enter this API URL on the phone (Settings -> API URL):")
        print("    http://%s:%d/%s" % (_lan_ip(), port, SECRET))
    else:
        print("Point the phone's Settings -> API URL at http://<this-machine-LAN-ip>:%d" % port)
    sys.stdout.flush()
    Server(("0.0.0.0", port), Handler).serve_forever()

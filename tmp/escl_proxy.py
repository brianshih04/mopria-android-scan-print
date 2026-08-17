#!/usr/bin/env python3
"""
Simple HTTP reverse proxy for Brother MFC-L2715DW eSCL.
Listens on port 8080, forwards everything to 10.1.121.175:80.
The emulator connects to 10.0.2.2:8080 (host from emulator),
the scanner sees the HOST's TCP stack → full resolution images.
"""
import http.server
import http.client
import sys

SCANNER_HOST = "10.1.121.175"
SCANNER_PORT = 80
LISTEN_PORT = 8080


class ProxyHandler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self._proxy("GET")

    def do_POST(self):
        self._proxy("POST")

    def do_DELETE(self):
        self._proxy("DELETE")

    def _proxy(self, method):
        # Read request body
        content_length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(content_length) if content_length > 0 else None

        # Forward to scanner
        conn = http.client.HTTPConnection(SCANNER_HOST, SCANNER_PORT, timeout=120)
        headers = {}
        for key, val in self.headers.items():
            if key.lower() not in ("host", "connection", "accept-encoding"):
                headers[key] = val
        headers["Host"] = f"{SCANNER_HOST}:{SCANNER_PORT}"
        headers["Connection"] = "close"

        try:
            conn.request(method, self.path, body=body, headers=headers)
            resp = conn.getresponse()

            # Send status
            self.send_response(resp.status, resp.reason)

            # Forward headers, rewriting Location to keep traffic through proxy
            for key, val in resp.getheaders():
                if key.lower() in ("connection", "transfer-encoding"):
                    continue
                if key.lower() == "location":
                    val = val.replace(f"{SCANNER_HOST}:{SCANNER_PORT}", "10.0.2.2:8080")
                    val = val.replace(SCANNER_HOST, "10.0.2.2:8080")
                self.send_header(key, val)

            # Read full body
            data = resp.read()
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

            sys.stderr.write(f"  {method} {self.path} → {resp.status} ({len(data)} bytes)\n")
        except Exception as e:
            sys.stderr.write(f"  {method} {self.path} → ERROR: {e}\n")
            self.send_response(502)
            self.end_headers()
        finally:
            conn.close()

    def log_message(self, format, *args):
        pass  # suppress default logging


if __name__ == "__main__":
    server = http.server.HTTPServer(("0.0.0.0", LISTEN_PORT), ProxyHandler)
    sys.stderr.write(f"eSCL proxy: 0.0.0.0:{LISTEN_PORT} → {SCANNER_HOST}:{SCANNER_PORT}\n")
    server.serve_forever()

"""INV-01: the registration poll has a ten-second deadline, including HTTP IO."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import json
import os
import shutil
import subprocess
import tempfile
import threading
import time
import unittest

GIT_BASH = Path("C:/Program Files/Git/bin/bash.exe")
BASH = str(GIT_BASH) if os.name == "nt" and GIT_BASH.exists() else shutil.which("bash")


@unittest.skipUnless(BASH and shutil.which("node"), "requires Git Bash/bash and Node")
class InventorySmokeTest(unittest.TestCase):
    def test_poll_deadline_includes_a_stalled_http_response(self):
        class SlowResponse(BaseHTTPRequestHandler):
            def do_GET(self):
                time.sleep(20)  # Far past the 10 s deadline, so a missing abort cannot pass the bound.
                try:
                    body = json.dumps({"data": {"items": [{"sku": "SMOKE-BOUND"}]}}).encode()
                    self.send_response(200)
                    self.send_header("Content-Length", str(len(body)))
                    self.end_headers()
                    self.wfile.write(body)
                except (BrokenPipeError, ConnectionResetError):
                    pass

            def log_message(self, *_):
                pass

        source = (Path(__file__).parent / "smoke-local.sh").read_text(encoding="utf-8")
        start = source.index("inventory_seen=false")
        end_marker = 'test "$inventory_seen" = true'
        fragment = source[start:source.index(end_marker, start) + len(end_marker)]
        server = ThreadingHTTPServer(("127.0.0.1", 0), SlowResponse)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            with tempfile.TemporaryDirectory(prefix="inventory smoke ") as temp:
                script = Path(temp) / "poll.sh"
                script.write_text('set -euo pipefail\ninventory_access=test\ninventory_sku=SMOKE-BOUND\n'
                                  f'GATEWAY_URL=http://127.0.0.1:{server.server_port}\n' + fragment,
                                  encoding="utf-8", newline="\n")
                began = time.monotonic()
                result = subprocess.run([BASH, "--noprofile", "--norc", script.as_posix()],
                                        capture_output=True, text=True, timeout=18)
                self.assertNotEqual(result.returncode, 0, "response after the deadline must not succeed")
                self.assertLess(time.monotonic() - began, 13, "deadline plus bash/node startup allowance on CI")
        finally:
            server.shutdown()
            server.server_close()


if __name__ == "__main__":
    unittest.main()

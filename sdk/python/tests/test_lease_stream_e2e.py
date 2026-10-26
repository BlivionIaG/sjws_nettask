"""End to end over HTTP/1.1 (#261 Q1): a real sjws server with token streaming on; ``:claim`` carries
``sjws-lease-stream`` (``<32hex>/<attempt>`` or ``-`` per LEASE, in order) and ``sjws-stream-window`` only for a batch
with a stream task. Read the way the S2c PR 7 SDK will: every field of the name joined (``get_all``), the entry count
checked against the LEASE frames. Run with a small advertised window (4/4, credit on) and at the defaults (64/256).

Needs the built jar: SJWS_JAR=target/sjws_nettask-<version>.jar (CI sets it after `mvn verify`). Skipped otherwise.
"""
import os
import pathlib
import secrets
import shutil
import socket
import struct
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "sdk" / "python"))

from sjws_swarm import Frame, FrameType, decode  # noqa: E402
from sjws_swarm.chunks import FIRST, StreamChunk  # noqa: E402

JAR = os.path.abspath(os.environ["SJWS_JAR"]) if os.environ.get("SJWS_JAR") else None
WIRE = "application/vnd.sjws.swarm.v1"


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def lease_stream_entries(headers, leases: int) -> list[str] | None:
    """The SDK's rule: join every field, fail closed when the entry count differs from the LEASE frame count."""
    fields = headers.get_all("sjws-lease-stream") or []
    if not fields:
        return None
    entries = ",".join(fields).split(",")
    if len(entries) != leases:
        raise ValueError(f"sjws-lease-stream has {len(entries)} entries for {leases} leases")
    return entries


class _Server:
    def __init__(self, extra: list[str]):
        self.tmp = tempfile.mkdtemp(prefix="sjws-e2e-ls-")
        site = pathlib.Path(self.tmp, "site")
        site.mkdir()
        (site / "hello.txt").write_text("hello")
        self.port = free_port()
        self.token = "e2e-token-" + secrets.token_urlsafe(32)
        tokens = pathlib.Path(self.tmp, "tokens")
        tokens.write_text(f"e2e {self.token} *\n")
        tokens.chmod(0o600)
        java = os.path.join(os.environ["JAVA_HOME"], "bin", "java") if os.environ.get("JAVA_HOME") else "java"
        self.log = open(pathlib.Path(self.tmp, "server.log"), "w")
        self.proc = subprocess.Popen(
            [java, "-Dsjws.swarm.enabled=true", "-Dsjws.swarm.stream.enabled=true",
             "-Dsjws.swarm.dir=" + str(pathlib.Path(self.tmp, "swarm")), "-Dsjws.swarm.node=e2e",
             "-Dsjws.swarm.tokens.file=" + str(tokens), *extra, "-jar", JAR, str(self.port), str(site)],
            stdout=self.log, stderr=subprocess.STDOUT, cwd=self.tmp)
        deadline = time.time() + 30
        while time.time() < deadline:
            try:
                with urllib.request.urlopen(f"http://127.0.0.1:{self.port}/hello.txt", timeout=1) as r:
                    if r.status == 200:
                        return
            except OSError:
                time.sleep(0.1)
        self.close()
        raise RuntimeError("server did not start")

    def post(self, path: str, body: bytes):
        req = urllib.request.Request(f"http://127.0.0.1:{self.port}/swarm/v1/{path}", data=body, method="POST",
                                     headers={"Content-Type": WIRE, "Authorization": "Bearer " + self.token})
        try:
            r = urllib.request.urlopen(req, timeout=10)
        except urllib.error.HTTPError as e:
            return e.code, e.read(), e.headers
        with r:
            return r.status, r.read(), r.headers

    def close(self):
        self.proc.terminate()
        try:
            self.proc.wait(10)
        except subprocess.TimeoutExpired:
            self.proc.kill()
        self.log.close()
        shutil.rmtree(self.tmp, ignore_errors=True)


def lease_ids(body: bytes) -> list[str]:
    out, view = [], memoryview(body)
    while view:
        frame, used = decode(view)
        view = view[used:]
        assert frame.type == FrameType.LEASE
        (n,) = struct.unpack_from(">H", frame.payload, 0)
        out.append(frame.payload[2:2 + n].decode())
    return out


@unittest.skipUnless(JAR, "SJWS_JAR not set")
class LeaseStreamHeaderEndToEndTest(unittest.TestCase):

    def check(self, server: _Server, window: int, credit: bool):
        task = Frame(FrameType.TASK, 0, b"x").encode()
        # unary only: neither header
        self.assertEqual(202, server.post("tasks/gen", task)[0])
        status, body, headers = server.post("tasks/gen:claim?visibilityMs=60000", b"")
        self.assertEqual(200, status)
        self.assertIsNone(lease_stream_entries(headers, len(lease_ids(body))))
        self.assertIsNone(headers.get("sjws-stream-window"))
        # stream then unary in one batch
        self.assertEqual(201, server.post("tasks/gen:stream", task)[0])
        self.assertEqual(202, server.post("tasks/gen", task)[0])
        status, body, headers = server.post("tasks/gen:claim?visibilityMs=60000&max=4", b"")
        self.assertEqual(200, status)
        leases = lease_ids(body)
        entries = lease_stream_entries(headers, len(leases))
        self.assertEqual(2, len(entries))
        self.assertEqual(["sjws-stream-window"], [k.lower() for k in headers.keys() if k.lower() == "sjws-stream-window"])
        self.assertEqual(str(window), headers.get("sjws-stream-window"))
        self.assertRegex(entries[0], r"^[0-9a-f]{32}/1$")
        self.assertEqual("-", entries[1])
        # the SDK's effective window: min(configured, advertised)
        self.assertEqual(min(8, window), min(8, int(headers.get("sjws-stream-window"))))
        # a chunk built from the header alone is accepted
        sid, attempt = entries[0].split("/")
        chunk = StreamChunk(bytes.fromhex(sid), 0, FIRST, b"tok", int(attempt))
        status, _, _ = server.post("leases/" + leases[0] + ":chunk", Frame(FrameType.TOKEN, 0, chunk.encode()).encode())
        self.assertEqual(200 if credit else 204, status)

    def test_advertised_small_window_with_credit(self):
        s = _Server(["-Dsjws.swarm.stream.window=4", "-Dsjws.swarm.stream.sinkChunks=4",
                     "-Dsjws.swarm.stream.credit.enabled=true"])
        try:
            self.check(s, 4, True)
        finally:
            s.close()

    def test_defaults_64_256(self):
        s = _Server([])
        try:
            self.check(s, 64, False)
        finally:
            s.close()


class LeaseStreamEntriesTest(unittest.TestCase):
    """No server needed: the reading rule the SDK will use (joined fields, count checked)."""

    def test_joined_fields_and_count_mismatch_fail_closed(self):
        import email.message
        m = email.message.Message()
        m["sjws-lease-stream"] = "-"
        m["sjws-lease-stream"] = "0123456789abcdeffedcba9876543210/2"
        self.assertEqual(["-", "0123456789abcdeffedcba9876543210/2"], lease_stream_entries(m, 2))
        with self.assertRaises(ValueError):
            lease_stream_entries(m, 3)


if __name__ == "__main__":
    unittest.main()

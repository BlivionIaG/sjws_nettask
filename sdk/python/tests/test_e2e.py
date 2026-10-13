"""End to end: a real sjws server with the swarm enabled, driven by the Python unary client.

Needs the built jar: SJWS_JAR=target/sjws_nettask-<version>.jar (CI sets it after `mvn verify`).
Skipped when SJWS_JAR is unset.
"""
import os
import pathlib
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "sdk" / "python"))

from sjws_swarm import Client, DeadlineExceeded, LeaseLost  # noqa: E402

JAR = os.path.abspath(os.environ["SJWS_JAR"]) if os.environ.get("SJWS_JAR") else None


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


@unittest.skipUnless(JAR, "SJWS_JAR not set")
class EndToEndTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp(prefix="sjws-e2e-")
        site = pathlib.Path(cls.tmp, "site")
        site.mkdir()
        (site / "hello.txt").write_text("hello")
        cls.port = free_port()
        token = "e2e-token-0123456789abcdef"
        tokens = pathlib.Path(cls.tmp, "tokens")
        tokens.write_text(f"e2e {token} *\n")
        tokens.chmod(0o600)
        java = os.path.join(os.environ.get("JAVA_HOME", ""), "bin", "java") if os.environ.get("JAVA_HOME") else "java"
        cls.log = open(pathlib.Path(cls.tmp, "server.log"), "w")
        cls.proc = subprocess.Popen(
            [java, "-Dsjws.swarm.enabled=true", "-Dsjws.swarm.dir=" + str(pathlib.Path(cls.tmp, "swarm")),
             "-Dsjws.swarm.node=e2e", "-Dsjws.swarm.tokens.file=" + str(tokens), "-jar", JAR, str(cls.port), str(site)],
            stdout=cls.log, stderr=subprocess.STDOUT, cwd=cls.tmp)
        deadline = time.time() + 30
        while time.time() < deadline:
            try:
                with urllib.request.urlopen(f"http://127.0.0.1:{cls.port}/hello.txt", timeout=1) as r:
                    if r.status == 200:
                        break
            except OSError:
                time.sleep(0.1)
        else:
            raise RuntimeError("server did not start: " + pathlib.Path(cls.tmp, "server.log").read_text())
        cls.client = Client(f"http://127.0.0.1:{cls.port}", token=token)

    @classmethod
    def tearDownClass(cls):
        cls.proc.terminate()
        try:
            cls.proc.wait(10)
        except subprocess.TimeoutExpired:
            cls.proc.kill()
        cls.log.close()
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def test_request_reply_through_a_python_worker(self):
        def worker():
            for lease in self.client.claim("upper.v1", wait=10):
                lease.reply(lease.body.upper())

        t = threading.Thread(target=worker)
        t.start()
        self.assertEqual(b"HELLO SWARM", self.client.call("upper.v1", b"hello swarm", deadline=15))
        t.join(15)

    def test_submit_claim_and_fenced_settles(self):
        task_id = self.client.submit("jobs.v1", b"job-1")
        self.assertEqual(16, len(task_id))
        (lease,) = self.client.claim("jobs.v1", visibility_ms=60_000, wait=5)
        self.assertEqual(b"job-1", lease.body)
        self.assertTrue(lease.id.startswith("jobs.v1~"), lease.id)
        lease.extend(60_000)
        lease.ack()
        with self.assertRaises(LeaseLost):
            lease.ack()
        self.client.submit("jobs.v1", b"poison")
        (bad,) = self.client.claim("jobs.v1", wait=5)
        bad.reject("bad input")
        self.assertEqual([], self.client.claim("jobs.v1"))

    def test_call_without_a_worker_hits_the_deadline(self):
        with self.assertRaises(DeadlineExceeded):
            self.client.call("nobody.v1", b"x", deadline=0.2)

    def test_files_are_still_served(self):
        with urllib.request.urlopen(f"http://127.0.0.1:{self.port}/hello.txt", timeout=5) as r:
            self.assertEqual(b"hello", r.read())


if __name__ == "__main__":
    unittest.main()

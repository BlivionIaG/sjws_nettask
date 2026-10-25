"""Conformance: the Python CREDIT codec against the shared goldens. Run: python3 -m unittest discover sdk/python/tests"""
import pathlib
import sys
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "sdk" / "python"))

from sjws_swarm.credit import CREDIT_SIZE, Credit, decode_credit  # noqa: E402
from sjws_swarm.frames import Frame, FrameType, decode  # noqa: E402

GOLDEN = ROOT / "tests" / "sjws_nettask" / "swarm" / "wire" / "golden-credit-v1.txt"


def _rows(negative):
    for line in GOLDEN.read_text().splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        f = line.split(" ")
        if (f[1] == "!") == negative:
            yield f


class CreditTest(unittest.TestCase):
    def test_golden(self):
        n = 0
        for name, sid, attempt, chunks, nbytes, payload, frame in _rows(False):
            c = decode_credit(bytes.fromhex(payload))
            self.assertEqual(Credit(bytes.fromhex(sid), int(attempt), int(chunks), int(nbytes)), c, name)
            self.assertEqual(payload, c.encode().hex(), name)
            self.assertEqual(CREDIT_SIZE, len(c.encode()), name)
            self.assertEqual(frame, Frame(FrameType.CREDIT, 0, c.encode()).encode().hex(), name)
            got, _ = decode(bytes.fromhex(frame))
            self.assertEqual(FrameType.CREDIT, got.type, name)
            self.assertEqual(c, decode_credit(got.payload), name)
            n += 1
        self.assertEqual(5, n)

    def test_negative_golden(self):
        n = 0
        for name, _, reason, payload, frame in _rows(True):
            got, _ = decode(bytes.fromhex(frame))
            self.assertEqual(FrameType.CREDIT, got.type, name)
            raw = b"" if payload == "-" else bytes.fromhex(payload)
            self.assertEqual(raw, got.payload, name)
            with self.assertRaises(ValueError, msg=name) as e:
                decode_credit(raw)
            self.assertTrue(str(e.exception).startswith(reason), (name, str(e.exception)))
            n += 1
        self.assertEqual(5, n)

    def test_flags_are_ignored(self):
        c = Credit(bytes(16), 1, 2, 3)
        got, _ = decode(Frame(FrameType.CREDIT, 0x5A, c.encode()).encode())
        self.assertEqual(c, decode_credit(got.payload))

    def test_errors(self):
        with self.assertRaises(ValueError):
            Credit(bytes(15), 0, 0, 0)
        for bad in ((-1, 0, 0), (0, 1 << 32, 0), (0, 0, -1)):
            with self.assertRaises(ValueError):
                Credit(bytes(16), *bad)


if __name__ == "__main__":
    unittest.main()

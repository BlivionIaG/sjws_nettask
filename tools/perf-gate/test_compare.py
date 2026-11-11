#!/usr/bin/env python3
"""Unit tests for compare.py. Run: python3 tools/perf-gate/test_compare.py"""
import json
import os
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import compare  # noqa: E402


def write_jsonl(path, rows):
    with open(path, "w") as f:
        for row in rows:
            f.write(json.dumps(row) + "\n")


def wrk(rps, p99_us, errors=0):
    return {"rps": rps, "p50_us": 100, "p99_us": p99_us, "errors": errors, "requests": 1000}


def wrk2(p99_us, errors=0, rate=10000):
    return {"kind": "wrk2", "rps": rate * 0.99, "p50_us": 200, "p99_us": p99_us,
            "errors": errors, "requests": 1000, "rate": rate}


def rss(kb, phase="after-wrk"):
    return {"kind": "rss", "phase": phase, "rss_kb": kb}


class CompareTest(unittest.TestCase):
    def run_cmp(self, base_rows, head_rows, extra=None):
        extra = extra or []
        with tempfile.TemporaryDirectory() as td:
            b = os.path.join(td, "base.jsonl")
            h = os.path.join(td, "head.jsonl")
            write_jsonl(b, base_rows)
            write_jsonl(h, head_rows)
            # capture stdout
            import io
            from contextlib import redirect_stdout
            buf = io.StringIO()
            with redirect_stdout(buf):
                rc = compare.main([b, h, "--threshold", "10"] + extra)
            return rc, buf.getvalue()

    def test_legacy_wrk_only_still_passes_close_numbers(self):
        base = [wrk(10000, 1000)] * 5
        head = [wrk(9800, 1100)] * 5
        # head rps -2%, p99 +10% — both inside 10% / 20%
        rc, out = self.run_cmp(base, head)
        self.assertEqual(rc, 1, msg=out)  # missing RSS is a harness error
        self.assertIn("RSS was not recorded", out)

    def test_wrk_with_rss_recorded_passes(self):
        base = [wrk(10000, 1000)] * 5 + [rss(80_000, "after-wrk")]
        head = [wrk(9800, 1100)] * 5 + [rss(81_000, "after-wrk")]
        rc, out = self.run_cmp(base, head)
        self.assertEqual(rc, 0, msg=out)
        self.assertIn("pending", out)
        self.assertIn("recorded", out)

    def test_throughput_regression(self):
        base = [wrk(10000, 1000)] * 5 + [rss(80_000)]
        head = [wrk(8000, 1000)] * 5 + [rss(80_000)]
        rc, out = self.run_cmp(base, head)
        self.assertEqual(rc, 1, msg=out)
        self.assertIn("regression", out)

    def test_wrk_errors_fail(self):
        base = [wrk(10000, 1000)] * 3 + [rss(80_000)]
        head = [wrk(10000, 1000, errors=2)] * 3 + [rss(80_000)]
        rc, out = self.run_cmp(base, head)
        self.assertEqual(rc, 1, msg=out)
        self.assertIn("errors", out)

    def test_wrk2_relative_p99_regression(self):
        base = [wrk(10000, 1000)] * 3 + [wrk2(2000)] * 3 + [rss(80_000, "after-wrk2")]
        head = [wrk(10000, 1000)] * 3 + [wrk2(3000)] * 3 + [rss(80_000, "after-wrk2")]
        # wrk2 p99 +50% vs default 20% allowance
        rc, out = self.run_cmp(base, head)
        self.assertEqual(rc, 1, msg=out)
        self.assertIn("wrk2 p99", out)
        self.assertIn("regression", out)

    def test_wrk2_within_band_passes(self):
        base = [wrk(10000, 1000)] * 3 + [wrk2(2000)] * 3 + [rss(80_000, "after-wrk2")]
        head = [wrk(10000, 1050)] * 3 + [wrk2(2100)] * 3 + [rss(81_000, "after-wrk2")]
        rc, out = self.run_cmp(base, head)
        self.assertEqual(rc, 0, msg=out)
        self.assertIn("wrk2 p99", out)

    def test_require_wrk2_missing_fails(self):
        base = [wrk(10000, 1000)] * 3 + [rss(80_000)]
        head = [wrk(10000, 1000)] * 3 + [rss(80_000)]
        rc, out = self.run_cmp(base, head, extra=["--require-wrk2"])
        self.assertEqual(rc, 1, msg=out)
        self.assertIn("wrk2 results are required", out)

    def test_one_sided_wrk2_fails(self):
        base = [wrk(10000, 1000)] * 3 + [wrk2(2000)] + [rss(80_000)]
        head = [wrk(10000, 1000)] * 3 + [rss(80_000)]
        rc, out = self.run_cmp(base, head)
        self.assertEqual(rc, 1, msg=out)
        self.assertIn("only one side", out)

    def test_rss_threshold_regression(self):
        base = [wrk(10000, 1000)] * 3 + [rss(80_000, "after-wrk")]
        head = [wrk(10000, 1000)] * 3 + [rss(120_000, "after-wrk")]
        rc, out = self.run_cmp(base, head, extra=["--rss-threshold", "10"])
        self.assertEqual(rc, 1, msg=out)
        self.assertIn("RSS", out)
        self.assertIn("regression", out)

    def test_rss_prefers_after_wrk2(self):
        base = [wrk(10000, 1000), rss(50_000, "warmup"), rss(80_000, "after-wrk"),
                rss(90_000, "after-wrk2")]
        head = [wrk(10000, 1000), rss(50_000, "warmup"), rss(200_000, "after-wrk"),
                rss(91_000, "after-wrk2")]
        # after-wrk would look like a huge regression; after-wrk2 is ~1%
        rc, out = self.run_cmp(base, head, extra=["--rss-threshold", "10"])
        self.assertEqual(rc, 0, msg=out)
        self.assertIn("after-wrk2", out)

    def test_rss_pending_ignores_large_delta(self):
        base = [wrk(10000, 1000)] * 3 + [rss(80_000)]
        head = [wrk(10000, 1000)] * 3 + [rss(400_000)]
        rc, out = self.run_cmp(base, head)
        self.assertEqual(rc, 0, msg=out)
        self.assertIn("pending", out)


if __name__ == "__main__":
    unittest.main()

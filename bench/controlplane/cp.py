#!/usr/bin/env python3
"""Control-plane benchmarks: (a) swap latency, (b) 1k-route lookup, (c) wrk2 p99/p99.9 with and
without config pushes, (d) idle footprint per node.mode. Only measured values are printed/written."""
import argparse, json, os, re, subprocess, sys, tempfile, time
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "footprint"))
import footprint as fp  # noqa: E402

REPO = fp.REPO
BENCH = os.path.join(HERE, "CpBench.java")


def java(a):
    return os.path.join(a.java_home, "bin", "java")


def run(a, *args):
    out = subprocess.run([java(a), "-cp", a.jar, BENCH, *args], capture_output=True, text=True, check=True).stdout
    print(out, end="")
    return out


def wait_port(port, p, secs=30):
    end = time.time() + secs
    while time.time() < end:
        try:
            if fp.get(port):
                return
        except OSError:
            pass
        if p.poll() is not None:
            raise SystemExit("server exited")
        time.sleep(0.02)
    raise SystemExit("server did not start")


def wrk2_lat(out):
    res = {}
    for k in ("50.000", "99.000", "99.900", "99.990"):
        m = re.search(r"^\s*" + re.escape(k) + r"%\s+([\d.]+)(us|ms|s)\b", out, re.M)
        if m:
            v = float(m.group(1)) * {"us": 0.001, "ms": 1, "s": 1000}[m.group(2)]
            res["p" + k.rstrip("0").rstrip(".")] = round(v, 3)
    m = re.search(r"Requests/sec:\s+([\d.]+)", out)
    res["rps"] = float(m.group(1)) if m else None
    return res


def serve(a, cfg, push_ms):
    cmd = [java(a), *fp.JVM_FLAGS, "-cp", a.jar, BENCH, "serve", "--config", cfg, "--push-ms", str(push_ms),
           "--push-seconds", str(a.push_seconds)]
    log = open(cfg + f".push{push_ms}.log", "w")
    return subprocess.Popen(cmd, stdout=log, stderr=subprocess.STDOUT), log.name


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--java-home", default=os.environ.get("JAVA_HOME", "/usr"))
    ap.add_argument("--jar", default=None)
    ap.add_argument("--routes", type=int, default=1000)
    ap.add_argument("--port", type=int, default=18090)
    ap.add_argument("--rate", type=int, default=50000)
    ap.add_argument("--duration", type=int, default=30)
    ap.add_argument("--push-ms", default="100", help="comma list; empty for no push runs")
    ap.add_argument("--wrk-duration", type=int, default=10, help="unthrottled wrk run (push 0 only)")
    ap.add_argument("--modes", default="", help="comma list of node.mode values for (d), e.g. standalone,data")
    ap.add_argument("--skip", default="", help="comma list of a,b,c,d to skip")
    ap.add_argument("--out", default=os.path.join(HERE, "results"))
    a = ap.parse_args()
    import glob
    a.jar = a.jar or [j for j in glob.glob(os.path.join(REPO, "target", "sjws_nettask-*.jar")) if "sources" not in j][0]
    a.push_seconds = a.duration + 15
    skip = set(filter(None, a.skip.split(",")))
    res = {"jar": a.jar, "routes": a.routes}
    root = tempfile.mkdtemp(prefix="cpbench-")
    with open(os.path.join(root, "1k.bin"), "wb") as f:
        f.write(os.urandom(1024))
    cfg = os.path.join(root, "sjws.yaml")
    run(a, "gen", "--routes", str(a.routes), "--root", root, "--port", str(a.port), "--out", cfg)
    if "a" not in skip:
        print("== (a) config swap"); res["swap"] = run(a, "swap", "--config", cfg, "--iters", "2000")
    if "b" not in skip:
        print("== (b) route lookup"); res["route"] = run(a, "route", "--config", cfg, "--seconds", "5")
    if "c" not in skip:
        res["wrk2"] = {}
        for push in [0] + [int(x) for x in a.push_ms.split(",") if x]:
            p, log = serve(a, cfg, push)
            try:
                wait_port(a.port, p)
                fp.sh(["wrk", "-t4", "-c100", "-d5s", f"http://127.0.0.1:{a.port}/1k.bin"])  # warmup
                if push == 0:
                    w = fp.sh(["wrk", "-t4", "-c100", f"-d{a.wrk_duration}s", f"http://127.0.0.1:{a.port}/1k.bin"])
                    m = re.search(r"Requests/sec:\s+([\d.]+)", w)
                    wrk_rps = float(m.group(1)) if m else None
                out = fp.sh(["wrk2", "-t4", "-c100", f"-d{a.duration}s", f"-R{a.rate}", "--latency",
                             f"http://127.0.0.1:{a.port}/1k.bin"])
                r = wrk2_lat(out)
                if push == 0:
                    r["wrk_max_rps"] = wrk_rps
                p.terminate(); p.wait(10)
                r["server"] = open(log).read().strip().splitlines()
                print(f"== (c) push_ms={push} rate={a.rate}", json.dumps(r))
                res["wrk2"][str(push)] = r
            finally:
                if p.poll() is None:
                    p.kill()
    if "d" not in skip and a.modes:
        res["footprint"] = {}
        for mode in a.modes.split(","):
            mcfg = os.path.join(root, f"sjws-{mode}.yaml")
            args = ["gen", "--routes", "0", "--root", root, "--port", str(a.port), "--out", mcfg]
            if mode != "omit":
                args += ["--mode", mode]
            run(a, *args)
            cmd = [java(a), *fp.JVM_FLAGS, "-jar", a.jar, f"--config={mcfg}"]
            t0 = time.monotonic()
            p = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            try:
                wait_port(a.port, p)
                start_ms = (time.monotonic() - t0) * 1000
                time.sleep(3)
                r = {"startup_ms": round(start_ms, 1), "rss_kb": fp.rss_kb(p.pid), "threads": fp.threads(p.pid),
                     "heap_after_gc_kb": fp.heap_used_kb(a.java_home, p.pid),
                     "nmt_total_kb": fp.nmt(a.java_home, p.pid).get("Total"),
                     "last_good_file": os.path.exists(mcfg + ".last-good")}
                print(f"== (d) mode={mode}", json.dumps(r)); res["footprint"][mode] = r
            finally:
                p.terminate(); p.wait(10)
    os.makedirs(a.out, exist_ok=True)
    with open(os.path.join(a.out, "results.json"), "w") as f:
        json.dump(res, f, indent=2)


if __name__ == "__main__":
    main()

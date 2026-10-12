#!/usr/bin/env python3
"""Reproducible JVM footprint benchmark for sjws_nettask (HTTP/1.1 only).

Measures: startup time, idle RSS, NMT summary (heap / non-heap), memory per idle
keep-alive connection (RSS, NMT committed, heap-after-GC deltas), and 1 KB
throughput with wrk. Writes results.json, results.csv and report.md.
Only measured values are written; nothing is estimated.
"""
import argparse, glob, json, os, platform, re, shutil, socket, subprocess, sys, time, statistics, csv, resource

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
JVM_FLAGS = ["-Xms256m", "-Xmx256m", "-XX:+UseG1GC", "-XX:NativeMemoryTracking=summary",
             "-XX:+UnlockDiagnosticVMOptions", "-XX:-PrintNMTStatistics"]


def sh(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, **kw).stdout


def rss_kb(pid):
    with open(f"/proc/{pid}/status") as f:
        for line in f:
            if line.startswith("VmRSS:"):
                return int(line.split()[1])


def threads(pid):
    return len(os.listdir(f"/proc/{pid}/task"))


def jcmd(java_home, pid, *args):
    return sh([os.path.join(java_home, "bin", "jcmd"), str(pid), *args])


def nmt(java_home, pid):
    """Parse 'VM.native_memory summary' into {category: committed_kb} plus total."""
    out = jcmd(java_home, pid, "VM.native_memory", "summary")
    res = {}
    m = re.search(r"Total: reserved=(\d+)KB, committed=(\d+)KB", out)
    if m:
        res["Total"] = int(m.group(2))
    for m in re.finditer(r"-\s+([A-Za-z][\w ]*?) \(reserved=(\d+)KB, committed=(\d+)KB\)", out):
        res[m.group(1).strip()] = int(m.group(3))
    return res


def heap_used_kb(java_home, pid, gc=True):
    if gc:
        jcmd(java_home, pid, "GC.run")
        time.sleep(0.5)
    out = jcmd(java_home, pid, "GC.heap_info")
    m = re.search(r"used (\d+)K", out)
    return int(m.group(1)) if m else None


def get(port, path="/1k.bin"):
    s = socket.create_connection(("127.0.0.1", port), timeout=2)
    s.sendall(f"GET {path} HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n".encode())
    data = b""
    while True:
        b = s.recv(65536)
        if not b:
            break
        data += b
    s.close()
    return data.startswith(b"HTTP/1.1 200")


def settle(pid, secs=2.0):
    time.sleep(secs)
    return rss_kb(pid)


class Server:
    def __init__(self, a, root):
        self.a, self.root = a, root

    def __enter__(self):
        a = self.a
        cmd = [os.path.join(a.java_home, "bin", "java"), *JVM_FLAGS, *a.jvm_flag,
               "-jar", a.jar, str(a.port), self.root,
               f"--max-connections={a.max_conns}", f"--max-connections-per-ip={a.max_conns}",
               "--idle-timeout=3600", "--header-timeout=3600", "--backlog=4096"]
        self.cmd = cmd
        self.t0 = time.monotonic()
        self.p = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        deadline = self.t0 + 30
        while True:
            try:
                if get(a.port):
                    break
            except OSError:
                pass
            if time.monotonic() > deadline or self.p.poll() is not None:
                raise SystemExit("server did not start: " + " ".join(cmd))
            time.sleep(0.005)
        self.startup_ms = (time.monotonic() - self.t0) * 1000
        return self

    def __exit__(self, *e):
        self.p.terminate()
        try:
            self.p.wait(10)
        except subprocess.TimeoutExpired:
            self.p.kill()


def open_idle(port, n):
    """Open n keep-alive connections, each serves one 1 KB request, then idles."""
    socks = []
    req = b"GET /1k.bin HTTP/1.1\r\nHost: x\r\n\r\n"
    for i in range(n):
        s = socket.create_connection(("127.0.0.1", port), timeout=10)
        s.sendall(req)
        socks.append(s)
        if len(socks) % 500 == 0:
            drain(socks[-500:])
    drain(socks[len(socks) - len(socks) % 500:])
    return socks


def drain(socks):
    for s in socks:
        got = b""
        while b"\r\n\r\n" not in got or len(got) < got.find(b"\r\n\r\n") + 4 + 1024:
            b = s.recv(4096)
            if not b:
                raise SystemExit("connection closed by server")
            got += b


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--java-home", default=os.environ.get("JAVA_HOME", "/usr"))
    ap.add_argument("--jar", default=None)
    ap.add_argument("--port", type=int, default=18080)
    ap.add_argument("--conns", default="0,1000,10000")
    ap.add_argument("--max-conns", type=int, default=20000)
    ap.add_argument("--startup-runs", type=int, default=5)
    ap.add_argument("--wrk-duration", default="10s")
    ap.add_argument("--wrk-conns", default="100")
    ap.add_argument("--wrk-threads", type=int, default=4)
    ap.add_argument("--wrk-reps", type=int, default=3)
    ap.add_argument("--jvm-flag", action="append", default=[], help="extra JVM flag (repeatable)")
    ap.add_argument("--out", default=os.path.join(HERE, "results"))
    a = ap.parse_args()
    if a.jar is None:
        jars = [j for j in glob.glob(os.path.join(REPO, "target", "sjws_nettask-*.jar")) if "sources" not in j]
        if not jars:
            raise SystemExit("no jar: run `mvn -B package -DskipTests` first")
        a.jar = jars[0]
    conns = [int(c) for c in a.conns.split(",")]
    soft, hard = resource.getrlimit(resource.RLIMIT_NOFILE)
    need = max(conns) * 2 + 1024
    if soft < need:
        resource.setrlimit(resource.RLIMIT_NOFILE, (min(need, hard), hard))
    os.makedirs(a.out, exist_ok=True)
    root = os.path.join(a.out, "docroot")
    os.makedirs(root, exist_ok=True)
    with open(os.path.join(root, "1k.bin"), "wb") as f:
        f.write(b"x" * 1024)

    res = {"env": env(a), "jvm_flags": JVM_FLAGS + a.jvm_flag}

    # 1. startup: process spawn -> first 200 on /1k.bin (fresh JVM each run)
    st = []
    for _ in range(a.startup_runs):
        with Server(a, root) as s:
            st.append(s.startup_ms)
    res["startup_ms"] = {"runs": [round(x, 1) for x in st], "median": round(statistics.median(st), 1),
                         "min": round(min(st), 1)}

    # 2. idle footprint + per-connection deltas (one JVM, connection counts ascending)
    with Server(a, root) as s:
        pid = s.p.pid
        res["command"] = s.cmd
        time.sleep(1)
        base = None
        rows = []
        held = []
        for n in sorted(conns):
            held += open_idle(a.port, n - len(held))
            heap = heap_used_kb(a.java_home, pid)
            r = settle(pid)
            m = nmt(a.java_home, pid)
            row = {"conns": n, "rss_kb": r, "heap_used_after_gc_kb": heap, "threads": threads(pid),
                   "nmt_committed_kb": m}
            if base is None:
                base = row
            else:
                d = n - base["conns"]
                row["per_conn_bytes"] = {
                    "rss": round((r - base["rss_kb"]) * 1024 / d),
                    "heap_after_gc": round((heap - base["heap_used_after_gc_kb"]) * 1024 / d),
                    "nmt_total": round((m["Total"] - base["nmt_committed_kb"]["Total"]) * 1024 / d),
                    "nmt_non_heap": round(((m["Total"] - m.get("Java Heap", 0))
                                           - (base["nmt_committed_kb"]["Total"] - base["nmt_committed_kb"].get("Java Heap", 0)))
                                          * 1024 / d),
                }
                if os.path.exists("/proc/net/sockstat"):
                    row["sockstat"] = open("/proc/net/sockstat").read().split("\n")[1]
            rows.append(row)
            print(f"conns={n} rss={r}KB heap={heap}KB threads={row['threads']} {row.get('per_conn_bytes', '')}",
                  flush=True)
        res["idle"] = rows
        for x in held:
            x.close()

    # 3. throughput: wrk on a fresh JVM, warm-up then reps
    wrk = shutil.which("wrk")
    if wrk:
        with Server(a, root) as s:
            url = f"http://127.0.0.1:{a.port}/1k.bin"
            out = []
            for wc in [int(c) for c in a.wrk_conns.split(",")]:
                sh([wrk, "-t", str(a.wrk_threads), "-c", str(wc), "-d", "5s", url])  # warm-up
                reps = []
                for _ in range(a.wrk_reps):
                    o = sh([wrk, "-t", str(a.wrk_threads), "-c", str(wc), "-d", a.wrk_duration, "--latency", url])
                    m = re.search(r"Requests/sec:\s+([\d.]+)", o)
                    p99 = re.search(r"99%\s+([\d.]+\w+)", o)
                    reps.append({"rps": float(m.group(1)) if m else None, "p99": p99.group(1) if p99 else None})
                out.append({"conns": wc, "reps": reps,
                            "median_rps": statistics.median([r["rps"] for r in reps if r["rps"]]),
                            "rss_after_kb": rss_kb(s.p.pid)})
            res["throughput_1k"] = out
    else:
        res["throughput_1k"] = "wrk not found"

    with open(os.path.join(a.out, "results.json"), "w") as f:
        json.dump(res, f, indent=2)
    with open(os.path.join(a.out, "results.csv"), "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["metric", "value", "unit"])
        w.writerow(["startup_median", res["startup_ms"]["median"], "ms"])
        for r in res["idle"]:
            w.writerow([f"rss@{r['conns']}", r["rss_kb"], "KB"])
            w.writerow([f"heap_after_gc@{r['conns']}", r["heap_used_after_gc_kb"], "KB"])
            w.writerow([f"nmt_total@{r['conns']}", r["nmt_committed_kb"]["Total"], "KB"])
            w.writerow([f"threads@{r['conns']}", r["threads"], "count"])
            for k, v in r.get("per_conn_bytes", {}).items():
                w.writerow([f"per_conn_{k}@{r['conns']}", v, "bytes"])
        if isinstance(res["throughput_1k"], list):
            for t in res["throughput_1k"]:
                w.writerow([f"rps_1k@c{t['conns']}", t["median_rps"], "req/s"])
    print(json.dumps({"startup_ms": res["startup_ms"], "per_conn": [r.get("per_conn_bytes") for r in res["idle"]],
                      "throughput": res["throughput_1k"]}, indent=1))


def env(a):
    cpu = ""
    try:
        cpu = re.search(r"model name\s*:\s*(.*)", open("/proc/cpuinfo").read()).group(1)
    except Exception:
        pass
    mem = sh(["free", "-m"]).split("\n")[1].split()[1] + " MB" if shutil.which("free") else ""
    jv = subprocess.run([os.path.join(a.java_home, "bin", "java"), "-version"], capture_output=True, text=True).stderr
    return {"cpu": cpu, "nproc": os.cpu_count(), "mem": mem, "kernel": platform.release(),
            "java": jv.strip().split("\n"), "wrk": sh(["wrk", "-v"]).split("\n")[0] if shutil.which("wrk") else None,
            "git": sh(["git", "-C", REPO, "rev-parse", "--short", "HEAD"]).strip(),
            "time": time.strftime("%Y-%m-%dT%H:%M:%S%z")}


if __name__ == "__main__":
    main()

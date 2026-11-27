#!/usr/bin/env python3
"""Performance guardrails (docs/shared-code.md, section 4). Python 3 standard library only.

  perf_check.py web-size [--dist web/dist] [--compare OTHER_DIST] [--strict]
      The built web app's chunks (hashes stripped from the names), raw and gzip bytes, against
      shared/perf/baseline.json ("web"). Fails when the main chunk (index .js / .css) grows more
      than 0.5% or 1 KB gzip, whichever is less, or any other chunk (the workers, ORT) more than
      HASH_SLACK bytes gzip (the hashed names a chunk holds change its gzip size by a few bytes),
      or a chunk is new. With --compare, prints both builds side by side and checks each chunk is
      within +-0.5% gzip of the other (for a change that should move nothing).
  perf_check.py web-size --update --reason TEXT
      Writes the chunks of --dist into the baseline (a PR that grows a chunk on purpose says why).
  perf_check.py bench --base BASE_REPO [--runs 7]
      A/B of web/tests/bench.cjs: base and head (this repository) alternating, --runs times each.
      Fails when a head median is over 1.05x the base's.
  perf_check.py fusion-time [--runs 7]
      Median wall time of `node web/tests/fusion.cjs` (the pure detection tests).
  perf_check.py jvm-bench [--out FILE]
      Prints the JVM tracker bench results (app/src/test/.../analysis/TrackerBench.kt, run with
      ONIT_BENCH=1; it writes app/build/bench/tracker.json) as perf_check reads them.
  perf_check.py record [--runs 7] [--jvm FILE]
      Rewrites shared/perf/baseline.json from this tree: web-size of --dist, bench.cjs medians,
      the fusion.cjs time, and the JVM bench (a tracker.json, if given). Machine-dependent numbers
      carry the machine they were measured on. --tree times another checkout instead (the base).
"""
import argparse
import gzip
import json
import os
import platform
import re
import statistics
import subprocess
import sys
import time
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
BASELINE = ROOT / "shared/perf/baseline.json"
HASH_SLACK = 32
MAIN_PCT = 0.005
MAIN_BYTES = 1024
BENCH_LIMIT = 1.05
# Vite's names: <name>-<8 characters of [A-Za-z0-9_-]>.<ext>
HASH_RE = re.compile(r"^(.*)-[A-Za-z0-9_-]{8}(\.[a-z0-9]+)$")


def chunks(dist):
    """{stripped name: {"raw", "gzip"}} of dist/assets."""
    out = {}
    assets = pathlib.Path(dist) / "assets"
    if not assets.is_dir():
        sys.exit(f"perf_check: no {assets} (build the web app first)")
    for f in sorted(assets.iterdir()):
        if not f.is_file():
            continue
        m = HASH_RE.match(f.name)
        name = m.group(1) + m.group(2) if m else f.name
        data = f.read_bytes()
        n = 2
        base = name
        while name in out:  # two chunks with one name: number them in file order
            name = f"{base}#{n}"
            n += 1
        out[name] = {"raw": len(data), "gzip": len(gzip.compress(data, compresslevel=9, mtime=0))}
    return out


def is_main(name):
    return name.startswith("index.") or name.startswith("index#")


def load_baseline():
    if BASELINE.exists():
        return json.loads(BASELINE.read_text(encoding="utf-8"))
    return {}


def save_baseline(b):
    BASELINE.parent.mkdir(parents=True, exist_ok=True)
    BASELINE.write_text(json.dumps(b, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def fmt(n):
    return f"{n:,}"


def web_size(a):
    now = chunks(a.dist)
    if a.update:
        if not a.reason:
            sys.exit("perf_check: --update needs --reason")
        b = load_baseline()
        b["web"] = {"note": "web/dist/assets of the release build (npm run build), hashes stripped; gzip at level 9", "reason": a.reason, "chunks": now}
        save_baseline(b)
        print(f"perf_check: wrote {len(now)} chunks to {BASELINE.relative_to(ROOT)}")
        return 0
    if a.compare:
        other = chunks(a.compare)
        bad = []
        print(f"{'chunk':48} {'raw A':>12} {'raw B':>12} {'gzip A':>10} {'gzip B':>10} {'gzip %':>8}")
        for k in sorted(set(now) | set(other)):
            x, y = other.get(k), now.get(k)
            if not x or not y:
                print(f"{k:48} {'only in ' + ('B' if y else 'A'):>12}")
                bad.append(k)
                continue
            pct = 100.0 * (y["gzip"] - x["gzip"]) / x["gzip"]
            print(f"{k:48} {fmt(x['raw']):>12} {fmt(y['raw']):>12} {fmt(x['gzip']):>10} {fmt(y['gzip']):>10} {pct:+7.3f}%")
            if abs(pct) > 100 * MAIN_PCT:
                bad.append(k)
        print(f"A = {a.compare}, B = {a.dist}")
        if bad:
            print(f"perf_check: not within +-{100 * MAIN_PCT}% gzip: {', '.join(bad)}", file=sys.stderr)
            return 1
        return 0
    base = load_baseline().get("web", {}).get("chunks")
    if not base:
        sys.exit(f"perf_check: no web baseline in {BASELINE.relative_to(ROOT)}")
    bad = []
    for k in sorted(set(now) | set(base)):
        x, y = base.get(k), now.get(k)
        if y is None:
            print(f"{k:48} gone (was {fmt(x['gzip'])} B gzip)")
            continue
        if x is None:
            print(f"{k:48} NEW {fmt(y['gzip'])} B gzip")
            bad.append(f"{k} is new")
            continue
        d = y["gzip"] - x["gzip"]
        limit = min(MAIN_BYTES, MAIN_PCT * x["gzip"]) if is_main(k) else HASH_SLACK
        flag = "  OVER" if d > limit else ""
        print(f"{k:48} {fmt(y['raw']):>12} raw {fmt(y['gzip']):>10} gzip {d:+7d} B (limit +{int(limit)}){flag}")
        if d > limit:
            bad.append(f"{k} +{d} B gzip")
    if bad:
        msg = "the web app grew: " + "; ".join(bad)
        hint = "if it is on purpose, run `python3 ci/perf_check.py web-size --update --reason '...'` after `npm run build` and commit shared/perf/baseline.json."
        if not a.strict:
            # A PR check only warns: a feature may grow the app on purpose. --strict (the shared-code tasks) fails.
            print(f"::warning title=Web app size::{msg}. {hint}")
            return 0
        print("perf_check: " + msg, file=sys.stderr)
        print("perf_check: " + hint, file=sys.stderr)
        return 1
    print("perf_check: web chunks within the baseline")
    return 0


def node_json(cmd, cwd):
    out = subprocess.run(cmd, cwd=cwd, check=True, capture_output=True, text=True).stdout
    for line in reversed(out.strip().splitlines()):
        if line.startswith("{"):
            return json.loads(line)
    sys.exit(f"perf_check: no JSON from {' '.join(cmd)} in {cwd}:\n{out}")


def bench_once(repo, iters=None):
    cmd = ["node", "tests/bench.cjs"] + ([str(iters)] if iters else [])
    return node_json(cmd, pathlib.Path(repo) / "web")


def bench(a):
    base = pathlib.Path(a.base).resolve()
    if not (base / "web/tests/bench.cjs").exists():
        # A base from before bench.cjs: run head's bench on the base's sources.
        sys.exit(f"perf_check: {base}/web/tests/bench.cjs is missing (copy this tree's web/tests/bench.cjs there)")
    runs = {"base": [], "head": []}
    for i in range(a.runs):
        for side, repo in (("base", base), ("head", ROOT)) if i % 2 == 0 else (("head", ROOT), ("base", base)):
            runs[side].append(bench_once(repo))
    keys = sorted(runs["head"][0]["ms"])
    worst = 0.0
    print(f"{'bench (ms)':16} {'base':>10} {'head':>10} {'head/base':>10}")
    res = {}
    for k in keys:
        b = statistics.median(r["ms"][k] for r in runs["base"])
        h = statistics.median(r["ms"][k] for r in runs["head"])
        ratio = h / b if b > 0 else 1.0
        worst = max(worst, ratio)
        res[k] = {"base": round(b, 5), "head": round(h, 5), "ratio": round(ratio, 4)}
        print(f"{k:16} {b:10.4f} {h:10.4f} {ratio:10.3f}")
    same = all(r["out"] == runs["base"][0]["out"] for r in runs["base"] + runs["head"])
    print(f"outputs identical between base and head: {same}")
    if a.json:
        pathlib.Path(a.json).write_text(json.dumps({"runs": a.runs, "ms": res, "sameOutput": same}, indent=2) + "\n")
    if worst > BENCH_LIMIT:
        print(f"perf_check: head is {worst:.3f}x base (limit {BENCH_LIMIT})", file=sys.stderr)
        return 1
    if not same:
        print("perf_check: base and head computed different results", file=sys.stderr)
        return 1
    return 0


def fusion_time(runs, tree=ROOT):
    times = []
    for _ in range(runs):
        t = time.perf_counter()
        out = subprocess.run(["node", "tests/fusion.cjs"], cwd=pathlib.Path(tree) / "web", check=True, capture_output=True, text=True).stdout
        times.append((time.perf_counter() - t) * 1000)
        if '"fail"' in out or "FAIL" in out:
            sys.exit(f"perf_check: fusion.cjs failed:\n{out}")
    return {"runs": runs, "median_ms": round(statistics.median(times), 1), "min_ms": round(min(times), 1)}


def machine():
    cpu = ""
    try:
        for line in open("/proc/cpuinfo", encoding="utf-8"):
            if line.startswith("model name"):
                cpu = line.split(":", 1)[1].strip()
                break
    except OSError:
        pass
    node = subprocess.run(["node", "--version"], capture_output=True, text=True).stdout.strip()
    return {"cpu": cpu or platform.processor(), "cores": os.cpu_count(), "node": node, "python": platform.python_version()}


def record(a):
    b = load_baseline()
    if a.dist and (pathlib.Path(a.dist) / "assets").is_dir():
        b["web"] = {"note": "web/dist/assets of the release build (npm run build), hashes stripped; gzip at level 9", "reason": a.reason or "baseline", "chunks": chunks(a.dist)}
    tree = pathlib.Path(a.tree).resolve() if a.tree else ROOT
    runs = [bench_once(tree) for _ in range(a.runs)]
    b["webBench"] = {
        "note": "node web/tests/bench.cjs: median ms of each part over the runs (machine-dependent: compare A/B on one machine, `perf_check.py bench`)",
        "machine": machine(),
        "tree": a.label or "this tree",
        "runs": a.runs,
        "ms": {k: round(statistics.median(r["ms"][k] for r in runs), 5) for k in sorted(runs[0]["ms"])},
        "out": runs[0]["out"],
    }
    b["fusionCjs"] = {"note": "wall time of `node web/tests/fusion.cjs` (machine-dependent)", "machine": machine(), "tree": a.label or "this tree", **fusion_time(a.runs, tree)}
    if a.jvm:
        j = json.loads(pathlib.Path(a.jvm).read_text(encoding="utf-8"))
        b["jvmBench"] = {"note": "TrackerBench.kt (ONIT_BENCH=1): median ms per second of video (machine-dependent)", **j}
    save_baseline(b)
    print(json.dumps({k: v for k, v in b.items() if k != "web"}, indent=2))
    return 0


def jvm_bench(a):
    f = pathlib.Path(a.out or ROOT / "app/build/bench/tracker.json")
    if not f.exists():
        sys.exit(f"perf_check: no {f}: run `ONIT_BENCH=1 ./gradlew :app:testDebugUnitTest --tests com.elad.faceblur.analysis.TrackerBench` first")
    print(f.read_text(encoding="utf-8"))
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    w = sub.add_parser("web-size")
    w.add_argument("--dist", default=str(ROOT / "web/dist"))
    w.add_argument("--compare")
    w.add_argument("--update", action="store_true")
    w.add_argument("--reason")
    w.add_argument("--strict", action="store_true", help="fail (not only warn) when a chunk grew past the baseline")
    bn = sub.add_parser("bench")
    bn.add_argument("--base", required=True)
    bn.add_argument("--runs", type=int, default=7)
    bn.add_argument("--json")
    ft = sub.add_parser("fusion-time")
    ft.add_argument("--runs", type=int, default=7)
    jb = sub.add_parser("jvm-bench")
    jb.add_argument("--out")
    r = sub.add_parser("record")
    r.add_argument("--dist", default=str(ROOT / "web/dist"))
    r.add_argument("--runs", type=int, default=7)
    r.add_argument("--jvm")
    r.add_argument("--reason")
    r.add_argument("--tree", help="time another checkout's web/src (with this tree's web/tests/bench.cjs copied in)")
    r.add_argument("--label", help="what was measured, e.g. 'develop 4e974ce'")
    a = ap.parse_args()
    if a.cmd == "web-size":
        return web_size(a)
    if a.cmd == "bench":
        return bench(a)
    if a.cmd == "fusion-time":
        print(json.dumps(fusion_time(a.runs)))
        return 0
    if a.cmd == "jvm-bench":
        return jvm_bench(a)
    return record(a)


if __name__ == "__main__":
    sys.exit(main())

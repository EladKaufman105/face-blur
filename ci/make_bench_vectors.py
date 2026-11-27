#!/usr/bin/env python3
"""Writes shared/vectors/tracker/bench.json: the seeded input both trackers are timed on
(app/src/test/.../analysis/TrackerBench.kt and web/tests/bench.cjs). Python 3 standard library
only; the same seed always makes the same file.

100 s of video at 30 fps (3,000 frames), detections on every second frame (15 a second, the app's
normal rate), six faces that move, change size, leave and come back, detector dropouts, crop
re-check scores on some, clutter (weak one-frame detections) and two hard cuts.

    python3 ci/make_bench_vectors.py           writes the file
    python3 ci/make_bench_vectors.py --check   exits 1 if the file isn't what the script makes
"""
import argparse
import json
import math
import pathlib
import random
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "shared/vectors/tracker/bench.json"
SEED = 20261127
FPS = 30
N = 3000
EVERY = 2
FACES = 6
ASPECT = 1080 / 1920  # height / width of a landscape 1920x1080 frame
CUTS = [1000, 2000]


def make():
    r = random.Random(SEED)
    faces = []
    for k in range(FACES):
        faces.append({
            "x0": 0.15 + 0.7 * r.random(), "y0": 0.2 + 0.6 * r.random(),
            "ax": 0.05 + 0.1 * r.random(), "ay": 0.03 + 0.06 * r.random(),
            "px": 6 + 20 * r.random(), "py": 5 + 15 * r.random(),
            "size": 0.04 + 0.1 * r.random(), "base": 0.45 + 0.5 * r.random(),
            # Visible from..to, then away for a while (a face that leaves and comes back).
            "on": 200 + int(600 * r.random()), "off": 40 + int(120 * r.random()), "phase": int(800 * r.random()),
        })
    dets = {}
    for i in range(0, N, EVERY):
        shot = sum(1 for c in CUTS if i >= c)
        frame = []
        t = i / FPS
        for k, f in enumerate(faces):
            if (i + f["phase"]) % (f["on"] + f["off"]) >= f["on"]:
                continue
            if r.random() < 0.1:  # the detector misses it on this frame
                continue
            shift = 0.13 * shot * (1 if k % 2 else -1)
            cx = f["x0"] + shift + f["ax"] * math.sin(2 * math.pi * t / f["px"]) + 0.004 * (r.random() - 0.5)
            cy = f["y0"] + f["ay"] * math.sin(2 * math.pi * t / f["py"] + k) + 0.004 * (r.random() - 0.5)
            cx = min(0.97, max(0.03, cx))
            w = f["size"] * (1 + 0.15 * math.sin(2 * math.pi * t / 9 + k)) * (1 + 0.03 * (r.random() - 0.5))
            h = w / ASPECT * 1.25
            score = min(0.99, max(0.05, f["base"] + 0.25 * (r.random() - 0.5)))
            verify = round(0.5 + 0.5 * r.random(), 4) if score < 0.8 and r.random() < 0.4 else 0
            frame.append([round(cx, 5), round(cy, 5), round(w, 5), round(h, 5), round(score, 4), verify])
        if r.random() < 0.15:  # clutter: a weak detection somewhere
            frame.append([round(0.05 + 0.9 * r.random(), 5), round(0.05 + 0.9 * r.random(), 5), round(0.02 + 0.05 * r.random(), 5),
                          round(0.04 + 0.08 * r.random(), 5), round(0.3 + 0.35 * r.random(), 4), 0])
        dets[str(i)] = frame
    return {
        "about": "Input of the tracker benches (TrackerBench.kt, web/tests/bench.cjs), made by ci/make_bench_vectors.py. "
                 "dets: frame -> [cx, cy, w, h, score, verify] (fractions of the upright frame). No camera motion.",
        "seed": SEED, "fps": FPS, "n": N, "aspect": round(ASPECT, 6), "cuts": CUTS, "dets": dets,
    }


def text():
    d = make()
    # One frame a line: small diffs, readable.
    lines = [f'    "{k}": {json.dumps(v, separators=(",", ":"))}' for k, v in d["dets"].items()]
    head = {k: v for k, v in d.items() if k != "dets"}
    body = json.dumps(head, indent=2)[:-2] + ',\n  "dets": {\n' + ",\n".join(lines) + "\n  }\n}\n"
    json.loads(body)
    return body


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    a = ap.parse_args()
    t = text()
    if a.check:
        if not OUT.exists() or OUT.read_text(encoding="utf-8") != t:
            print(f"make_bench_vectors: {OUT.relative_to(ROOT)} is not what the script makes", file=sys.stderr)
            return 1
        return 0
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(t, encoding="utf-8")
    d = json.loads(t)
    print(f"make_bench_vectors: wrote {OUT.relative_to(ROOT)}: {sum(len(v) for v in d['dets'].values())} detections on {len(d['dets'])} frames, {len(t):,} bytes")
    return 0


if __name__ == "__main__":
    sys.exit(main())

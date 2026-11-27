#!/usr/bin/env bash
# Fetches the models the app downloads at run time, exactly as the app's model registry
# (app/src/main/assets/model-registry.json) pins them, for the instrumented tests and the release
# self-test. Each file comes out of git at its pinned commit (git fetch + git show: no CDN, no
# raw.githubusercontent.com rate limits), and is checked against the registry's size and SHA-256.
#
# Usage, from the repository root:
#   bash ci/fetch-models.sh [--out DIR] [--with-speech] [PACK...]
# PACK: faces, people, speakers, selfie (the default: these four), speech-he, speech-any.
# DIR: where the files go, by their registry names (default app/src/androidTest/assets/models).
# The speech models (574 MB, a release asset) are fetched only when named or with --with-speech;
# the tests use whisper's "tiny" model instead (ci/fetch-test-media.sh).
set -euo pipefail

out=app/src/androidTest/assets/models
speech=0
packs=()
while [ $# -gt 0 ]; do
  case "$1" in
    --out) out="$2"; shift 2 ;;
    --with-speech) speech=1; shift ;;
    -h|--help) sed -n '2,13p' "$0"; exit 0 ;;
    *) packs+=("$1"); shift ;;
  esac
done
[ ${#packs[@]} -gt 0 ] || packs=(faces people speakers selfie)
[ "$speech" = 1 ] && packs+=(speech-he)
mkdir -p "$out"

registry=app/src/main/assets/model-registry.json
[ -f "$registry" ] || { echo "no $registry (run from the repository root)" >&2; exit 1; }

# One line per file: pack, name, size, sha256, url (a pending pack is refused).
lines=$(python3 -I - "$registry" "${packs[@]}" <<'PY'
import json, sys
reg = json.load(open(sys.argv[1]))
want = sys.argv[2:]
packs = {p["id"]: p for p in reg["packs"]}
for w in want:
    p = packs.get(w)
    if p is None:
        sys.exit(f"unknown pack {w}")
    if p.get("pending"):
        sys.exit(f"pack {w} is pending: {p['pending']}")
    if p.get("unpinned"):
        sys.exit(f"pack {w} is unpinned (no checksum to check): not fetched for CI")
    for f in p["files"]:
        print(w, f["name"], f["size"], f["sha256"], f["url"])
PY
)

raw_prefix=https://raw.githubusercontent.com/EladKaufman105/face-blur/
fetched=" "
while read -r pack name size sha url; do
  dest="$out/$name"
  if [ -f "$dest" ] && [ "$(stat -c %s "$dest")" = "$size" ] && echo "$sha  $dest" | sha256sum -c --status; then
    echo "$pack/$name: already here"
    continue
  fi
  case "$url" in
    "$raw_prefix"*)
      rest=${url#"$raw_prefix"}
      commit=${rest%%/*}
      path=${rest#*/}
      if [[ "$fetched" != *" $commit "* ]]; then
        git fetch -q --depth 1 origin "$commit"
        fetched="$fetched$commit "
      fi
      git show "$commit:$path" > "$dest.part"
      ;;
    https://github.com/EladKaufman105/face-blur/releases/download/*)
      curl -sSfL --retry 4 --retry-all-errors --max-time 1800 -o "$dest.part" "$url"
      ;;
    https://storage.googleapis.com/mediapipe-models/*)
      # MediaPipe's versioned model files: the size and SHA-256 below pin them.
      curl -sSfL --retry 4 --retry-all-errors --max-time 600 -o "$dest.part" "$url"
      ;;
    *)
      echo "$pack/$name: not a pinned URL: $url" >&2
      exit 1
      ;;
  esac
  got=$(stat -c %s "$dest.part")
  if [ "$got" != "$size" ] || ! echo "$sha  $dest.part" | sha256sum -c --status; then
    echo "$pack/$name: size or SHA-256 doesn't match the registry ($got bytes)" >&2
    rm -f "$dest.part"
    exit 1
  fi
  mv "$dest.part" "$dest"
  echo "$pack/$name: $size bytes, sha256 ok"
done <<< "$lines"
ls -la "$out"

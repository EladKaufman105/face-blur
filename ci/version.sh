#!/usr/bin/env bash
# Prints the app's next version (no "v") from the git tags; CI never commits it anywhere.
#   beta    develop: X.Y.Z-beta.N, the next minor's next beta
#   release main: X.Y.0 after betas of it (develop merged in), else X.Y.Z+1 (a hotfix)
#   dev     anything else (PRs, local builds)
# Usage: ci/version.sh beta|release|dev [ref]. Needs the tags fetched.
set -euo pipefail

die() { echo "version.sh: $*" >&2; exit 1; }

MODE="${1:-}"
REF="${2:-HEAD}"
case "$MODE" in
  beta|release) ;;
  dev) echo "0.0.0-dev"; exit 0 ;;
  *) die "usage: ci/version.sh beta|release|dev [ref]" ;;
esac
git rev-parse -q --verify "$REF^{commit}" > /dev/null || die "no such commit: $REF"

# The last release: the highest vX.Y.Z tag (legacy build-N / test-N tags don't count).
LAST=$(git tag -l 'v*' | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' | sed 's/^v//' | sort -t. -k1,1n -k2,2n -k3,3n | tail -n 1 || true)
LAST=${LAST:-2.0.0}
IFS=. read -r LMAJ LMIN LPAT <<< "$LAST"
LMAJ=$((10#$LMAJ)); LMIN=$((10#$LMIN)); LPAT=$((10#$LPAT))

# The major asked for in version.properties at the ref.
if PROPS=$(git show "$REF:version.properties" 2> /dev/null); then
  MAJOR=$(printf '%s\n' "$PROPS" | tr -d '\r' | sed -n 's/^[[:space:]]*major[[:space:]]*=[[:space:]]*\([0-9][0-9]*\)[[:space:]]*$/\1/p' | tail -n 1)
  [ -n "$MAJOR" ] || die "version.properties at $REF has no major=<number>"
  MAJOR=$((10#$MAJOR))
else
  MAJOR=$LMAJ
fi

# The line being worked on: a new major if asked for, else the next minor.
if [ "$MAJOR" -gt "$LMAJ" ]; then
  NEXT="$MAJOR.0.0"
else
  [ "$MAJOR" -eq "$LMAJ" ] || echo "version.sh: major=$MAJOR is below the last release $LAST, ignored" >&2
  NEXT="$LMAJ.$((LMIN + 1)).0"
fi
BETA_RE="^v${NEXT//./\\.}-beta\\.[0-9]+\$"

if [ "$MODE" = beta ]; then
  N=$(git tag -l "v$NEXT-beta.*" | grep -E "$BETA_RE" | sed 's/.*-beta\.//' | sort -n | tail -n 1 || true)
  VERSION="$NEXT-beta.$(( 10#${N:-0} + 1 ))"
else
  # A release is already tagged here: a re-run must not make it a hotfix.
  # (v2.0.0 is only the baseline, the same as no tags.)
  DONE=$(git tag -l 'v*' --points-at "$REF" | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' | grep -vx 'v2.0.0' || true)
  [ -z "$DONE" ] || die "$REF is already released as ${DONE//$'\n'/ }"
  BETAS=$(git tag -l "v$NEXT-beta.*" --merged "$REF" | grep -E "$BETA_RE" || true)
  if [ "$MAJOR" -gt "$LMAJ" ] || [ -n "$BETAS" ]; then
    VERSION="$NEXT"
  else
    VERSION="$LMAJ.$LMIN.$((LPAT + 1))"
  fi
fi

if git rev-parse -q --verify "refs/tags/v$VERSION" > /dev/null; then
  die "tag v$VERSION already exists"
fi
echo "version.sh: last release $LAST, major $MAJOR, $MODE at $REF -> $VERSION" >&2
echo "$VERSION"

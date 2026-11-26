#!/usr/bin/env bash
# ccache for the native build (whisper.cpp, LAME) in a CI job. app/build.gradle.kts compiles
# through ccache only when CCACHE_DIR is set, which `setup` does. Optional: when ccache can't be
# installed, the job builds without it.
#   ci/ccache.sh setup   install, set CCACHE_* in $GITHUB_ENV; outputs ok=true|false, week
#   ci/ccache.sh stats   print the hit rate
set -uo pipefail

case "${1:-}" in
  setup)
    if ! command -v ccache > /dev/null; then
      install() { timeout 60 sudo apt-get install -y -qq --no-install-recommends ccache > /dev/null 2>&1; }
      install || { timeout 90 sudo apt-get update -qq > /dev/null 2>&1 && install; }
    fi
    if ! command -v ccache > /dev/null; then
      echo "::warning::ccache could not be installed; the native code is compiled without it."
      echo "ok=false" >> "$GITHUB_OUTPUT"
      exit 0
    fi
    mkdir -p "$HOME/.ccache"
    {
      echo "CCACHE_DIR=$HOME/.ccache"
      # Paths under the workspace are hashed relative to it.
      echo "CCACHE_BASEDIR=$GITHUB_WORKSPACE"
      echo "CCACHE_NOHASHDIR=true"
      echo "CCACHE_MAXSIZE=500M"
    } >> "$GITHUB_ENV"
    CCACHE_DIR="$HOME/.ccache" ccache --zero-stats > /dev/null
    {
      echo "ok=true"
      # The cache key changes weekly, so a cache that stopped hitting (a new runner image) is replaced.
      echo "week=$(date -u +%G-%V)"
    } >> "$GITHUB_OUTPUT"
    ccache --version | head -n 1
    ;;
  stats)
    if [ -n "${CCACHE_DIR:-}" ] && command -v ccache > /dev/null; then ccache --show-stats || true; fi
    ;;
  *) echo "usage: ci/ccache.sh setup|stats" >&2; exit 2 ;;
esac

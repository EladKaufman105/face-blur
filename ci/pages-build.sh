#!/usr/bin/env bash
# Makes sure GitHub Pages publishes gh-pages after ci/publish-pages.sh pushed <commit> to it, and
# waits (up to about 12 minutes) until a build of that commit, or of a newer one on gh-pages, is live.
# A push made with the workflow's token may not start a Pages build by itself, so this asks for one
# unless a build of it is already there.
# While Pages still deploys from GitHub Actions (before the owner switches it to the branch, see
# docs/admin-web-beta.md), or isn't on, this only warns.
# Usage: GH_TOKEN=... GITHUB_REPOSITORY=owner/repo ci/pages-build.sh <commit>
set -euo pipefail
commit=${1:?commit}
repo=${GITHUB_REPOSITORY:?owner/repo}

# Where Pages publishes from. 404: Pages isn't on. Any other failure is an error, not "not yet".
if ! pages=$(gh api "repos/$repo/pages" 2>&1); then
  if grep -q "HTTP 404" <<<"$pages"; then
    echo "::warning::GitHub Pages isn't on for this repository: gh-pages is updated but not served. See docs/admin-web-beta.md."
    exit 0
  fi
  echo "::error::Couldn't read the GitHub Pages settings: $pages"
  exit 1
fi
type=$(jq -r '.build_type // "none"' <<<"$pages")
branch=$(jq -r '.source.branch // ""' <<<"$pages")
path=$(jq -r '.source.path // ""' <<<"$pages")
if [ "$type" != legacy ]; then
  echo "::warning::GitHub Pages doesn't deploy from a branch yet (build type: $type): gh-pages is updated but not served. See docs/admin-web-beta.md."
  exit 0
fi
if [ "$branch" != gh-pages ] || [ "$path" != / ]; then
  echo "::error::GitHub Pages deploys from '$branch' '$path', not 'gh-pages' '/'. Fix it in Settings -> Pages (docs/admin-web-beta.md)."
  exit 1
fi

latest() { gh api "repos/$repo/pages/builds/latest" --jq '[.status, .commit] | @tsv' 2>/dev/null || echo "unknown -"; }
read -r status built < <(latest)
if [ "$built" != "$commit" ]; then
  gh api -X POST "repos/$repo/pages/builds" --silent \
    || echo "::warning::Couldn't request a GitHub Pages build; waiting for one started by the push."
fi

for _ in $(seq 144); do
  sleep 5
  read -r status built < <(latest)
  head=$(git ls-remote origin refs/heads/gh-pages 2>/dev/null | cut -f1) || head=
  # Ours, or a newer gh-pages (a deploy of the other app since) that has ours in it.
  if [ "$built" = "$commit" ] || { [ -n "$head" ] && [ "$built" = "$head" ] && [ "$head" != "$commit" ]; }; then
    case "$status" in
      built) echo "GitHub Pages: built $built"; exit 0 ;;
      errored) echo "::error::GitHub Pages couldn't build $built (see the pages-build-deployment run)"; exit 1 ;;
    esac
  fi
done
echo "::error::GitHub Pages didn't finish building $commit in 12 minutes (last: $status $built)"
exit 1

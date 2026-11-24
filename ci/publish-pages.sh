#!/usr/bin/env bash
# Puts one web app on the gh-pages branch, which GitHub Pages serves: the release at the root
# (everything there except beta/) or the beta under beta/. The other app's part is kept as it is
# (the same git tree, so byte for byte). The branch is always one commit (orphan, force-pushed),
# so it never grows. Only CI writes it (.github/workflows/web.yml).
#
# Usage, inside a clone whose origin may be pushed to: ci/publish-pages.sh <release|beta> <built dist dir> <source, e.g. main@abc1234>
# A deploy of the other app at the same time is safe: the push is refused if gh-pages moved since
# it was read (--force-with-lease), and then this reads it again and tries again.
set -euo pipefail
channel=${1:?release or beta}
dist=$(cd "${2:?dist dir}" && pwd)
source=${3:?source}
case "$channel" in release | beta) ;; *) echo "unknown channel: $channel" >&2; exit 2 ;; esac
[ -f "$dist/index.html" ] || { echo "no build in $dist" >&2; exit 1; }
if [ "$channel" = release ] && [ -e "$dist/beta" ]; then echo "the release build has a beta/ of its own" >&2; exit 1; fi

export GIT_AUTHOR_NAME=${GIT_AUTHOR_NAME:-github-actions[bot]}
export GIT_AUTHOR_EMAIL=${GIT_AUTHOR_EMAIL:-41898282+github-actions[bot]@users.noreply.github.com}
export GIT_COMMITTER_NAME=$GIT_AUTHOR_NAME GIT_COMMITTER_EMAIL=$GIT_AUTHOR_EMAIL
index=$(mktemp)
trap 'rm -f "$index"' EXIT

# The build as a tree (a separate index: the working tree and the checkout's index stay as they are).
rm -f "$index"
# (Not limited by a sparse checkout, as CI's is.)
GIT_INDEX_FILE=$index git -c core.sparseCheckout=false --work-tree="$dist" add -A -f .
part=$(GIT_INDEX_FILE=$index git write-tree)
nojekyll=$(git hash-object -w --stdin < /dev/null)

for attempt in 1 2 3 4 5; do
  # gh-pages as it is now (none yet: only this part goes up).
  old=$(git ls-remote origin refs/heads/gh-pages | cut -f1)
  if [ -n "$old" ]; then git fetch -q --depth 1 origin "+refs/heads/gh-pages:refs/remotes/origin/gh-pages"; fi
  entries() { [ -n "$old" ] && git ls-tree "$old^{tree}" || true; }
  if [ "$channel" = release ]; then
    # The new root, with the beta's beta/ as it was.
    tree=$({ git ls-tree "$part" | grep -v -P '\t\.nojekyll$' || true
             entries | grep -P '\tbeta$' || true
             printf '100644 blob %s\t.nojekyll\n' "$nojekyll"; } | git mktree)
  else
    # The release's root as it was, with the new beta/.
    tree=$({ entries | grep -v -P '\t(beta|\.nojekyll)$' || true
             printf '040000 tree %s\tbeta\n' "$part"
             printf '100644 blob %s\t.nojekyll\n' "$nojekyll"; } | git mktree)
  fi
  commit=$(git commit-tree "$tree" -m "Web: $channel from $source")
  if git push -q --force-with-lease="refs/heads/gh-pages:$old" origin "$commit:refs/heads/gh-pages"; then
    echo "gh-pages: $channel from $source ($commit)"
    if [ -n "${GITHUB_OUTPUT:-}" ]; then echo "commit=$commit" >> "$GITHUB_OUTPUT"; fi
    exit 0
  fi
  echo "gh-pages moved while deploying (attempt $attempt): reading it again" >&2
  sleep $((attempt * 5))
done
echo "could not update gh-pages" >&2
exit 1

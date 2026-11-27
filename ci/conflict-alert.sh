#!/usr/bin/env bash
# Comments on open PRs into develop that conflict with it ("dirty") or are behind it ("behind").
# GitHub computes mergeability lazily, so each PR is asked again until it answers (about 1 minute).
# Usage: GH_TOKEN=... REPO=owner/repo DEVELOP=<sha> ci/conflict-alert.sh
set -euo pipefail
repo=${REPO:?owner/repo}
dev=${DEVELOP:-develop}

for n in $(gh api "repos/$repo/pulls?base=develop&state=open&per_page=100" --paginate --jq '.[].number'); do
  state='' head=''
  for _ in $(seq 12); do
    read -r state head < <(gh api "repos/$repo/pulls/$n" --jq '[.mergeable_state, .head.sha] | @tsv') || true
    [ -n "$state" ] && [ "$state" != unknown ] && break
    sleep 5
  done
  case "$state" in
    dirty) what="conflicts with \`develop\`. Merge \`develop\` into this branch and resolve the conflicts; it can't merge until then." ;;
    behind) what="is behind \`develop\`. Merge \`develop\` into this branch (the checks run again); it can't merge until then." ;;
    *) echo "#$n: $state"; continue ;;
  esac
  marker="<!-- conflict-alert $state $head -->"
  if gh api "repos/$repo/issues/$n/comments?per_page=100" --paginate --jq '.[].body' | grep -qF "$marker"; then
    echo "#$n: $state, already said"
    continue
  fi
  body="$marker
This PR $what

Checked after \`develop\` moved to ${dev:0:7}.

---
_Posted by the Conflict alert workflow_"
  gh api "repos/$repo/issues/$n/comments" -f body="$body" --silent
  echo "#$n: $state, commented"
done

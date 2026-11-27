#!/usr/bin/env bash
# Comments on open PRs into develop that conflict with it or are behind it (develop's ruleset requires
# a PR to be up to date). GitHub computes mergeability lazily, so each PR is asked again until it
# answers (about 1 minute). Skips PRs whose head isn't a branch of this repo that may take develop
# (main, or a fork).
# Usage: GH_TOKEN=... REPO=owner/repo DEVELOP=<sha> ci/conflict-alert.sh
set -euo pipefail
repo=${REPO:?owner/repo}
dev=${DEVELOP:?develop sha}

prs=$(gh api "repos/$repo/pulls?base=develop&state=open&per_page=100" --paginate --jq '.[].number')
for n in $prs; do
  info=''
  for _ in $(seq 12); do
    info=$(gh api "repos/$repo/pulls/$n" --jq '[(.mergeable | tostring), .head.sha, .head.ref, .head.repo.full_name] | @tsv') || info=''
    [ -n "$info" ] && [ "$(cut -f1 <<<"$info")" != null ] && break
    sleep 5
  done
  IFS=$'\t' read -r mergeable head ref from <<<"$info"
  if [ "$from" != "$repo" ] || [ "$ref" = main ]; then
    echo "#$n: head $from:$ref, skipped"
    continue
  fi
  if [ "$mergeable" = false ]; then
    state=conflict
    what="conflicts with \`develop\`. Merge \`develop\` into this branch and resolve the conflicts; it can't merge until then."
  elif [ "$(gh api "repos/$repo/compare/$head...$dev" --jq .ahead_by)" -gt 0 ]; then
    state=behind
    what="is behind \`develop\`. Merge \`develop\` into this branch (the checks run again); it can't merge until then."
  else
    echo "#$n: up to date"
    continue
  fi
  marker="<!-- conflict-alert $state $head -->"
  bodies=$(gh api "repos/$repo/issues/$n/comments?per_page=100" --paginate --jq '.[].body')
  if grep -qF "$marker" <<<"$bodies"; then
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

#!/usr/bin/env bash
# Is a release's or beta's version still this run's to publish? build.yml runs it, so that a run
# that was superseded (a newer run took its version) or re-run publishes nothing and says why,
# and never updates a release another run made.
#
#   ci/release-guard.sh beta <version> [ref]
#       plan, on develop, in a clone with the tags: fails if ref (default HEAD) is already in a beta
#       (its own, or a newer commit's): a re-run of a superseded or published run.
#   ci/release-guard.sh new <tag>
#       publish, before the first try: fails if the tag exists on the remote at all.
#   ci/release-guard.sh retry <tag>
#       publish, before the second try: the tag may exist only on this run's commit ($GITHUB_SHA,
#       the first try made it), never on another.
#
# Exit 0: go on. 1: don't publish (the message says why). 2: an error (can't tell).
# The remote is https://github.com/$GITHUB_REPOSITORY (GH_TOKEN, if set, is sent), or
# $RELEASE_GUARD_REMOTE (tests).
set -euo pipefail

die() { echo "release-guard: $*" >&2; exit 2; }
stop() { echo "::error::$*"; exit 1; }

# remote_sha <tag>: the commit the tag names on the remote, empty if there is none.
remote_sha() {
  local remote=${RELEASE_GUARD_REMOTE:-https://github.com/${GITHUB_REPOSITORY:?}.git} out
  local auth=()
  if [ -n "${GH_TOKEN:-}" ] && [ -z "${RELEASE_GUARD_REMOTE:-}" ]; then
    auth=(-c "http.extraHeader=Authorization: basic $(printf 'x-access-token:%s' "$GH_TOKEN" | base64 -w0)")
  fi
  out=$(git "${auth[@]}" ls-remote --tags "$remote" "refs/tags/$1" "refs/tags/$1^{}") || die "can't read the tags of $remote"
  # An annotated tag's commit is on its ^{} line.
  printf '%s\n' "$out" | awk -v t="refs/tags/$1" '$2 == t "^{}" { c = $1 } $2 == t && c == "" { c = $1 } END { print c }'
}

case "${1:-}" in
  beta)
    version=${2:?version}
    ref=${3:-HEAD}
    sha=$(git rev-parse -q --verify "$ref^{commit}") || die "no such commit: $ref"
    [[ "$version" =~ -beta\.[0-9]+$ ]] || die "not a beta version: $version"
    # Any line's beta: a commit an older line's beta has is an old run's too.
    have=$(git tag -l 'v*-beta.*' --contains "$sha" | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+-beta\.[0-9]+$' | sort -t. -k1,1n -k2,2n -k3,3n -k4,4n || true)
    if [ -n "$have" ]; then
      first=$(printf '%s\n' "$have" | head -n 1)
      stop "Superseded: ${sha:0:7} is already in beta $first ($(git rev-parse --short "$first^{commit}")). Nothing to publish: a re-run makes no second beta of the same change. The newest beta has it."
    fi
    echo "release-guard: ${sha:0:7} is in no beta yet"
    ;;
  new)
    tag=${2:?tag}
    sha=$(remote_sha "$tag")
    if [ -n "$sha" ] && [ "$sha" = "${GITHUB_SHA:-}" ]; then
      stop "$tag exists already on this commit: an earlier attempt of this run made it. Nothing more is published. If its APK is missing, the owner adds it by hand; re-running can't."
    fi
    [ -z "$sha" ] || stop "Superseded: $tag exists already (on ${sha:0:7}): another run took this version after this run planned it. Nothing was published. Don't re-run this run; the newer run's release has its changes."
    echo "release-guard: $tag is free"
    ;;
  retry)
    tag=${2:?tag}
    sha=$(remote_sha "$tag")
    if [ -n "$sha" ] && [ "$sha" != "${GITHUB_SHA:?}" ]; then
      stop "$tag was made on ${sha:0:7} by another run while this one waited: nothing more is published here."
    fi
    if [ -n "$sha" ]; then echo "release-guard: $tag is on this run's commit (the first try made it)"
    else echo "release-guard: $tag is still free"; fi
    ;;
  *) die "usage: ci/release-guard.sh beta <version> [ref] | new <tag> | retry <tag>" ;;
esac

#!/usr/bin/env bash
# Is a release the beta that was tested? Run from the repository root, with history and tags.
#
#   ci/promote-check.sh [ref]          Git only (the release check, and the release PR's
#                                      android-build). The newest beta of the release version
#                                      merged into ref (default origin/develop), and the files
#                                      outside EXCLUDES that changed since. Exit 0: none, the tree
#                                      is the beta's. 1: files changed (listed) or no beta. 2: error.
#   ci/promote-check.sh plan [ref]     build.yml on main. Prints key=value lines for $GITHUB_OUTPUT:
#                                      version, and mode=promote with rc_run, rc_sha256,
#                                      rc_version_code and beta when the beta's release candidate
#                                      (RC) can be released, else mode=full (today's full e2e).
#                                      Exits non-zero only when ci/version.sh fails ("already
#                                      released"). Every other miss is mode=full.
#   ci/promote-check.sh web-tested [ref]
#                                      pr.yml on a PR into main. Prints the commit of a successful
#                                      web.yml push run on develop that ran the web tests on the
#                                      same web files as ref, exit 0; exit 1 if there is none.
#   ci/promote-check.sh excludes       Prints EXCLUDES.
#   ci/promote-check.sh lint           Fails if EXCLUDES and build.yml's paths-ignore differ.
#
# GitHub API calls use `gh api` (GH_TOKEN, GITHUB_REPOSITORY). Tests set PROMOTE_FAKE_API to a
# directory of JSON files instead: the path with '/', '?', '&' and '=' made '_', plus .json.
set -euo pipefail

# The paths that don't go into the APK: build.yml's paths-ignore, exactly (the lint step in pr.yml
# checks it). A change only here makes no beta, and a release may differ from its beta only here.
EXCLUDES=(
  '.github/workflows/models.yml'
  '.github/workflows/web.yml'
  '.github/workflows/web-tests.yml'
  '.github/actions/**'
  'ci/web-tests.sh'
  'ci/web_i18n_check.py'
  'ci/publish-pages.sh'
  'ci/pages-build.sh'
  'web/**'
  'docs/**'
  '.claude/**'
  '.github/pull_request_template.md'
  '.github/workflows/parity-hint.yml'
)

HERE="$(cd "$(dirname "$0")" && pwd)"
# The repository it runs in (tests run it in throwaway repositories).
ROOT="$(git rev-parse --show-toplevel)"
REPO="${GITHUB_REPOSITORY:-EladKaufman105/face-blur}"

log() { echo "promote-check: $*" >&2; }

# api <path>: the JSON of a GitHub REST API GET.
api() {
  if [ -n "${PROMOTE_FAKE_API:-}" ]; then
    local f
    f="$PROMOTE_FAKE_API/$(printf '%s' "$1" | tr '/?&=' '____').json"
    [ -f "$f" ] && cat "$f"
  else
    gh api -H 'X-GitHub-Api-Version: 2022-11-28' "$1"
  fi
}

# json <python expression over d> : evaluates against the JSON on stdin, prints the result.
json() { python3 -I -c 'import json,sys; d=json.load(sys.stdin); r=eval(sys.argv[1]); print("" if r is None else r)' "$1"; }

pathspecs() { local p; for p in "${EXCLUDES[@]}"; do printf ':(exclude,glob)%s\n' "$p"; done; }

# yaml_list <file> <python expr over the parsed file>: one item per line. PyYAML reads "on" as True.
yaml_list() {
  if python3 -I -c 'import yaml' 2> /dev/null; then
    python3 -I -c 'import sys,yaml; d=yaml.safe_load(open(sys.argv[1])); on=d.get("on", d.get(True)); print("\n".join(eval(sys.argv[2])))' "$1" "$2"
  elif command -v yq > /dev/null; then
    case "$2" in
      *paths-ignore*) yq -r '(.on // .["true"]).push["paths-ignore"][]' "$1" ;;
      *) yq -r '(.on // .["true"]).push.paths[]' "$1" ;;
    esac
  else
    # The block list under the first "<key>:" line, quoted or not.
    local key
    case "$2" in *paths-ignore*) key='paths-ignore' ;; *) key=paths ;; esac
    awk -v k="$key" '
      !inlist && $0 ~ "^[[:space:]]*" k ":[[:space:]]*$" { inlist = 1; next }
      inlist && /^[[:space:]]*#/ { next }
      inlist && /^[[:space:]]*- / { sub(/^[[:space:]]*- /, ""); sub(/[[:space:]]+#.*$/, ""); gsub(/^['\''"]|['\''"]$/, ""); print; next }
      inlist { exit }' "$1"
  fi
}

# beta_of <ref> <version>: the newest vVERSION-beta.N tag merged into ref.
beta_of() {
  git tag -l "v$2-beta.*" --merged "$1" | grep -E "^v${2//./\\.}-beta\.[0-9]+\$" | sort -t. -k4,4n | tail -n 1 || true
}

# changed <beta> <ref>: files that differ outside EXCLUDES.
changed() {
  local specs
  mapfile -t specs < <(pathspecs)
  git -C "$ROOT" diff --no-renames --name-only "$1" "$2" -- . "${specs[@]}"
}

# tree <ref>: prints version, beta; exit 0 when ref's tree equals the beta's outside EXCLUDES.
tree() {
  local ref=$1 version beta files
  git rev-parse -q --verify "$ref^{commit}" > /dev/null || { log "no such commit: $ref"; return 2; }
  version=$(bash "$HERE/version.sh" release "$ref") || return 2
  echo "version=$version"
  beta=$(beta_of "$ref" "$version")
  if [ -z "$beta" ]; then log "no beta of $version is merged into $ref (a hotfix?)"; return 1; fi
  echo "beta=$beta"
  files=$(changed "$beta" "$ref") || return 2
  if [ -n "$files" ]; then
    log "changed since $beta (outside EXCLUDES):"
    printf '%s\n' "$files"
    return 1
  fi
  log "$ref is $beta's tree (outside EXCLUDES)"
}

# plan <ref>: see the header. Prints key=value lines; full on any miss.
plan() {
  local ref=$1 out version beta rc_run rc_sha body run jobs arts last_code tag_sha
  full() { log "full e2e: $*"; echo "mode=full"; echo "reason=$*"; exit 0; }
  version=$(bash "$HERE/version.sh" release "$ref") || exit 2
  echo "version=$version"
  [ "${FORCE_FULL:-false}" != true ] || full "asked for (force_full)"
  out=$(tree "$ref" 2>&1) || full "the tree isn't a beta's: $(printf '%s' "$out" | tr '\n' ' ')"
  beta=$(beta_of "$ref" "$version")
  tag_sha=$(git rev-parse "$beta^{commit}")

  # The beta's release names the run that built its RC (written by build.yml's publish job).
  body=$(api "repos/$REPO/releases/tags/$beta" | json 'd["body"] if d.get("prerelease") and not d.get("draft") else ""' 2> /dev/null) || body=""
  rc_run=$(printf '%s\n' "$body" | tr -d '\r' | sed -n 's/^rc-run: \([0-9][0-9]*\)$/\1/p')
  rc_sha=$(printf '%s\n' "$body" | tr -d '\r' | sed -n 's/^rc-sha256: \([0-9a-f]\{64\}\)$/\1/p')
  # Exactly one of each.
  [ "$(printf '%s' "$rc_run" | wc -l)" = 0 ] && [ "$(printf '%s' "$rc_sha" | wc -l)" = 0 ] || full "$beta's release names more than one RC"
  [ -n "$rc_run" ] && [ -n "$rc_sha" ] || full "$beta's release has no rc-run / rc-sha256"

  # That run: build.yml, a push to develop at the tag's commit, everything passed. This also
  # rejects a tag made by hand.
  run=$(api "repos/$REPO/actions/runs/$rc_run" | json '"|".join(str(x) for x in [d["path"], d["event"], d["head_branch"], d["head_sha"], d["status"], d["conclusion"], d["head_repository"]["full_name"], d["run_number"]])' 2> /dev/null) || run=""
  IFS='|' read -r r_path r_event r_branch r_sha r_status r_concl r_repo r_number <<< "$run"
  [ "${r_path:-}" = .github/workflows/build.yml ] || full "run $rc_run isn't build.yml (${r_path:-no run})"
  [ "$r_event" = push ] && [ "$r_branch" = develop ] || full "run $rc_run is $r_event on $r_branch, not a push to develop"
  [ "$r_repo" = "$REPO" ] || full "run $rc_run is from $r_repo"
  [ "$r_sha" = "$tag_sha" ] || full "run $rc_run built $r_sha, $beta is $tag_sha"
  [ "$r_status/$r_concl" = completed/success ] || full "run $rc_run is $r_status/$r_concl"
  # Its publish job and every e2e shard passed (the latest attempt).
  jobs=$(api "repos/$REPO/actions/runs/$rc_run/jobs?per_page=100" | json '"%d %d %d" % (sum(1 for j in d["jobs"] if j["name"] == "publish" and j["conclusion"] == "success"), sum(1 for j in d["jobs"] if j["name"].startswith("e2e") and j["conclusion"] == "success"), sum(1 for j in d["jobs"] if j["name"].startswith("e2e") and j["conclusion"] != "success"))' 2> /dev/null) || jobs=""
  read -r j_pub j_ok j_bad <<< "${jobs:-0 0 1}"
  [ "$j_pub" = 1 ] && [ "$j_ok" -ge 1 ] && [ "$j_bad" = 0 ] || full "run $rc_run: publish/e2e jobs not all passed ($jobs)"
  # The RC itself, not expired (kept 90 days).
  arts=$(api "repos/$REPO/actions/runs/$rc_run/artifacts?name=release-candidate" | json 'sum(1 for a in d["artifacts"] if a["name"] == "release-candidate" and not a["expired"])' 2> /dev/null) || arts=0
  [ "$arts" = 1 ] || full "run $rc_run has no release-candidate artifact (expired after 90 days?)"
  # Android installs an update only with a higher versionCode: the RC's is its run's number.
  # Its body starts "ONIT <version>, build <versionCode>" (legacy releases too: "ONIT 2.0, build 128.").
  last_code=$(api "repos/$REPO/releases/latest" | json '(d["body"] or "").splitlines()[0]' 2> /dev/null | tr -d '\r' | sed -n 's/^ONIT [^,]*, build \([0-9][0-9]*\).*/\1/p') || last_code=""
  [ -n "$last_code" ] || full "can't read the last release's versionCode"
  [ "$r_number" -gt "$last_code" ] || full "the RC's versionCode $r_number isn't above the last release's $last_code"

  echo "mode=promote"
  echo "beta=$beta"
  echo "rc_run=$rc_run"
  echo "rc_sha256=$rc_sha"
  echo "rc_version_code=$r_number"
  log "promote $beta's release candidate (run $rc_run, versionCode $r_number) as $version"
}

# web_tested <ref>: see the header.
web_tested() {
  local ref=$1 paths specs runs id sha jobs ok bad p
  git rev-parse -q --verify "$ref^{commit}" > /dev/null || { log "no such commit: $ref"; return 1; }
  mapfile -t paths < <(yaml_list "$ROOT/.github/workflows/web.yml" 'on["push"]["paths"]')
  [ "${#paths[@]}" -gt 0 ] || { log "no paths in web.yml"; return 1; }
  specs=()
  for p in "${paths[@]}"; do specs+=(":(glob)$p"); done
  # The newest successful web.yml pushes to develop, newest first: "<run id> <commit>".
  runs=$(api "repos/$REPO/actions/workflows/web.yml/runs?branch=develop&event=push&status=success&per_page=30" |
    json '"\n".join("%d %s" % (r["id"], r["head_sha"]) for r in d["workflow_runs"] if r["path"] == ".github/workflows/web.yml" and r["event"] == "push" and r["head_branch"] == "develop" and r["conclusion"] == "success")' 2> /dev/null) || runs=""
  while read -r id sha; do
    [ -n "$sha" ] || continue
    git cat-file -e "$sha^{commit}" 2> /dev/null || continue
    git -C "$ROOT" diff --quiet --no-renames "$sha" "$ref" -- "${specs[@]}" || continue
    # Older runs deployed without tests: the shards must have run, and all passed.
    jobs=$(api "repos/$REPO/actions/runs/$id/jobs?per_page=100" |
      json '"%d %d" % (sum(1 for j in d["jobs"] if "test (shard" in j["name"] and j["conclusion"] == "success"), sum(1 for j in d["jobs"] if "test (shard" in j["name"] and j["conclusion"] != "success"))' 2> /dev/null) || jobs="0 1"
    read -r ok bad <<< "$jobs"
    if [ "$ok" -ge 1 ] && [ "$bad" = 0 ]; then
      log "web.yml tested the same web files at $sha"
      echo "$sha"
      return 0
    fi
  done <<< "$runs"
  log "no successful web.yml test run on develop with the same web files as $ref"
  return 1
}

lint() {
  local want have
  want=$(printf '%s\n' "${EXCLUDES[@]}" | sort)
  have=$(yaml_list "$ROOT/.github/workflows/build.yml" 'on["push"]["paths-ignore"]' | sort) || return 2
  if [ "$want" != "$have" ]; then
    echo "::error::build.yml's paths-ignore and EXCLUDES in ci/promote-check.sh differ; keep them the same."
    diff <(echo "$want") <(echo "$have") || true
    return 1
  fi
  echo "paths-ignore == EXCLUDES (${#EXCLUDES[@]} paths)"
}

cd "$ROOT"
case "${1:-}" in
  excludes) printf '%s\n' "${EXCLUDES[@]}" ;;
  lint) lint ;;
  plan) plan "${2:-HEAD}" ;;
  web-tested) web_tested "${2:-HEAD}" ;;
  -h|--help) sed -n '2,/^set -euo/p' "$0" | sed '$d' ;;
  *) tree "${1:-origin/develop}" ;;
esac

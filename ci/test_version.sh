#!/usr/bin/env bash
# Tests ci/version.sh on throwaway git repos: the branch flow from no tags to a major bump. Then
# the release candidate (RC) rules: a release's version is its beta's without -beta.N
# (build.yml's rc build relies on it), ci/promote-check.sh (with a fake GitHub API) and
# ci/verify-rc.sh (with fake aapt2 / apksigner).
set -euo pipefail

CI="$(cd "$(dirname "$0")" && pwd)"
SCRIPT="$CI/version.sh"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
FAILS=0

export GIT_AUTHOR_NAME=t GIT_AUTHOR_EMAIL=t@t GIT_COMMITTER_NAME=t GIT_COMMITTER_EMAIL=t@t
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1

# expect <what> <wanted> <mode> [ref]
expect() {
  local what=$1 want=$2 got
  shift 2
  got=$(bash "$SCRIPT" "$@" 2> /dev/null) || got="(exit $?)"
  if [ "$got" = "$want" ]; then echo "ok   $what: $* -> $got"
  else echo "FAIL $what: $* -> $got, wanted $want"; FAILS=$((FAILS + 1)); fi
}

# expect_fail <what> <mode> [ref]
expect_fail() {
  local what=$1 got
  shift
  if got=$(bash "$SCRIPT" "$@" 2> "$TMP/err"); then
    echo "FAIL $what: $* -> $got, wanted an error"; FAILS=$((FAILS + 1))
  else echo "ok   $what: $* -> error: $(tail -n 1 "$TMP/err")"; fi
}

commit() { git commit -q --allow-empty -m "$1"; }

# The whole flow, with or without the admin's baseline tag v2.0.0 on the old main.
flow() {
  local baseline=$1 dir="$TMP/repo$1"
  git init -q -b main "$dir"
  cd "$dir"
  commit "old main"
  expect "no tags, no version.properties" 2.1.0-beta.1 beta
  expect "no tags, no version.properties" 2.0.1 release
  printf '# comment\nmajor=2\n' > version.properties
  git add version.properties
  commit "version.properties"
  expect "no tags" 2.1.0-beta.1 beta
  expect "no tags" 2.0.1 release
  expect "no tags" 0.0.0-dev dev
  git tag build-126; git tag test-127; git tag models-1
  expect "legacy tags" 2.1.0-beta.1 beta
  expect "legacy tags" 2.0.1 release
  if [ "$baseline" = 1 ]; then
    git tag v2.0.0
    expect "baseline v2.0.0" 2.1.0-beta.1 beta
    expect "baseline v2.0.0" 2.0.1 release
  fi

  # develop: two features merged, a beta each.
  git checkout -q -b develop
  commit "feature 1"; git tag v2.1.0-beta.1
  commit "feature 2"; git tag v2.1.0-beta.2
  expect "develop after beta.2" 2.1.0-beta.3 beta develop
  # A feature branch's own commits don't matter until merged.
  git checkout -q -b feature/x
  commit "work"
  expect "dev on a feature" 0.0.0-dev dev feature/x

  # Release: develop merged into main.
  git checkout -q main
  git merge -q --no-ff -m "Merge develop" develop
  expect "develop merged into main" 2.1.0 release main
  expect "next beta before v2.1.0 exists" 2.1.0-beta.3 beta develop
  git tag v2.1.0
  expect_fail "re-run of a tagged release" release main
  expect "after v2.1.0" 2.2.0-beta.1 beta develop
  git checkout -q develop
  git merge -q --no-ff -m "Merge main" main
  commit "feature 3"; git tag v2.2.0-beta.1

  # Hotfix on main, a 2.2.0 beta on develop it can't reach.
  git checkout -q main
  git checkout -q -b hotfix/crash
  commit "fix"
  git checkout -q main
  git merge -q --no-ff -m "Merge hotfix/crash" hotfix/crash
  expect "hotfix after v2.1.0" 2.1.1 release main
  git tag v2.1.1
  expect "after v2.1.1" 2.2.0-beta.2 beta develop
  git checkout -q develop
  git merge -q --no-ff -m "Merge main" main
  expect "develop with the hotfix" 2.2.0-beta.2 beta develop

  # A major bump asked for in version.properties.
  printf 'major=3\n' > version.properties
  git commit -q -am "major 3"
  expect "major=3" 3.0.0-beta.1 beta develop
  expect "major=3" 3.0.0 release develop
  # main still says 2: its hotfixes stay on 2.1.
  git checkout -q main
  commit "another fix"
  expect "main with major=2" 2.1.2 release main
  cd "$TMP"
}

flow 0
flow 1

# A tag made meanwhile by another run (hidden from the listing, seen by the final check).
cd "$TMP/repo0"
mkdir -p "$TMP/bin"
REAL_GIT=$(command -v git)
cat > "$TMP/bin/git" <<EOF
#!/usr/bin/env bash
if [ "\$1" = tag ]; then "$REAL_GIT" "\$@" | grep -vx 'v3.0.0-beta.1'; exit 0; fi
exec "$REAL_GIT" "\$@"
EOF
chmod +x "$TMP/bin/git"
git tag v3.0.0-beta.1 develop
PATH="$TMP/bin:$PATH" expect_fail "existing tag" beta develop
expect_fail "bad mode" nightly
expect_fail "bad ref" beta no-such-branch
cd "$TMP"

ok() { echo "ok   $*"; }
bad() { echo "FAIL $*"; FAILS=$((FAILS + 1)); }

# --- The RC's version: build.yml builds it on develop as the beta's version without -beta.N, and
# main promotes it only if that is what ci/version.sh gives the release.
# tag_beta <ref>: tags the beta build.yml would publish at ref.
tag_beta() { BETA_V=$(bash "$SCRIPT" beta "$1" 2> /dev/null); git tag "v$BETA_V" "$1"; }
# expect_rc <what> <release ref>
expect_rc() { expect "$1: the RC's version" "${BETA_V%-beta.*}" release "$2"; }

git init -q -b main "$TMP/rc"
cd "$TMP/rc"
printf 'major=2\n' > version.properties
git add version.properties
commit "old main"
git checkout -q -b develop
commit "feature 1"; tag_beta develop
commit "feature 2"; tag_beta develop
git checkout -q main
git merge -q --no-ff -m "Merge develop" develop
expect_rc "first release" main
git tag v2.1.0
git checkout -q develop
git merge -q --no-ff -m "Merge main" main
commit "feature 3"; tag_beta develop
# A hotfix released after that beta was built (the last release moves to 2.1.1)...
git checkout -q main
commit "fix"
expect "hotfix" 2.1.1 release main
git tag v2.1.1
# ...and the release of that beta, without the merge-back (promote-check then sees the tree differ)
git checkout -q -b rel1 main
git merge -q --no-ff -m "Merge develop" develop
expect_rc "release, the hotfix not merged back" rel1
# ...and with it (a new beta).
git checkout -q develop
git merge -q --no-ff -m "Merge main" main
tag_beta develop
git checkout -q -b rel2 main
git merge -q --no-ff -m "Merge develop" develop
expect_rc "release after the merge-back" rel2
# A major bump.
git checkout -q develop
printf 'major=3\n' > version.properties
git commit -q -am "major 3"
tag_beta develop
git checkout -q -b rel3 main
git merge -q --no-ff -m "Merge develop" develop
expect_rc "major release" rel3
cd "$TMP"

# --- ci/promote-check.sh, with a fake GitHub API.
PC="$CI/promote-check.sh"
API="$TMP/api"
SHA_RC=$(printf 'rc' | sha256sum | cut -d' ' -f1)
fake() { mkdir -p "$API"; printf '%s\n' "$2" > "$API/$(printf '%s' "$1" | tr '/?&=' '____').json"; }
good_api() {
  rm -rf "$API"
  fake repos/o/r/releases/tags/v2.1.0-beta.2 "{\"prerelease\": true, \"draft\": false, \"body\": \"Beta 2.1.0-beta.2 from develop, build 200.\\r\\n\\r\\nrc-run: 555\\r\\nrc-sha256: $SHA_RC\"}"
  fake repos/o/r/actions/runs/555 "{\"path\": \".github/workflows/build.yml\", \"event\": \"push\", \"head_branch\": \"develop\", \"head_sha\": \"$BETA_SHA\", \"status\": \"completed\", \"conclusion\": \"success\", \"head_repository\": {\"full_name\": \"o/r\"}, \"run_number\": 200}"
  fake 'repos/o/r/actions/runs/555/jobs?per_page=100' '{"jobs": [{"name": "plan", "conclusion": "success"}, {"name": "e2e (shard 1)", "conclusion": "success"}, {"name": "e2e (shard 2)", "conclusion": "success"}, {"name": "publish", "conclusion": "success"}]}'
  fake 'repos/o/r/actions/runs/555/artifacts?name=release-candidate' '{"artifacts": [{"name": "release-candidate", "expired": false}]}'
  fake repos/o/r/releases/latest '{"body": "ONIT 2.0, build 128. APK for arm64 (vivo X300 Pro).\nFix the build 999 times"}'
}
# plan_expect <what> <wanted mode> [ref]: and, for promote, the outputs.
plan_expect() {
  local what=$1 want=$2 ref=${3:-rel} out mode
  out=$(PROMOTE_FAKE_API="$API" GITHUB_REPOSITORY=o/r bash "$PC" plan "$ref" 2> "$TMP/err") || { bad "$what: plan failed: $(tail -n 1 "$TMP/err")"; return; }
  mode=$(printf '%s\n' "$out" | sed -n 's/^mode=//p')
  if [ "$mode" != "$want" ]; then bad "$what: mode=$mode, wanted $want ($(tail -n 1 "$TMP/err"))"; return; fi
  if [ "$want" = promote ]; then
    for kv in version=2.1.0 beta=v2.1.0-beta.2 rc_run=555 "rc_sha256=$SHA_RC" rc_version_code=200; do
      printf '%s\n' "$out" | grep -qx "$kv" || { bad "$what: no $kv in: $out"; return; }
    done
  fi
  ok "$what: mode=$mode$([ "$want" = full ] && echo " ($(printf '%s\n' "$out" | sed -n 's/^reason=//p'))")"
}
# release_of <branch>: a release branch from main with that branch merged in.
release_of() { git checkout -q -B rel main; git merge -q --no-ff -m "Merge $1" "$1"; }

git init -q -b main "$TMP/pc"
cd "$TMP/pc"
printf 'major=2\n' > version.properties
mkdir -p .github/workflows app web docs ci
cp "$CI/../.github/workflows/web.yml" "$CI/../.github/workflows/build.yml" .github/workflows/
echo 1 > app/a.txt; echo 1 > web/w.txt
git add -A; commit "old main"
git checkout -q -b develop
commit "feature 1"; tag_beta develop
echo 2 > app/a.txt; git commit -q -am "feature 2"; tag_beta develop
BETA_SHA=$(git rev-parse HEAD)
expect_branch() { git checkout -q -b "$1" develop; mkdir -p "$(dirname "$2")"; echo x >> "$2"; git add -A; commit "$1"; git checkout -q develop; }
expect_branch dev-web web/w.txt
expect_branch dev-docs docs/a.md
expect_branch dev-wi18n ci/web_i18n_check.py
expect_branch dev-app app/a.txt
expect_branch dev-buildyml .github/workflows/build.yml
expect_branch dev-props version.properties
good_api

release_of develop; plan_expect "the beta's own tree" promote
release_of dev-web; plan_expect "a web change after the beta" promote
release_of dev-docs; plan_expect "a docs change after the beta" promote
release_of dev-wi18n; plan_expect "ci/web_i18n_check.py after the beta" promote
release_of dev-app; plan_expect "an app change after the beta" full
release_of dev-buildyml; plan_expect "a build.yml change after the beta" full
release_of dev-props; plan_expect "a version.properties change after the beta" full
release_of develop
FORCE_FULL=true plan_expect "force_full" full
rm -rf "$API"; plan_expect "no API answers" full
good_api; fake repos/o/r/releases/tags/v2.1.0-beta.2 '{"prerelease": true, "draft": false, "body": "Beta 2.1.0-beta.2, an old one"}'
plan_expect "no rc-run in the beta's release" full
good_api; fake repos/o/r/releases/tags/v2.1.0-beta.2 "{\"prerelease\": false, \"draft\": false, \"body\": \"rc-run: 555\\nrc-sha256: $SHA_RC\"}"
plan_expect "the beta's release isn't a pre-release" full
good_api; fake repos/o/r/releases/tags/v2.1.0-beta.2 "{\"prerelease\": true, \"draft\": false, \"body\": \"rc-run: 555\\nrc-run: 556\\nrc-sha256: $SHA_RC\"}"
plan_expect "two rc-run lines" full
# "<from>|<to>" in the run's JSON.
for change in '"event": "push"|"event": "workflow_dispatch"' '"head_branch": "develop"|"head_branch": "main"' \
  "\"head_sha\": \"$BETA_SHA\"|\"head_sha\": \"0000000000000000000000000000000000000000\"" \
  '"path": ".github/workflows/build.yml"|"path": ".github/workflows/web.yml"' '"conclusion": "success"|"conclusion": "failure"' \
  '"status": "completed"|"status": "in_progress"' '"full_name": "o/r"|"full_name": "fork/r"'; do
  good_api
  sed -i "s|$change|" "$API/repos_o_r_actions_runs_555.json"
  plan_expect "the RC's run: ${change##*|}" full
done
good_api; fake 'repos/o/r/actions/runs/555/jobs?per_page=100' '{"jobs": [{"name": "e2e (shard 1)", "conclusion": "success"}, {"name": "e2e (shard 2)", "conclusion": "failure"}, {"name": "publish", "conclusion": "success"}]}'
plan_expect "an e2e shard failed" full
good_api; fake 'repos/o/r/actions/runs/555/jobs?per_page=100' '{"jobs": [{"name": "e2e (shard 1)", "conclusion": "success"}, {"name": "publish", "conclusion": "skipped"}]}'
plan_expect "publish skipped" full
good_api; fake 'repos/o/r/actions/runs/555/artifacts?name=release-candidate' '{"artifacts": [{"name": "release-candidate", "expired": true}]}'
plan_expect "the RC expired" full
good_api; fake 'repos/o/r/actions/runs/555/artifacts?name=release-candidate' '{"artifacts": []}'
plan_expect "no RC artifact" full
good_api; fake repos/o/r/releases/latest '{"body": "ONIT 2.0.9, build 200 (the Android versionCode)."}'
plan_expect "versionCode not above the last release's" full
good_api; fake repos/o/r/releases/latest '{"body": "Something else"}'
plan_expect "the last release's versionCode unreadable" full
good_api
# Released: a re-run fails ("already released"), it doesn't fall back.
git tag v2.1.0 rel
if PROMOTE_FAKE_API="$API" GITHUB_REPOSITORY=o/r bash "$PC" plan rel > /dev/null 2>&1; then bad "re-run of a released commit: no error"
else ok "re-run of a released commit: error"; fi
# A hotfix has no beta of its version: the full path.
git checkout -q -B hot rel; commit "fix"
plan_expect "hotfix" full hot
git tag -d v2.1.0 > /dev/null

# The git-only check (CLAUDE.md's release check, the release PR's android-build).
if out=$(bash "$PC" dev-web 2> /dev/null); then ok "tree check, web change: same tree"; else bad "tree check, web change: $out"; fi
if out=$(bash "$PC" dev-app 2> /dev/null); then bad "tree check, app change: same tree"
elif printf '%s\n' "$out" | grep -qx app/a.txt; then ok "tree check, app change: lists app/a.txt"; else bad "tree check, app change: $out"; fi
if bash "$PC" no-such-ref > /dev/null 2>&1; then bad "tree check, bad ref: no error"; else ok "tree check, bad ref: error"; fi

# web-tested: a successful web.yml push run on develop that ran the shards, on the same web files.
git checkout -q -b dev-w1 develop; echo 3 > web/w.txt; git commit -q -am "web 1"; W1=$(git rev-parse HEAD)
git checkout -q -b dev-w1-app dev-w1; echo 3 > app/a.txt; git commit -q -am "app after web 1"
git checkout -q -b dev-w2 dev-w1; echo 4 > web/w.txt; git commit -q -am "web 2"
web_api() {
  fake 'repos/o/r/actions/workflows/web.yml/runs?branch=develop&event=push&status=success&per_page=30' "{\"workflow_runs\": [{\"id\": 901, \"head_sha\": \"$W1\", \"path\": \".github/workflows/web.yml\", \"event\": \"push\", \"head_branch\": \"develop\", \"conclusion\": \"success\"}]}"
  fake 'repos/o/r/actions/runs/901/jobs?per_page=100' "{\"jobs\": [{\"name\": \"web / build\", \"conclusion\": \"success\"}, {\"name\": \"web / test (shard $1)\", \"conclusion\": \"$2\"}, {\"name\": \"deploy (beta)\", \"conclusion\": \"success\"}]}"
}
web_expect() {
  local what=$1 want=$2 ref=$3 got
  got=$(PROMOTE_FAKE_API="$API" GITHUB_REPOSITORY=o/r bash "$PC" web-tested "$ref" 2> /dev/null) || got=none
  if [ "$got" = "$want" ]; then ok "web-tested, $what: $got"; else bad "web-tested, $what: $got, wanted $want"; fi
}
web_api 1 success
release_of dev-w1; web_expect "the tested commit" "$W1" rel
release_of dev-w1-app; web_expect "an app change after it" "$W1" rel
release_of dev-w2; web_expect "a web change after it" none rel
web_api 1 failure
release_of dev-w1; web_expect "a shard failed" none rel
fake 'repos/o/r/actions/runs/901/jobs?per_page=100' '{"jobs": [{"name": "deploy (beta)", "conclusion": "success"}]}'
web_expect "deployed without tests" none rel
rm -rf "$API"; web_expect "no API answers" none rel

# lint: build.yml's paths-ignore == EXCLUDES.
if (cd "$CI/.." && bash "$PC" lint > /dev/null); then ok "lint: the repository's lists match"; else bad "lint: the repository's lists differ"; fi
sed -i "/^      - 'docs\/\*\*'$/d" .github/workflows/build.yml
if bash "$PC" lint > /dev/null 2>&1; then bad "lint: a path missing from paths-ignore passed"; else ok "lint: a path missing from paths-ignore fails"; fi
git checkout -q .github/workflows/build.yml
cd "$TMP"

# --- ci/verify-rc.sh, with fake aapt2 and apksigner.
VR="$CI/verify-rc.sh"
CERT=d8a7cc0dab1725c83d419e49e064e6aedca969c7896524ffc0d07625d0dc83fd
printf 'rc' > "$TMP/rc.apk"
cat > "$TMP/bin/aapt2" << 'EOF'
#!/usr/bin/env bash
printf "package: name='%s' versionCode='%s' versionName='%s' platformBuildVersionName='16'\nsdkVersion:'29'\nnative-code: %s\n" "${F_PKG:-com.elad.faceblur}" "${F_CODE:-200}" "${F_NAME:-2.1.0}" "${F_NATIVE:-'arm64-v8a'}"
EOF
cat > "$TMP/bin/apksigner" << 'EOF'
#!/usr/bin/env bash
[ "${F_SIGN_FAIL:-}" = 1 ] && { echo "DOES NOT VERIFY"; exit 1; }
for d in ${F_CERTS:?}; do echo "Signer #1 certificate DN: CN=FaceBlur, O=Elad"; echo "Signer #1 certificate SHA-256 digest: $d"; done
EOF
chmod +x "$TMP/bin/aapt2" "$TMP/bin/apksigner"
export AAPT2="$TMP/bin/aapt2" APKSIGNER="$TMP/bin/apksigner"
# verify_expect <what> <0|1> [VAR=value ...]
verify_expect() {
  local what=$1 want=$2 got=0
  shift 2
  env F_CERTS="$CERT" "$@" bash "$VR" "$TMP/rc.apk" 2.1.0 200 "$SHA_RC" > /dev/null 2> "$TMP/err" || got=1
  if [ "$got" = "$want" ]; then ok "verify-rc, $what: exit $got $(cat "$TMP/err")"; else bad "verify-rc, $what: exit $got, wanted $want"; fi
}
verify_expect "the RC" 0
verify_expect "the beta's app id" 1 F_PKG=com.elad.faceblur.labs
verify_expect "another versionName" 1 F_NAME=2.1.0-beta.2
verify_expect "another versionCode" 1 F_CODE=199
verify_expect "an x86_64 build" 1 "F_NATIVE='arm64-v8a' 'x86_64'"
verify_expect "another signer" 1 F_CERTS=0000000000000000000000000000000000000000000000000000000000000000
verify_expect "two signers" 1 "F_CERTS=$CERT $CERT"
verify_expect "a bad signature" 1 F_SIGN_FAIL=1
printf 'other' > "$TMP/rc.apk"
verify_expect "other bytes" 1
unset AAPT2 APKSIGNER

if [ "$FAILS" -gt 0 ]; then echo "$FAILS failed"; exit 1; fi
echo "all passed"

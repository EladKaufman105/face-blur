#!/usr/bin/env bash
# Tests ci/version.sh on throwaway git repos: the branch flow from no tags to a major bump.
set -euo pipefail

SCRIPT="$(cd "$(dirname "$0")" && pwd)/version.sh"
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

if [ "$FAILS" -gt 0 ]; then echo "$FAILS failed"; exit 1; fi
echo "all passed"

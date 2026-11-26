# Contributing to ONIT

ONIT is an Android video editor with a browser version in `web/`. See README.md for what it does. AI agents read CLAUDE.md, which has the same rules.

## Branches

- `main`: releases only. A merge publishes a public GitHub release (APK): the tested beta's release candidate when `main`'s tree is that beta's, else once the emulator e2e passes. The web app from `main` is at https://eladkaufman105.github.io/face-blur/.
- `develop`: integration branch and the default branch. A merge publishes a beta pre-release of "ONIT Beta" once the emulator e2e passes (merges that change only `web/` make no beta). It has its own app id and installs beside ONIT. The web app from `develop` is the beta web app, at https://eladkaufman105.github.io/face-blur/beta/.
- Never push, force-push, rebase or delete `main` or `develop`. Repository rulesets block this.
- `labs` is retired. Do not push to it.

## Make a change

Branch from `origin/develop`. Name it `feature/<short-name>` or `fix/<short-name>`.

```
git fetch origin develop
git switch -c feature/<short-name> origin/develop
# edit, then
git add <files>
git commit -m "Editor: short sentence about the user-visible effect"
git push -u origin feature/<short-name>
gh pr create --base develop
```

With the repository setting "Automatically delete head branches" on, a feature branch is deleted when its PR is merged (restore it from the PR page). For more work, start a new branch from `origin/develop`; a cloud session whose branch was deleted recreates it from `origin/develop` (pushing recreates it on GitHub).

Commit messages are one short plain sentence about the user-visible effect. An area prefix is optional (`Editor: ...`, `Web: ...`).

Every user-visible string needs its English text. The UI is Hebrew and English. Check with `python3 ci/i18n_check.py`.

## Build locally

- Android: `bash ci/fetch-whisper.sh && bash ci/fetch-opencv.sh && ./gradlew assembleDebug`
- Web: `cd web && npm ci && npm run build`. Needs python3 with `onnx`, `numpy` and `onnxconverter-common`.
- The NDK version is pinned in `app/build.gradle.kts` (the default of GitHub's ubuntu-24.04 runner image).
- CI scripts: `bash ci/test_version.sh` tests `ci/version.sh`, `ci/promote-check.sh` and `ci/verify-rc.sh`.

## Pull request checks

Every change reaches `develop` or `main` through a PR. No human approval is required. `main` and `develop` are never deleted (default branch and rulesets), nor are the data branches (rulesets); side-workflow and old CI branches are never PR heads, so merges don't touch them. These checks must pass (workflows "PR guard" and "PR checks"):

- `source-branch`: PRs into `main` must come from `develop` or `hotfix/*`.
- `android-build`: i18n check, debug APK build, the instrumented tests compile, JVM unit tests.
- `web-build`: web build and the Chromium tests.

The Android emulator e2e runs after merge (`build.yml`): the instrumented tests in 4 parallel emulator shards, plus the release build's self-test. It gates the beta. Tests marked `@Weekly` and the full EndToEndTest clip set run weekly (Sunday) and in manual runs with `full`; those runs publish nothing. The arm64 APKs are built in parallel and published only when every shard passed.

Each beta run also builds the release candidate (RC): the next release's APK, kept 90 days as the artifact `release-candidate`. When `main`'s tree is the newest beta's (outside `EXCLUDES` in `ci/promote-check.sh`, the same list as `build.yml`'s `paths-ignore`), `build.yml` checks the RC (`ci/verify-rc.sh`) and releases it without a rebuild or emulator run. Otherwise, and for every hotfix, it rebuilds and runs the e2e first. On a release PR, `android-build` skips the build when the merged tree is a tested beta's, and `web-build` skips the tests when `web.yml` already tested the same web files on `develop`.

On a PR, `pr.yml` builds Android (debug) only when Android files, `ci/` or workflows change, and builds and tests the web app (Chromium, parallel shards, one per numbered case of `ci/web-tests.sh`) only when `web/`, the assets it copies from `app/src/main/assets` or workflows change. `android-build` and `web-build` always report, and pass when their work was skipped. A push to `develop` that changes web files (`web/`, the assets it copies from `app/src/main/assets`, `web.yml`, `web-tests.yml`, `ci/web-tests.sh`, `ci/web_i18n_check.py`, `ci/publish-pages.sh`, `ci/pages-build.sh` or `.github/actions/`) runs the web tests, then deploys the beta web app. Such a push to `main` deploys the release web app without tests. Neither touches the other. A manual run of `web.yml` runs every web test on the branch it's started from, then deploys the release (always built from `main`), the beta (always built from `develop`) or both; the weekly run only tests. Results are workflow artifacts on the run.

## Review loop

For every PR into `develop`, and for hotfix PRs into `main`:

1. Wait for green checks.
2. Start a reviewer with a fresh context (Opus, high effort). Give it the PR's goal and the diff or branch name, not your conclusions.
3. The reviewer tags each finding `blocking` (bug, crash, data loss, security or privacy issue, broken build or CI, broken release or versioning) or `nit`.
4. Fix each blocking finding, or reject it and say why. The next round judges the rejection.
5. Run at most 3 rounds. Only rounds with blocking findings count.
6. List nits in a PR comment. Fixing them is optional.
7. After round 3, if blocking findings remain, do not merge. Convert the PR to draft, add the label `needs-human`, and comment with each unresolved finding and both views.
8. When green with no blocking findings, squash merge into `develop`. Exception: a PR from `main` (hotfix merge-back) uses a merge commit.

## Release (develop -> main)

1. Open a PR from `develop` into `main`. No review loop.
2. Check that PR checks are green.
3. Check that the release is the beta that was tested on a device: nothing outside `EXCLUDES` changed on `develop` since the newest beta.

```
git fetch origin --tags
bash ci/promote-check.sh origin/develop
```

It must exit 0 and print no files. If it prints files, wait for the next beta and test it, or release anyway (the release then rebuilds and runs the full e2e). If that beta never came because its e2e failed, fix that first.

4. Write release notes in the PR body: the PRs merged into `develop` since the last release.
5. Ask "Release X.Y.Z?". Merge only after a clear yes.
6. Merge with a merge commit. Never squash or rebase. Versioning reads the beta tags from the history of `main`.

## Hotfix

```
git fetch origin main
git switch -c hotfix/<name> origin/main
# fix, commit, push
gh pr create --base main
```

Run the review loop, get a clear yes, then merge with a merge commit. Then open a PR `main` -> `develop` and merge it with a merge commit, so the fix returns to `develop`.

## When CI fails

- e2e fails after a merge into `develop`: no beta. Fix it with a normal PR.
- e2e fails after a merge into `main`: no release. Fix it with a `hotfix/*` PR.
- The weekly full e2e fails: nothing is published. Fix it with a normal PR.
- Promote fails before the release exists: re-run the run, or start `build.yml` on `main` by hand with `force_full`.
- A release exists but has no APK: tell the owner. Re-running fails on purpose.
- Until the first release brings the new `web.yml` to `main`, a hotfix into `main` that changes web files runs `main`'s old web workflow (GitHub Actions Pages deploy), which may fail or briefly replace the site: afterwards run "Web app" on `develop` with channel `both`.
- Changed a PR's base branch? Push a new commit so the builds run again.
- Never put `[skip ci]` in a commit message or merge title.

## Versioning

CI computes SemVer from git tags in `ci/version.sh`. Nobody types version numbers.

- Merge into `develop`: `vX.Y.Z-beta.N` pre-release.
- Merge `develop` -> `main`: minor bump. The first is 2.1.0. It is the newest beta's version without `-beta.N`.
- Hotfix into `main`: patch bump (2.1.1).
- Major bump: a PR into `develop` that raises `major=` in `version.properties`. Only when asked.
- Never create, move or delete `v*` tags by hand.
- Never hard-code `versionName` or `versionCode` in `app/build.gradle.kts`. Android `versionCode` is the run number of the `build.yml` run that built the APK (for a promoted release, the `develop` run that built its RC).
- Older releases use legacy tags `build-N` and `test-N`. Leave them.

## Other branches

- Side workflow branches are not merged into `develop`. Pushing runs their workflow: `opencv-src` builds `opencv-min2`, `ml-src` builds `ml-models`, `sfx-src` builds `sfx-fs`, `bench` runs the ARM benchmark (log only).
- Data branches are downloaded at runtime by the app or CI: `gpu-model`, `speaker-model`, `ml-models` (pinned commit), `opencv-min2` (SHA-pinned in `ci/fetch-opencv.sh`), `test-media`, `sfx-fs` (source of the bundled sounds, see `scripts/build_sfx.py`). Never delete, rebase or force-push them by hand. Installed apps would break.
- `gh-pages` is the web site (GitHub Pages serves it): the release web app at its root, the beta under `beta/`. Only CI writes it (`ci/publish-pages.sh`, one force-pushed commit), and it is the only branch CI writes. Never edit, push or delete it by hand.
- Apart from `gh-pages`, CI no longer writes branches. Its outputs are workflow artifacts (e2e results and screenshots, web test results, build logs on failure; kept 14 days). The old output branches `ci-results`, `ci-results-develop`, `ci-results-labs`, `ci-log`, `model-check` are kept but no longer written. Also kept: `opencv-min`.

## Secrets

Never add new secrets to the repo. Use GitHub Actions secrets. Known issue, being fixed separately: the release signing keystore and its passwords are committed (`app/faceblur.jks`, `app/build.gradle.kts`).

# Contributing to ONIT

ONIT is an Android video editor with a browser version in `web/`. See README.md for what it does. AI agents read CLAUDE.md, which has the same rules.

## Branches

- `main`: releases only. A merge publishes a public GitHub release (APK) once the emulator e2e passes, and deploys the web app to GitHub Pages when web files changed.
- `develop`: integration branch and the default branch. A merge publishes a beta pre-release of "ONIT Test" once the emulator e2e passes (merges that change only `web/` make no beta). It has its own app id and installs beside ONIT.
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

Commit messages are one short plain sentence about the user-visible effect. An area prefix is optional (`Editor: ...`, `Web: ...`).

Every user-visible string needs its English text. The UI is Hebrew and English. Check with `python3 ci/i18n_check.py`.

## Build locally

- Android: `bash ci/fetch-whisper.sh && bash ci/fetch-opencv.sh && ./gradlew assembleDebug`
- Web: `cd web && npm ci && npm run build`. Needs python3 with `onnx`, `numpy` and `onnxconverter-common`.

## Pull request checks

Every change reaches `develop` or `main` through a PR. No human approval is required. These checks must pass (workflows "PR guard" and "PR checks"):

- `source-branch`: PRs into `main` must come from `develop` or `hotfix/*`.
- `android-build`: i18n check and debug APK build.
- `web-build`: web build and one browser smoke test.

The full Android emulator e2e runs after merge (`build.yml`). It gates the release or beta.

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
3. Check that the release is the beta that was tested on a device: nothing outside `web/` changed on `develop` since the newest beta tag.

```
git fetch origin --tags
BETA=$(git tag --list 'v*-beta.*' --sort=-v:refname | head -1)
git diff --name-only "$BETA" origin/develop -- . ':!web' ':!.github/workflows/web.yml' ':!.github/workflows/models.yml'
```

It must print nothing. If it prints files, wait for the next beta and test it. If that beta never came because its e2e failed, fix that first.

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
- A release exists but has no APK: tell the owner. Re-running fails on purpose.
- Changed a PR's base branch? Push a new commit so the builds run again.
- Never put `[skip ci]` in a commit message or merge title.

## Versioning

CI computes SemVer from git tags in `ci/version.sh`. Nobody types version numbers.

- Merge into `develop`: `vX.Y.Z-beta.N` pre-release.
- Merge `develop` -> `main`: minor bump. The first is 2.1.0.
- Hotfix into `main`: patch bump (2.1.1).
- Major bump: a PR into `develop` that raises `major=` in `version.properties`. Only when asked.
- Never create, move or delete `v*` tags by hand.
- Never hard-code `versionName` or `versionCode` in `app/build.gradle.kts`. Android `versionCode` is the `build.yml` run number.
- Older releases use legacy tags `build-N` and `test-N`. Leave them.

## Other branches

- Side workflow branches are not merged into `develop`. Pushing runs their workflow: `opencv-src` builds `opencv-min2`, `ml-src` builds `ml-models`, `sfx-src` builds `sfx-fs`, `bench` runs the ARM benchmark (log only).
- Data branches are downloaded at runtime by the app or CI: `gpu-model`, `speaker-model`, `ml-models` (pinned commit), `opencv-min2` (SHA-pinned in `ci/fetch-opencv.sh`), `test-media`, `sfx-fs` (source of the bundled sounds, see `scripts/build_sfx.py`). Never delete, rebase or force-push them by hand. Installed apps would break.
- CI output branches are written only by CI: `ci-results`, `ci-results-develop`, `ci-log`, `model-check`. Legacy, kept: `ci-results-labs`, `opencv-min`.

## Secrets

Never add new secrets to the repo. Use GitHub Actions secrets. Known issue, being fixed separately: the release signing keystore and its passwords are committed (`app/faceblur.jks`, `app/build.gradle.kts`).

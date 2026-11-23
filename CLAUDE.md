# ONIT: rules for AI agents

ONIT is an Android video editor (blurs faces, subtitles, voice) with a browser version in `web/`. Read README.md for what it does. The app UI is Hebrew + English. Humans: see CONTRIBUTING.md.

## Branches

- `main`: releases only. Every merge publishes a public GitHub release (APK) and deploys the web app to GitHub Pages.
- `develop`: integration branch and the default branch. Every merge publishes a beta pre-release of "ONIT Test" (separate app id, installs beside ONIT).
- Never push, force-push, rebase or delete `main` or `develop`. Repository rulesets enforce this. Do not try to work around them.
- `labs` is retired. Do not push to it. It is kept as history.
- `hotfix/<name>`: branch from `main`, PR into `main`, then a PR `main` -> `develop` (merge commit) to bring the fix back.

## Starting work

- Feature work: branch from `origin/develop`. Open the PR into `develop`.
- Humans name branches `feature/<short-name>` or `fix/<short-name>`.
- Cloud sessions keep the branch name the session assigns (for example `ccr-...`). The branch must still be based on `origin/develop`.
- Before the first commit run `git fetch origin develop`. If the branch is not based on it, rebase or recreate the branch on `origin/develop`.
- Commit messages: one short plain sentence about the user-visible effect. Optional area prefix, like `Editor: ...` or `Web: ...`.

## Pull requests

- Every change reaches `develop` or `main` through a PR. No human approval is required.
- Required checks (workflow "PR checks", `.github/workflows/pr.yml`):
  - `source-branch`: PRs into `main` must come from `develop` or `hotfix/*`.
  - `android-build`: i18n check and debug APK build.
  - `web-build`: web build and one browser smoke test.
- The full Android emulator e2e runs after merge (`build.yml`). It gates the release or beta.
- Every user-visible string needs its English text. `ci/i18n_check.py` checks this.
- Fill in `.github/pull_request_template.md`.

## Review loop (PRs into develop, and hotfix PRs into main)

1. Wait for the checks to be green.
2. Spawn a reviewer sub-agent: model Opus, effort high, fresh context. Give it the PR's goal and the diff (or the branch name). Do not give it your reasoning or conclusions.
3. The reviewer tags each finding `blocking` or `nit`. Blocking means a bug, crash, data loss, security or privacy issue, broken build or CI, or broken release or versioning.
4. Fix each blocking finding, or reject it with a stated reason. The next round judges the rejection.
5. Run at most 3 rounds. Only rounds with blocking findings count.
6. List nits in a PR comment. Fixing them is optional.
7. If blocking findings remain after round 3, do not merge:
   - Convert the PR to draft.
   - Add the label `needs-human`.
   - Post a PR comment listing each unresolved finding with the reviewer's view and your view.
   - Tell the user.
8. If checks are green and no blocking findings remain, you may merge into `develop` yourself. Use squash merge.

## Release (develop -> main)

- Open a PR from `develop` into `main`. Skip the code-review loop. Each change was reviewed on its way into `develop`, and the user tests the beta on a device.
- Check that PR checks are green.
- Check that the HEAD of `develop` is exactly the commit of the newest beta tag (`vX.Y.Z-beta.N`). That is the beta the user tested.
- If `develop` has newer commits, warn the user. Let them choose: test the newer beta first, or wait.
- Write release notes in the PR body: the PRs merged into `develop` since the last release.
- Ask the user "Release X.Y.Z?". Merge only after a clear yes. Same for hotfix PRs into `main`.
- Merge PRs into `main` with a merge commit. Never squash or rebase. Versioning reads the beta tags from the history of `main`.

## Versioning

CI computes SemVer from git tags in `ci/version.sh`. Nobody types version numbers.

- Merge into `develop`: pre-release `vX.Y.Z-beta.N` ("ONIT Test <version>").
- Merge `develop` -> `main`: release `vX.Y.Z`, a minor bump. The first one is 2.1.0.
- Hotfix into `main`: patch bump (for example 2.1.1).
- Major bump: a PR into `develop` that raises `major=` in `version.properties`. Only when the user asks.
- Never create, move or delete `v*` tags by hand.
- Never hard-code `versionName` or `versionCode` in `app/build.gradle.kts`. Android `versionCode` is the `build.yml` run number.
- Older releases use legacy tags `build-N` (releases) and `test-N` (labs builds). Leave them.

## Other branches

- Side workflow branches are not merged into `develop`. Pushing to one runs its own workflow:
  - `opencv-src` builds `opencv-min2`.
  - `ml-src` builds `ml-models`.
  - `sfx-src` builds `sfx-fs`.
  - `bench` runs the ARM benchmark. It only writes a log.
- Data branches are downloaded at runtime by the shipped app or by CI: `gpu-model`, `speaker-model`, `ml-models` (pinned commit), `opencv-min2` (SHA-pinned in `ci/fetch-opencv.sh`), `test-media`, `sfx-fs` (source of the bundled sounds, see `scripts/build_sfx.py`). Never delete, rebase or force-push them by hand. Installed apps would break.
- CI output branches are written only by CI: `ci-results` (main), `ci-results-develop` (develop), `ci-log`, `model-check`. Legacy, kept: `ci-results-labs`, `opencv-min`. Do not write to them.

## Build

- Android: `bash ci/fetch-whisper.sh && bash ci/fetch-opencv.sh && ./gradlew assembleDebug`
- Web: `cd web && npm ci && npm run build`. Needs python3 with `onnx`, `numpy` and `onnxconverter-common`.
- Run the i18n check before pushing: `python3 ci/i18n_check.py`.

## Secrets

- Never add new secrets to the repo. Use GitHub Actions secrets.
- Known issue, being fixed separately: the release signing keystore and its passwords are committed (`app/faceblur.jks`, `app/build.gradle.kts`). Do not copy this pattern. Do not rotate or rewrite it unless asked.

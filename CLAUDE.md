# ONIT: rules for AI agents

ONIT is an Android video editor (blurs faces, subtitles, voice) with a browser version in `web/`. Read README.md for what it does. The app UI is Hebrew + English. Humans: see CONTRIBUTING.md.

## Branches

- `main`: releases only. A merge publishes a public GitHub release (APK): the release candidate of the tested beta when `main`'s tree is that beta's ("promote", a few minutes), else once the emulator e2e passes. The web app from `main` is at https://eladkaufman105.github.io/face-blur/.
- `develop`: integration branch and the default branch. A merge publishes a beta pre-release of "ONIT Beta" (separate app id, installs beside ONIT) once the emulator e2e passes. Merges that change only paths in `EXCLUDES` of `ci/promote-check.sh` (`web/`, `docs/`, `.claude/`, the PR template, `parity-hint.yml` and the web app's CI files) make no beta. Merges that change web files run the web tests, then deploy the beta web app. The web app from `develop` is the beta web app, at https://eladkaufman105.github.io/face-blur/beta/.
- Never push, force-push, rebase or delete `main` or `develop`. Repository rulesets enforce this. Do not try to work around them.
- `labs` is retired. Do not push to it. It is kept as history.
- `hotfix/<name>`: branch from `main`, PR into `main`, then a PR `main` -> `develop` (merge commit) to bring the fix back.

## Starting work

- Feature work: branch from `origin/develop`. Open the PR into `develop`.
- Humans name branches `feature/<short-name>` or `fix/<short-name>`.
- Cloud sessions keep the branch name the session assigns (for example `ccr-...`). The branch must still be based on `origin/develop`.
- Before the first commit run `git fetch origin develop`. If the branch is not based on it, rebase or recreate the branch on `origin/develop`.
- A cloud session whose branch was deleted after its PR merged recreates the branch from `origin/develop` before new work (pushing recreates it on GitHub).
- Commit messages: one short plain sentence about the user-visible effect. Optional area prefix, like `Editor: ...` or `Web: ...`.

## Pull requests

- Every change reaches `develop` or `main` through a PR. No human approval is required.
- With the repository setting "Automatically delete head branches" on, a feature branch is deleted when its PR is merged (restore it from the PR page). `main` and `develop` are never deleted (default branch and rulesets), nor are the data branches (rulesets). Side-workflow and old CI branches are never PR heads, so merges don't touch them.
- Required checks (`.github/workflows/pr-guard.yml` runs `source-branch`; `.github/workflows/pr.yml` runs the builds):
  - `source-branch`: PRs into `main` must come from `develop` or `hotfix/*`.
  - `android-build`: i18n check, debug APK build, the instrumented tests compile, JVM unit tests. On a release PR it skips the build when the merged tree is a tested beta's (`ci/promote-check.sh`).
  - `web-build`: web build and the Chromium tests. On a release PR it skips the tests when `web.yml` already tested the same web files on `develop`.
- The Android emulator e2e runs after merge (`build.yml`): the instrumented tests in 4 parallel emulator shards, plus the release build's self-test. It gates the beta, and the release when it isn't promoted. Tests marked `@Weekly` and the full EndToEndTest clip set run only in the weekly run (Sunday) and in manual runs with `full`, which publish nothing. The arm64 APKs are built in parallel with it and published only when every shard passed.
- On `develop`, `build.yml` also builds the release candidate (RC): the next release's APK (app id `com.elad.faceblur`, the beta's version without `-beta.N`), kept 90 days as the run's artifact `release-candidate`. The beta's release body names it (`rc-run`, `rc-sha256`).
- The promote path: on a push to `main`, `build.yml`'s `plan` job runs `ci/promote-check.sh plan`. If `main` differs from the newest beta of the release version only in `EXCLUDES`, that beta's run passed, its RC exists and its versionCode is above the latest release's, `ci/verify-rc.sh` checks the RC (app id, version, versionCode, signing certificate, sha256) and `promote` releases it, with no build and no emulator. Any miss runs the full path (build, e2e, publish). Hotfixes always take the full path. A beta older than 90 days has no RC left: full path.
- `EXCLUDES` in `ci/promote-check.sh` and `build.yml`'s `paths-ignore` are one list in two places. `pr.yml` fails if they differ.
- `pr.yml` runs the Android debug build only when Android files, `ci/` or workflows change, and the web build and Chromium tests (parallel shards, one per numbered case of `ci/web-tests.sh`) only when `web/`, the models in `models/`, the assets the web app copies from `app/src/main/assets`, or workflows change. `android-build` and `web-build` always report; they pass when their work was skipped. A push to `develop` that changes web files (`web/`, `models/`, the assets it copies from `app/src/main/assets`, `web.yml`, `web-tests.yml`, `ci/web-tests.sh`, `ci/web_i18n_check.py`, `ci/publish-pages.sh`, `ci/pages-build.sh` or `.github/actions/`) runs the web tests on the merged tree, then deploys the beta web app. Such a push to `main` deploys the release web app without tests. Neither touches the other. A manual run of `web.yml` runs every web test (the full versions too, `FULL=1`) on the branch it's started from, then deploys the release (always built from `main`), the beta (always built from `develop`) or both; the weekly run only tests. The subtitles test (Whisper, a 2.2 GB download) runs on a PR into `develop` only when subtitle code changes, and weekly and on manual runs of `web.yml` (not on `develop` pushes or release PRs).
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
8. If checks are green and no blocking findings remain, you may merge into `develop` yourself. Use squash merge, except a PR from `main` (hotfix merge-back): merge commit.

## Parity

- The app (`app/`) and the web app (`web/`) offer the same features. `docs/parity.md` tracks the differences and the accepted exceptions.
- Every PR that changes user-facing behaviour on one platform either includes the same change on the other, or links a follow-up parity PR or issue and updates `docs/parity.md` (status `gap-web` or `gap-app`). Exceptions: a difference listed under "Accepted exceptions" in `docs/parity.md`, or a PR that ticks `platform-only` in the template.
- Use the `parity` agent (`.claude/agents/parity.md`) to port. Its PRs start with `Parity:`. The agent stops when the PR is open and its checks are green: the calling session runs the review loop and merges.
- Privacy behaviour must be identical or stricter on both platforms. Never weaken a gate to match the other one.
- Releases (develop -> main) list the open `gap-*` rows in the release notes.

## Release (develop -> main)

- Open a PR from `develop` into `main`. Skip the code-review loop. Each change was reviewed on its way into `develop`, and the user tests the beta on a device.
- Check that PR checks are green.
- Check that the release is the beta the user tested: run `git fetch origin --tags` and `bash ci/promote-check.sh origin/develop`. It must exit 0 and print no files: nothing outside `EXCLUDES` changed on `develop` since the newest beta.
- If it prints files, warn the user. Let them choose: wait for the next beta and test it, or release anyway (then `main` takes the full path, about 20 minutes). If that next beta never came because its e2e failed, fix that first with a normal PR into `develop`.
- After the merge, check the `build.yml` run on `main`: the `plan` job's summary says promote or full path, and why.
- Write release notes in the PR body: the PRs merged into `develop` since the last release, and the open `gap-*` rows of `docs/parity.md`.
- Check that the last weekly `build.yml` and `web.yml` runs on `develop` are green (they run every test, including the `@Weekly` ones and all clips). If one is red, tell the user before asking.
- Ask the user "Release X.Y.Z?". Merge only after a clear yes. Same for hotfix PRs into `main`.
- Merge PRs into `main` with a merge commit. Never squash or rebase. Versioning reads the beta tags from the history of `main`.

## When CI fails

- e2e fails after a merge into `develop`: no beta. Fix it with a normal PR into `develop`.
- e2e fails after a merge into `main`: no release. Fix it with a `hotfix/*` PR.
- The weekly full e2e (Sunday, `build.yml`) fails: nothing is published. Fix it with a normal PR into `develop`.
- `promote` fails before the release exists: re-run the `build.yml` run, or start `build.yml` on `main` by hand with `force_full` for the full path.
- A release or beta exists but has no APK (upload failed): tell the user. Re-running the whole workflow fails on purpose ("already released"). Do not re-run only the `publish` or `promote` job (it would skip that check); the owner fixes it by hand.
- Until the first release brings the new `web.yml` to `main`, a hotfix into `main` that changes web files runs `main`'s old web workflow (GitHub Actions Pages deploy), which may fail or briefly replace the site: afterwards run "Web app" on `develop` with channel `both`.
- If you change a PR's base branch, push a new commit so the builds run again.
- Never put `[skip ci]` in a commit message or merge title. In a PR it stops the required checks; in a merge into `develop` or `main` it stops the beta or release.

## Versioning

CI computes SemVer from git tags in `ci/version.sh`. Nobody types version numbers.

- Merge into `develop`: pre-release `vX.Y.Z-beta.N` ("ONIT Beta <version>").
- Merge `develop` -> `main`: release `vX.Y.Z`, a minor bump. The first one is 2.1.0. Its version is the newest beta's without `-beta.N` (`ci/test_version.sh` tests this), so the beta's RC can be released as it is.
- Hotfix into `main`: patch bump (for example 2.1.1).
- Major bump: a PR into `develop` that raises `major=` in `version.properties`. Only when the user asks.
- Never create, move or delete `v*` tags by hand.
- Never hard-code `versionName` or `versionCode` in `app/build.gradle.kts`. Android `versionCode` is the run number of the `build.yml` run that built the APK (for a promoted release, the `develop` run that built its RC).
- Older releases use legacy tags `build-N` (releases) and `test-N` (labs builds). Leave them.

## Other branches

- Side workflow branches are not merged into `develop`. Pushing to one runs its own workflow:
  - `opencv-src` builds `opencv-min2`.
  - `ml-src` builds `ml-models`.
  - `sfx-src` builds `sfx-fs`.
  - `bench` runs the ARM benchmark. It only writes a log.
- Data branches are downloaded at runtime by the shipped app or by CI: `gpu-model`, `speaker-model`, `ml-models`, `test-media`, `sfx-fs` (source of the bundled sounds, see `scripts/build_sfx.py`), `opencv-min2` (SHA-pinned in `ci/fetch-opencv.sh`). The app's models are pinned by commit (or a `models-*` release asset) in `app/src/main/assets/model-registry.json`, with size and sha256. Never delete, rebase or force-push them by hand, nor delete a `models-*` release asset. Installed apps would break.
- `gh-pages` is the web site (GitHub Pages serves it): the release web app at its root, the beta under `beta/`. Only CI writes it (`ci/publish-pages.sh`, one force-pushed commit), and it is the only branch CI writes. Never edit, push or delete it by hand.
- Apart from `gh-pages`, CI no longer writes branches. Its outputs are workflow artifacts (e2e results and screenshots, web test results, build logs on failure; kept 14 days). The old output branches `ci-results`, `ci-results-develop`, `ci-results-labs`, `ci-log`, `model-check` are kept but no longer written. Also kept: `opencv-min`. Do not write to them.

## Build

- Android: `bash ci/fetch-whisper.sh && bash ci/fetch-opencv.sh && ./gradlew assembleDebug`
- Web: `cd web && npm ci && npm run build`. Needs python3 with `onnx`, `numpy` and `onnxconverter-common`.
- Instrumented tests and the release self-test need the models: run `bash ci/fetch-models.sh` first (it puts them in `app/src/androidTest/assets/models`).
- Models are not in the APK. The app downloads them on demand (first-launch sheet, a feature's card, Settings > Models), as `model-registry.json` lists them. The ONNX models the web app shares are in `models/`. A model update takes two PRs: one changes the file (in `models/`, a data branch, or a new `models-N` release through `models.yml`), the next updates its URL pin, size, sha256 and the pack's `version` in the registry. Face blur never runs without its whole pack. The multilingual Whisper is `unpinned` (size check only) until `models.yml` mirrors it.
- Run the i18n check before pushing: `python3 ci/i18n_check.py`.
- The NDK version is pinned in `app/build.gradle.kts` (the default NDK of GitHub's ubuntu-24.04 runner image), so a beta and its release compile with the same one. Change it on purpose.
- After changing `ci/version.sh`, `ci/promote-check.sh` or `ci/verify-rc.sh`, run `bash ci/test_version.sh`.

## Secrets

- Never add new secrets to the repo. Use GitHub Actions secrets.
- Known issue, being fixed separately: the release signing keystore and its passwords are committed (`app/faceblur.jks`, `app/build.gradle.kts`). Do not copy this pattern. Do not rotate or rewrite it unless asked.

# Admin migration plan: develop / main workflow

**For:** the repo owner's Claude agent. Elad owns `EladKaufman105/face-blur` and is its only admin.
**Elad uses the Claude app on his phone.** He doesn't write code. Ask him only short yes/no questions in plain words. Never ask him to read files or run commands. When he has to tap something in GitHub, give him one direct link and one step at a time.
**Prepared on:** branch `migration/develop-workflow`, by a collaborator's Claude session. Every file change is already written, tested where possible, and reviewed. Your job is to execute, verify and turn on the settings.

## What this changes

| Before | After |
|---|---|
| Everyone pushes straight to `main` | Work goes through pull requests (PRs) |
| Every push to `main` publishes a release; `labs` publishes test builds | `develop` publishes betas (`v2.1.0-beta.N`, the "ONIT Test" app); `main` publishes releases (`v2.1.0`) |
| Version names `2.0.<build>` | SemVer (major.minor.patch), worked out by CI from git tags (`ci/version.sh`) |
| No rules on any branch | Rulesets: `main` and `develop` accept PRs only; data branches and release/model tags can't be deleted |

Files on the branch:
- `.github/workflows/`: `build.yml`, `web.yml`, and the new `pr.yml` and `pr-guard.yml`.
- `ci/version.sh` and `ci/test_version.sh`; `version.properties`; `app/build.gradle.kts` (version lines only).
- `CLAUDE.md`, `CONTRIBUTING.md`, `.github/pull_request_template.md`.
- `.github/rulesets/*.json`.
- `README.md` (2 paragraphs) and this file.

## Rules for you, the executing agent

1. Do the phases in order. Finish each phase's **Check** before moving on.
2. **STOP-ASK** means: ask Elad the quoted question and wait for a clear yes.
3. If a step fails or a check doesn't match: stop, don't improvise, tell Elad in one sentence what failed, and use **Rollback** if needed.
4. Never force-push or delete branches. The one exception is in Phase 4.1, and it needs Elad's yes. Never touch the data branches: `gpu-model`, `speaker-model`, `ml-models`, `opencv-min2`, `test-media`, `sfx-fs`. Installed apps and builds download from them.
5. Tools, in this order:
   - **`gh` CLI** logged in as Elad, if it works for admin calls. Test with Phase 0.1.
   - **GitHub MCP tools** for PRs, merges and branches.
   - **Appendix B**: for Settings changes your tools can't make (rulesets, default branch, merge buttons). Elad taps them in his phone's browser while you guide him.

   A cloud session in the Claude app usually can't make admin API calls. Expect to use Appendix B for Phase 2.2-2.3 and Phase 3.
6. Repo: `EladKaufman105/face-blur`.

## Phase 0: Preconditions (5 min)

1. **Admin access.** Try `gh api repos/EladKaufman105/face-blur --jq .permissions.admin`. If it prints `true`, you can use `gh` for admin steps. Otherwise plan on Appendix B for those steps. Elad is the owner, so he is admin.
2. **Branch check.** The message that sent Elad here names the expected head commit of `migration/develop-workflow`. Compare it with the branch's actual head (`git ls-remote origin migration/develop-workflow`, or the GitHub MCP). If they differ, **STOP-ASK**: "The prepared branch changed after it was handed over. Continue anyway?"
3. **STOP-ASK**: "For about an hour nobody may change the main branch, including other Claude sessions. Have you told your collaborator, and is nobody working on it right now?"
4. **Nothing running.** Wait until no workflow is running or queued in the repo's Actions.
5. **Write down `OLD_MAIN`.** It's the current `main` commit, and Rollback needs it.

## Phase 1: Merge the prepared changes without a release (10 min)

1. Open a PR from `migration/develop-workflow` into `main`, titled "Branch workflow: develop, PR checks, SemVer". Use this file's "What this changes" table as the body.
2. **Conflicts.** If GitHub says the PR has conflicts: **STOP-ASK** "The main branch changed and clashes with the prepared work. Stop here so your collaborator can update it?" Don't resolve conflicts yourself.
3. **Checks.** The "PR checks" workflow runs on this PR. `android-build` and `web-build` must pass; if either fails, stop. The `source-branch` check does **not** run on this PR. That's expected: it only runs once it exists on `main`.
4. **Merge** with a **merge commit** (not squash) whose title ends in `[skip ci]`. That stops this merge from publishing a release.
   - Title: `Branch workflow: develop, PR checks, SemVer [skip ci]`
   - With `gh`: `gh pr merge <PR number> -R EladKaufman105/face-blur --merge --subject "<title>"`
   - With the GitHub MCP: merge method `merge`, commit title as above.
5. **Check:** no new "Build APK" run started for the merge commit. If one did, cancel it right away.
6. **Optional baseline tag** `v2.0.0` at the new `main` commit: `gh api repos/EladKaufman105/face-blur/git/refs -f ref=refs/tags/v2.0.0 -f sha=<NEW_MAIN>`. It only labels history; versioning works the same without it. Skip it if your tools can't create tags.

## Phase 2: Create develop and set repo settings (5 min)

1. **Create `develop` from `main`.** The new `main` commit is `NEW_MAIN`. With `gh`: `gh api repos/EladKaufman105/face-blur/git/refs -f ref=refs/heads/develop -f sha=<NEW_MAIN>`. With the MCP: create branch `develop` from `main`. Without either: Appendix B step 1.
   - Its head commit carries `[skip ci]`, so no workflow should run. If "Build APK" does start on `develop`, let it finish. It would publish the first beta, `v2.1.0-beta.1`, which is harmless.
2. **Default branch → `develop`.** With `gh`: `gh api -X PATCH repos/EladKaufman105/face-blur -f default_branch=develop`. Otherwise: Appendix B step 2.
3. **Merge buttons.**
   - Merge commits on, squash on, rebase off.
   - "Automatically delete head branches" **off**.
   - With `gh`: `gh api -X PATCH repos/EladKaufman105/face-blur -F allow_merge_commit=true -F allow_squash_merge=true -F allow_rebase_merge=false -F delete_branch_on_merge=false`.
   - Otherwise: Appendix B step 3.
4. **Pin the background-removal models.** Installed apps download them from commit `187ecd0f0bdb7e082b132a0e844e3a6785fb463b` of `ml-models`. A tag keeps that commit from ever disappearing: `gh api repos/EladKaufman105/face-blur/git/refs -f ref=refs/tags/pinned/ml-models-187ecd0 -f sha=187ecd0f0bdb7e082b132a0e844e3a6785fb463b`. If your tools can't create tags, skip it and tell Elad in the final message.
5. **Label** for stuck review loops: name `needs-human`, color `B60205`, description "Review loop hit 3 rounds; a person decides". Creating labels needs no admin.
6. **Check:** the default branch is `develop`, and the merge buttons are as in step 3.

## Phase 3: Rulesets (5-15 min)

The four rulesets are in `.github/rulesets/` on `develop`.
- With `gh`: `git fetch origin && git switch --detach origin/develop`, then `for f in .github/rulesets/*.json; do gh api -X POST repos/EladKaufman105/face-blur/rulesets --input "$f"; done`.
- Without `gh`: Appendix B step 4. Elad creates them by hand, about 10 minutes.

**Check:** these 4 rulesets exist and are **Active**, each with an **empty bypass list**:
- `main: releases only`
- `develop: PRs only`
- `data branches: no deletion`
- `protected tags: no moving or deleting`

What they do:
- **`main` and `develop`:**
  - A PR is required, with 0 approvals.
  - The checks `source-branch`, `android-build` and `web-build` must pass.
  - No force-push, no deletion. Nobody bypasses, not even admins.
  - `main` allows only merge commits, which versioning needs. `develop` allows squash or merge.
- **Data branches:** can't be deleted. CI can still update them.
- **Tags `v*`, `models-*`, `pinned/*`:** can't be moved or deleted. CI can still create new ones.

## Phase 4: Dry run (40-60 min, mostly waiting for CI)

1. **Direct push is blocked.**
   - Push an empty commit straight to `develop`, for example from a temporary branch made from `origin/develop`: `git push origin HEAD:develop`. Expected: GitHub rejects it and mentions rules.
   - If your environment refuses the push itself before GitHub can (for example, it only lets you push your own session branch), skip this test.
   - If GitHub **accepts** the push, the rules aren't working. **STOP-ASK**: "The branch rules didn't block a test change. Can I undo it?" To undo: temporarily disable the `develop` ruleset, force-push `develop` back to `NEW_MAIN` (the one allowed force-push), and turn the ruleset back on.
2. **A normal feature PR works.** This one also files this plan away.
   - From `develop`, make branch `docs/archive-migration-plan`.
   - Move this file to `docs/admin-migration-2026-11.md` and add `Done on <date>.` at the top.
   - Commit, push, and open a PR into `develop`.
   - **Check:** all 3 checks pass (`source-branch`, `android-build`, `web-build`), then squash merge works.
   - The CLAUDE.md review loop is optional for this doc-only PR.
3. **The first beta comes out.** The merge starts "Build APK" on `develop`: the emulator e2e first (about 20-30 min), then the build.
   - **Check:** a pre-release "ONIT Test 2.1.0-beta.1" with file `ONIT-Test-2.1.0-beta.1.apk`, tagged `v2.1.0-beta.1`, not marked "Latest".
   - If e2e fails, re-run the failed jobs once. If it fails again, stop and report.
4. **The release guard works.**
   - From `develop`, make branch `guard-test` with one empty commit, and open a PR from it into **`main`**.
   - **Check:** `source-branch` fails ("PRs into main come only from develop (release) or hotfix/*"), and the PR can't be merged.
   - Close the PR and delete `guard-test`. It's a throwaway branch, so deleting it is fine.
5. **Don't release to `main` now.** The first real release, `2.1.0`, comes later through the normal flow, after the collaborator tests the beta on a phone.

## Phase 5: Finish

1. Tell Elad in plain words:
   - "Done. Tell your collaborator the freeze is over."
   - "From now on all work starts from develop and goes in through pull requests."
   - The link to the beta from Phase 4.
   - Anything you skipped, such as the tags in Phase 1.6 or Phase 2.4.
2. Leave `labs` as it is. It's retired, but kept as history.

## Rollback

Undo in this order, as far back as you need:

1. **Rulesets off.** Settings → Rules → Rulesets → each one → Enforcement status: Disabled. With `gh`: `gh api -X PUT repos/EladKaufman105/face-blur/rulesets/<id> -f enforcement=disabled`.
2. **Default branch back** to `main` (Appendix B step 2, choosing `main`).
3. **Old workflows back.**
   - On a branch from `main`, revert the Phase 1 merge commit (`git revert -m 1 <merge commit>`). Open a PR into `main` and merge it.
   - If it's urgent and the rules are off, pushing straight to `main` is allowed.
   - CI then works as before: pushes to `main` and `labs` release.
4. Leave `develop` and any tags in place. They do no harm.

## Appendix A: things not done here, on purpose

- The release signing key (`app/faceblur.jks`) and its passwords are in the repo. Moving them to Actions secrets is a separate task.
- The stale branches `opencv-min`, `ci-log`, `ci-results-labs` and `labs` are kept, by the owner's choice.
- No paid tools and no Copilot review. PRs are reviewed by a Claude reviewer sub-agent, as CLAUDE.md describes.

## Appendix B: Settings by hand (Elad on his phone)

Send Elad one link and one instruction at a time, and wait for "done". Tips for him: open links in the phone's browser, signed in to GitHub, not in the GitHub app. If the page looks cramped, switch the browser to "Desktop site".

1. **Create `develop`** (only if your tools couldn't):
   - Open https://github.com/EladKaufman105/face-blur/branches and tap **New branch**.
   - Name: `develop`. Source: `main`. Tap **Create**.
2. **Default branch:**
   - Open https://github.com/EladKaufman105/face-blur/settings
   - Under "Default branch", tap the switch (arrows) icon, choose `develop`, then **Update**, then confirm.
3. **Merge buttons:** same page, section "Pull Requests":
   - "Allow merge commits": on.
   - "Allow squash merging": on.
   - "Allow rebase merging": off.
   - "Automatically delete head branches": off.
4. **Rulesets.** Open https://github.com/EladKaufman105/face-blur/settings/rules. Each ruleset starts with **New ruleset**: **New branch ruleset** for a–c, **New tag ruleset** for d. Then:
   - Name: as below.
   - Enforcement status: **Active**.
   - Bypass list: leave empty.
   - Targets: **Add target → Include by pattern**, once per pattern below.
   - Rules: tick exactly the ones listed, untick the rest.
   - Tap **Create**.

   a. Name `main: releases only`.
      - Pattern: `main`.
      - Rules: **Restrict deletions**; **Block force pushes**.
      - **Require a pull request before merging**: Required approvals `0`; under "Allowed merge methods", only **Merge**.
      - **Require status checks to pass**: add `source-branch`, `android-build` and `web-build`, typing each name and choosing it.

   b. Name `develop: PRs only`.
      - Pattern: `develop`.
      - Same as (a), but "Allowed merge methods" are **Merge** and **Squash**.

   c. Name `data branches: no deletion`.
      - Patterns: `gpu-model`, `speaker-model`, `ml-models`, `opencv-min2`, `test-media`, `sfx-fs`.
      - Rules: only **Restrict deletions**. Untick "Block force pushes", because CI updates these branches.

   d. Name `protected tags: no moving or deleting`.
      - Patterns: `v*`, `models-*`, `pinned/*`.
      - Rules: **Restrict updates** and **Restrict deletions** only. Leave "Restrict creations" unticked, because CI creates new tags.
5. **Merge with `[skip ci]` on the website** (Phase 1.4, if your tools can't set the title):
   - On the PR page, choose **Create a merge commit** in the merge button's menu.
   - Tap the button. In the title box, add ` [skip ci]` at the end, then **Confirm merge**.

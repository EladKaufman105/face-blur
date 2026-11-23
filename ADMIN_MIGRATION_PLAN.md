# Admin migration plan: develop / main workflow

**For:** the repo owner's Claude agent, which has admin rights on `EladKaufman105/face-blur`.
**Owner:** Elad. He doesn't write code, so ask him only yes/no questions, in plain words.
**Prepared on:** branch `migration/develop-workflow`, by a collaborator's Claude session. Every file change is already written and reviewed. Your job is to execute, verify and turn on the settings.

## What this changes

| Before | After |
|---|---|
| Everyone pushes straight to `main` | Work goes through pull requests (PRs) |
| Every push to `main` publishes a release; `labs` publishes test builds | `develop` publishes betas (`v2.1.0-beta.N`, the "ONIT Test" app); `main` publishes releases (`v2.1.0`) |
| Version names `2.0.<build>` | SemVer (major.minor.patch), worked out by CI from git tags (`ci/version.sh`) |
| No rules on any branch | Rulesets: `main` and `develop` accept PRs only; data branches and `v*` tags can't be deleted |

Files on the branch: `.github/workflows/build.yml`, `web.yml` and the new `pr.yml` and `pr-guard.yml`; `ci/version.sh` and `ci/test_version.sh`; `version.properties`; `app/build.gradle.kts` (version lines only); `CLAUDE.md`, `CONTRIBUTING.md`, `.github/pull_request_template.md`; `.github/rulesets/*.json`; `README.md` (2 paragraphs); this file.

## Rules for you, the executing agent

1. Do the phases in order. Finish each phase's **Check** before moving on.
2. **STOP-ASK** means: ask Elad the quoted question and wait for a clear yes.
3. If a step fails or a check doesn't match: stop, don't improvise, tell Elad what failed, and use **Rollback** if needed.
4. Never force-push or delete branches. Never touch the data branches: `gpu-model`, `speaker-model`, `ml-models`, `opencv-min2`, `test-media`, `sfx-fs`. Installed apps download from them.
5. Tools: use the `gh` CLI, logged in as the owner, if it works. Otherwise use GitHub MCP tools for PRs and branches, and **Appendix B** (Elad clicks in Settings, you guide him) for anything they can't do.
6. Repo: `EladKaufman105/face-blur` (below: `R`).

## Phase 0: Preconditions (5 min)

1. Admin check: `gh api repos/EladKaufman105/face-blur --jq .permissions.admin` must print `true`. If `gh` can't do this, ask Elad to open the repo's Settings tab. If he can see it, he's admin.
2. Branch check: the person who sent Elad here gave him the expected head commit of `migration/develop-workflow`. Compare: `git ls-remote origin migration/develop-workflow`. If it differs, **STOP-ASK**: "The prepared branch changed after it was handed over. Should I continue anyway?"
3. **STOP-ASK**: "For about an hour nobody may push to main, including other Claude sessions. Have you told your collaborator and are no sessions working on main right now?"
4. Wait until no workflow is running or queued: `gh run list -R EladKaufman105/face-blur --status in_progress` and `--status queued` both print nothing.
5. Record the current `main` commit: `gh api repos/EladKaufman105/face-blur/commits/main --jq .sha`. Write it down as `OLD_MAIN`; rollback needs it.

## Phase 1: Merge the prepared changes, without a release (10 min)

1. Open a PR from `migration/develop-workflow` into `main`, titled "Branch workflow: develop, PR checks, SemVer". Use this file's "What this changes" table as the body.
2. If GitHub says the PR has conflicts: **STOP-ASK** "main changed during the freeze and conflicts with the prepared work. Should I stop here so the collaborator can update the branch?" Don't resolve conflicts in workflow or version files yourself.
3. The new "PR checks" and "PR guard" workflows run on this PR. Expected result:
   - `android-build` and `web-build` must pass. If either fails, stop.
   - `source-branch` **fails, and that is correct**: this PR doesn't come from `develop`. No rules are on yet, so it doesn't block.
4. Merge with a **merge commit** whose title ends in `[skip ci]`, so this merge doesn't publish a release:
   `gh pr merge <PR number> -R EladKaufman105/face-blur --merge --subject "Branch workflow: develop, PR checks, SemVer [skip ci]"`
5. **Check:** `gh run list -R EladKaufman105/face-blur --branch main --limit 3` shows no new "Build APK" run for the merge commit. If one started, cancel it right away: `gh run cancel <id>`.
6. Baseline tag, marking "everything up to here is 2.0". Let `NEW_MAIN` be the commit `main` now points to:
   `gh api repos/EladKaufman105/face-blur/git/refs -f ref=refs/tags/v2.0.0 -f sha=<NEW_MAIN>`

## Phase 2: Create develop and set repo settings (5 min)

1. Create `develop` from `main`: `gh api repos/EladKaufman105/face-blur/git/refs -f ref=refs/heads/develop -f sha=<NEW_MAIN>`.
   - Its head commit carries `[skip ci]`, so no workflow should run. If "Build APK" does start on `develop`, let it finish: it publishes the first beta, `v2.1.0-beta.1`, which is harmless.
2. Make `develop` the default branch: `gh api -X PATCH repos/EladKaufman105/face-blur -f default_branch=develop`
3. Merge buttons: `gh api -X PATCH repos/EladKaufman105/face-blur -F allow_merge_commit=true -F allow_squash_merge=true -F allow_rebase_merge=false -F delete_branch_on_merge=false`
   - Keep auto-delete of head branches **off**. It must never touch `develop`.
4. Label used when an AI review loop gets stuck: `gh label create needs-human -R EladKaufman105/face-blur --color B60205 --description "Review loop hit 3 rounds; a person decides"`
5. **Check:** `gh api repos/EladKaufman105/face-blur --jq '.default_branch, .allow_merge_commit, .allow_squash_merge, .allow_rebase_merge'` prints `develop true true false`.

## Phase 3: Rulesets (5 min)

1. Create one ruleset per file. Run from a checkout of `develop` (or main):
   ```
   for f in .github/rulesets/*.json; do gh api -X POST repos/EladKaufman105/face-blur/rulesets --input "$f"; done
   ```
   - No `gh`: Appendix B, step 3 (import each JSON file in Settings).
2. **Check:** `gh api repos/EladKaufman105/face-blur/rulesets --jq '.[] | "\(.name) \(.enforcement)"'` lists 4 rulesets, all `active`:
   - `main: releases only`
   - `develop: PRs only`
   - `data branches: no deletion`
   - `version tags: no moving or deleting`

What they do:
- `main` and `develop`: PR required, 0 approvals, required checks `source-branch`, `android-build` and `web-build`. No force-push, no deletion, nobody bypasses, not even admins.
- On `main` only merge commits are allowed: versioning needs them. On `develop`, squash or merge.
- Data branches: can't be deleted. CI can still update them.
- `v*` tags: can't be moved or deleted. CI can still create new ones.

## Phase 4: Dry run (40-60 min, mostly waiting for CI)

1. **Direct push is blocked.** In a local clone: `git fetch origin && git switch -c tmp-push-test origin/develop && git commit --allow-empty -m "push test" && git push origin HEAD:develop`.
   - Expected: rejected, with a message about rules.
   - If the push is **accepted**, the rules aren't working. Stop, and ask Elad whether to undo it. Undo means temporarily disabling the `develop` ruleset, then `git push --force origin <NEW_MAIN>:develop`, then turning it back on.
   - Afterwards: `git switch - && git branch -D tmp-push-test`.
2. **A normal feature PR works.** This also files this plan away:
   1. `git switch -c docs/archive-migration-plan origin/develop`
   2. `mkdir -p docs && git mv ADMIN_MIGRATION_PLAN.md docs/admin-migration-2026-11.md`
   3. Add one line at the top: `Done on <date>.`
   4. Commit, push, and open a PR into `develop`.
   5. **Check:** all 3 checks pass, including `source-branch`. Squash merge works.
   - The CLAUDE.md review loop is optional for this doc-only PR.
3. **The first beta comes out.** The merge starts "Build APK" on `develop`: emulator e2e first (about 20-30 min), then the build.
   - **Check:** a pre-release "ONIT Test 2.1.0-beta.1" exists, tagged `v2.1.0-beta.1`, with file `ONIT-Test-2.1.0-beta.1.apk`. It isn't marked "Latest". Branch `ci-results-develop` was updated.
   - If e2e fails, rerun it once: `gh run rerun <id> --failed`. If it fails again, stop and report.
4. **The release guard works.**
   1. `git switch -c guard-test origin/develop && git commit --allow-empty -m "guard test" && git push -u origin guard-test`
   2. Open a PR from `guard-test` into **main**.
   3. **Check:** `source-branch` fails with "PRs into main come only from develop (release) or hotfix/*", and the PR can't be merged.
   4. Close the PR and delete `guard-test`. That's a normal branch, so deleting it is fine.
5. **Don't release to `main` now.** The first real release, `2.1.0`, happens later through the normal flow, after the collaborator tests the beta on a phone.

## Phase 5: Finish

1. Tell Elad: "Done. Tell your collaborator the freeze is over. From now on all work starts from develop and goes in through pull requests." Include the beta link from Phase 4.
2. Leave `labs` as it is. It's retired, but kept as history.

## Rollback

Undo in this order, as far back as you need:

1. Rules off: for each ruleset, `gh api -X PUT repos/EladKaufman105/face-blur/rulesets/<id> -f enforcement=disabled`. Get ids with `gh api repos/EladKaufman105/face-blur/rulesets`.
2. Default branch back: `gh api -X PATCH repos/EladKaufman105/face-blur -f default_branch=main`
3. Old workflows back: on a branch from `main`, run `git revert -m 1 <merge commit of Phase 1>`, open a PR into `main` and merge it. With the rules off you may push straight to `main` if it's urgent. CI then behaves as before (pushes to `main` and `labs` release).
4. Leave `develop` and any `v*` tags in place. They do no harm.

## Appendix A: things not done here, on purpose

- The release signing key (`app/faceblur.jks`) and its passwords are in the repo. Moving them to Actions secrets is a separate task.
- Stale branches `opencv-min`, `ci-log`, `ci-results-labs` and `labs` are kept, by the owner's choice.
- No paid tools: no Copilot review. PR review is done by a Claude reviewer sub-agent, as CLAUDE.md describes.

## Appendix B: the same in Settings (when tools can't do it)

Guide Elad one click at a time. Wait for him after each step.

1. **Default branch:** Settings → General → Default branch → switch icon → `develop` → Update → confirm.
2. **Merge buttons:** Settings → General → Pull Requests:
   - "Allow merge commits": on.
   - "Allow squash merging": on.
   - "Allow rebase merging": off.
   - "Automatically delete head branches": off.
3. **Rulesets:** Settings → Rules → Rulesets → New ruleset → Import a ruleset. Pick one file from `.github/rulesets/` (download it from the repo first) → Create. Repeat for all 4 files.
   - Check that each one shows "Active" and that the Bypass list is empty.
4. **Label:** Issues → Labels → New label:
   - Name `needs-human`.
   - Description "Review loop hit 3 rounds; a person decides".

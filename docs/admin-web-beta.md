# Admin: turn on the beta web app (one time)

**For:** the repo owner's Claude agent. Elad is the only admin and uses the Claude app on his phone: ask him short yes/no questions, and for a tap in GitHub give one direct link and one step at a time.
**When:** after the PR that adds the beta web app is merged into `develop`.

## What changes

The web site moves from "GitHub Actions" Pages deploys to the `gh-pages` branch, which only CI writes (`ci/publish-pages.sh` from `.github/workflows/web.yml`):
- https://eladkaufman105.github.io/face-blur/ is the release web app, built from `main`. A push to `main` with web changes updates only it.
- https://eladkaufman105.github.io/face-blur/beta/ is "ONIT Beta", built from `develop`. A push to `develop` with web changes updates only it.
- `gh-pages` is always one commit, force-pushed by CI. Nobody edits it by hand.

## Steps

1. **Fill `gh-pages` (both apps).** Actions → "Web app" → Run workflow → branch `develop`, channel `both`.
   With `gh`: `gh workflow run web.yml --repo EladKaufman105/face-blur --ref develop -f channel=both`.
   The run first runs every web test on `develop` (the subtitles test downloads 1.4 GB and takes up to an hour), then the jobs `deploy (release)` (built from `main`) and `deploy (beta)` (built from `develop`). Wait until it's green. If a test job fails, re-run the failed jobs once; if it fails again, stop and tell Elad which job failed.
   Each deploy job ends with "Build GitHub Pages": a push with the workflow's token may not start a Pages build, so the job makes sure one runs and waits until it's live (up to about 12 minutes). Until step 2 is done, that step only warns ("doesn't deploy from a branch yet") and the run stays green.
   **Check:** `gh api repos/EladKaufman105/face-blur/contents/?ref=gh-pages --jq '.[].name'` lists `index.html`, `.nojekyll` and `beta`, and `gh api 'repos/EladKaufman105/face-blur/contents/beta?ref=gh-pages' --jq '.[].name'` lists `index.html`.
2. **Serve it.** Settings → Pages (https://github.com/EladKaufman105/face-blur/settings/pages) → Build and deployment → Source: **Deploy from a branch** → Branch **`gh-pages`**, folder **`/ (root)`** → Save.
   With `gh`: `gh api -X PUT repos/EladKaufman105/face-blur/pages -f build_type=legacy -f 'source[branch]=gh-pages' -f 'source[path]=/'`.
   GitHub's own "pages-build-deployment" run then publishes it. If that run fails with "not allowed to deploy to github-pages due to environment protection rules": Settings → Environments → `github-pages` → Deployment branches and tags → add `gh-pages` (https://github.com/EladKaufman105/face-blur/settings/environments), then re-run the failed "pages-build-deployment" run (or `gh api -X POST repos/EladKaufman105/face-blur/pages/builds`).
3. **Protect the branch from deletion.** The ruleset "data branches: no deletion" now also covers `gh-pages` (`.github/rulesets/data-branches.json` on `develop`; deletion only, since CI force-pushes it).
   With `gh`: `id=$(gh api repos/EladKaufman105/face-blur/rulesets --jq '.[] | select(.name=="data branches: no deletion") | .id')`, then `gh api -X PUT repos/EladKaufman105/face-blur/rulesets/$id --input .github/rulesets/data-branches.json` (from a checkout of `origin/develop`).
   Without `gh`: Settings → Rules → Rulesets → "data branches: no deletion" → Target branches → Add target → Include by pattern → `gh-pages` → Save.
4. **Check.**
   - https://eladkaufman105.github.io/face-blur/ opens ONIT, with no "Beta" anywhere.
   - https://eladkaufman105.github.io/face-blur/beta/ opens "ONIT Beta", with the BETA tag on the logo and the note "Beta: may have bugs".
   - The latest "pages-build-deployment" run in Actions is green.

From now on, after a merge into `main` or `develop` with web changes, its "Web app" run publishes that app and waits for GitHub Pages ("Build GitHub Pages" in the deploy job, then a green "pages-build-deployment" run).

Until the first release brings the new `web.yml` to `main`, a hotfix into `main` that changes web files runs `main`'s old web workflow, which deploys through GitHub Actions: with the source on the branch, it may fail or briefly replace the site. After such a hotfix, run "Web app" on `develop` with channel `both`.

## Rollback

Settings → Pages → Source: **GitHub Actions**. Then the site needs a new Actions deployment (until then, check what it serves):
- While `main` still has the old `web.yml` (before the first release with this change): Actions → "Web app" → Run workflow on **`main`**. It deploys the release only, with no beta.
- After that: revert the change with a normal PR into `develop` (and release it), then run "Web app" on `main`. The old workflow deploys only from `main`.

Leave the `gh-pages` branch in place (it does no harm).

# Releasing

The plugin is released to Maven Central as `io.github.damienmiheev:killbill-monobank-plugin` (an OSGi bundle jar,
with sources and a javadoc jar carrying `docs/design.md`). Kill Bill installs it from there:

```bash
kpm install_java_plugin monobank --group-id=io.github.damienmiheev --artifact-id=killbill-monobank-plugin --version=X.Y.Z
```

Only the maintainer releases. A release must not need a follow-up fix release, so **every step of the verification
is done twice**: the first pass by the maintainer, the second independently (a second person or a fresh review), on
the head of the release pull request, whose content is exactly what gets tagged. Nothing is published until both
passes are clean.

## One-time setup

1. Central Portal (https://central.sonatype.com): the namespace `io.github.damienmiheev` is verified (shared with the
   monobank SDK). Account → Generate User Token gives the username and password parts used below.
2. Repository settings: run `scripts/github-settings.sh` (needs `gh` logged in as the repository admin). It applies,
   and can re-apply:
   - no classic branch protection on `main` (rulesets replace it; a classic one cannot be bypassed);
   - rulesets from `.github/rulesets/`: `main` (no bypass for anyone: squash-merged pull requests with resolved
     conversations, required checks `check`, `analyze`, `analyze-actions` on the up-to-date branch, no high CodeQL
     alerts, signed commits, linear history, no force pushes or deletion), `main-review` (one code-owner approval;
     the admin may bypass it only when merging a pull request), `release-tags-create` (only the admin creates `v*`
     tags) and `release-tags-immutable` (nobody moves or deletes them; signed);
   - immutable releases, secret scanning with push protection, Dependabot alerts and security updates, private
     vulnerability reporting;
   - Actions: GitHub-owned actions plus the allow-listed ones, pinned to full commit SHAs, read-only `GITHUB_TOKEN`,
     outside contributors' workflows need approval;
   - environments: `maven-central` (only `v*` tags may deploy, the admin approves every run and cannot skip the
     approval) and `monobank-test`
     (only `main`; it runs the nightly live suite, so it has no reviewer).
3. Secrets of the `maven-central` environment (Settings → Environments, or `gh secret set NAME --env maven-central`):
   - `CENTRAL_USERNAME`, `CENTRAL_PASSWORD`: the Central Portal user token;
   - `GPG_SIGNING_KEY`: the ASCII-armored private key (`gpg --armor --export-secret-keys <KEY_ID>`);
   - `GPG_SIGNING_PASSWORD`: its passphrase.
   The public key is on a keyserver (`gpg --keyserver keys.openpgp.org --send-keys <KEY_ID>`).
4. Secrets of the `monobank-test` environment: `MONOBANK_ACQUIRING_TEST_TOKEN` (monobank's test token) and
   `MONOBANK_TEST_CARD` (`number|MM/YY|CVC` of a made-up Luhn-valid card; the test token takes any).

## Verification pass (run it twice)

On a clean checkout of the release pull request's head (version `X.Y.Z`, not a snapshot):

1. The monobank SDK is a release from Maven Central (`monobank-sdk.version` has no `-SNAPSHOT`, CI no longer runs
   `scripts/install-monobank-sdk.sh`), and its own release checklist ran against this plugin.
2. `./mvnw -B -Pdetekt clean verify` passes: ktlint, detekt, unit tests, and the integration tests against a real
   Kill Bill 0.24.22 and PostgreSQL with the bundle installed.
3. CI is green on the commit: `check`, `analyze`, `analyze-actions`.
4. The live suite against monobank's test merchant passes: Actions → "Live tests" (environment `monobank-test`), or
   by hand with `MONOBANK_ACQUIRING_TEST_TOKEN`, `MONOBANK_TOKEN_IS_TEST=1`, `MONOBANK_LIVE_PAY=auto`,
   `MONOBANK_TEST_CARD` set:
   ```bash
   ./mvnw -B -Plive verify -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=LivePayerIT,MonobankLiveIT
   ```
   It covers hosted and saved-card purchases, holds captured in part and voided, refunds in parts, card saving and
   removal. Note the invoice ids in the release notes draft.
5. Inspect what will be published: `./mvnw -B -Psonatype-oss-release -Dgpg.skip -DskipTests -DskipITs clean verify`,
   then `target/` has exactly the bundle jar, `-sources.jar` and `-javadoc.jar` (no test jars); the bundle's manifest
   imports only what
   Kill Bill 0.24 exports; the pom has name, description, URL, license, developer and SCM.
6. The bundle installs into a stock Kill Bill 0.24.22 from the built jar and the plugin starts (the integration tests
   do this; check their log for the plugin's start line).
7. README, `docs/design.md` and the CHANGELOG entry match the release.
8. A fresh-eyes review of the full diff since the previous release found nothing to fix.

## Release

`main` only accepts squash-merged pull requests and `v*` tags can't be moved, so:

1. Open a release pull request: `<version>X.Y.Z</version>` in `pom.xml`, the CHANGELOG `Unreleased` section moved
   under `X.Y.Z`, the version in the README's install example, and nothing else.
2. Run both verification passes on its head; fix anything in a new pull request first, then start over.
3. Squash-merge it (all required checks green). The merge commit on `main` has the same tree as the verified head.
4. Tag the merge commit and push the tag (only the maintainer can create `v*` tags):
   ```bash
   git fetch origin && git tag -s vX.Y.Z origin/main && git push origin vX.Y.Z
   ```
5. Actions → "Publish to Maven Central" → run on tag `vX.Y.Z`. Its `check` job (no secrets) refuses a snapshot, an
   SDK snapshot, a tag that doesn't match the version or isn't on `main`, and re-runs the build checks; then approve
   the `maven-central` environment for `publish`, which signs, uploads and stops: the deployment
   (`killbill-monobank-plugin X.Y.Z`) waits in the Central Portal.
6. Central Portal → Deployments: drop any older or failed deployment for this version, check the file list once more
   (bundle, sources, javadoc, each with its `.asc`, the pom), then **Publish**.
7. After it appears on https://repo1.maven.org/maven2/io/github/damienmiheev/killbill-monobank-plugin/ (can take
   ~30 min), create the GitHub release from the tag with the CHANGELOG entry and the bundle jar attached (releases
   are immutable once published, so check it before publishing).
8. Open a pull request setting the version to the next `-SNAPSHOT`.

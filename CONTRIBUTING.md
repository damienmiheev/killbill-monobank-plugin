# Contributing

Thank you for helping. This plugin moves real money, so changes are held to a high bar: every behaviour change
comes with tests, and the design document stays true.

## Before you start

- For anything larger than a small fix, open an issue first and describe the problem and the approach.
- Security problems: see [SECURITY.md](SECURITY.md), never a public issue.
- Read [docs/design.md](docs/design.md). A change to behaviour updates it in the same pull request.

## Development

- JDK 21 or newer, Docker (for the integration tests). The bundle itself is compiled for Java 11, the runtime
  of Kill Bill 0.24.
- `./mvnw verify` runs ktlint, the unit tests, builds the bundle and runs the integration tests, which boot a
  real Kill Bill and PostgreSQL in Testcontainers with the bundle baked into the image.
- `./mvnw -Pdetekt verify` also runs detekt. `./mvnw ktlint:format` fixes formatting.
- Integration tests are `*IT` classes; unit tests are `*Test`.

## Rules for code

- **Money safety first.** A monobank call whose outcome is unknown is never reported as failed and never retried
  blindly (see the state machine in the design). New money paths come with tests for timeouts, duplicates and
  reordering.
- **No secrets anywhere**: not in the repo, tests, logs, error messages, health output or plugin properties.
  Card tokens, merchant tokens, `X-Sign` values and webhook bodies are never logged.
- **Every Kill Bill parameter is nullable** in Kotlin overrides: Kill Bill passes nulls.
- Amounts are converted only through `Money`, with the currency's own exponent.
- Dependencies: Kill Bill's APIs are `provided`; anything embedded is private to the bundle. Keep the bundle
  small and prefer the JDK.
- Schema changes ship as a new `src/main/resources/migration/V<yyyyMMddHHmm>__<what>.sql`, plus the same change
  in `ddl-postgresql.sql`, plus `SchemaCheck.REQUIRED` (a test keeps them in step).

## Pull requests

- One topic per pull request, with a description of what changes and how it was tested.
- `main` is protected: changes land only through a pull request with one approving review from a code owner
  (approved after the last push, all threads resolved), green required checks (`check`, CodeQL `analyze` and
  `analyze-actions`, no high-severity code-scanning alerts), a linear history and signed commits (GitHub signs the squash merge).
  The maintainer may merge their own pull requests without a second reviewer, but never around the checks.
- Release tags (`v*`) are created only by the maintainer (signed); nobody, the maintainer included, can move or
  delete one, and published releases are immutable.
- No binaries, keys or files over 5 MB in the repository (checked in CI).
- Commits are squashed on merge; write the title as the changelog line, and add the entry to `CHANGELOG.md`.

## Releases

The release process arrives with the first release (0.1.0); until then nothing is published. The rule it will
follow: a release is tested top to bottom **twice** before it is published. The exact release-candidate commit
must pass the full suite against the fake monobank and the live suite against monobank's test token (a manually
started workflow in a protected environment), in two separate runs on fresh environments. Any failure means a new
candidate and both runs again. Only then is the tag pushed and the signed bundle published to Maven Central.

By contributing you agree that your contributions are licensed under the Apache License 2.0.

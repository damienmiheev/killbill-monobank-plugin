# Kill Bill monobank plugin: guide for agents

A Kill Bill 0.24 payment plugin for monobank acquiring, in Kotlin, built with Maven on `killbill-oss-parent`.
Public, Apache-2.0. Read [docs/design.md](docs/design.md) before changing behaviour; keep it true.

## Commands

- `./mvnw verify` (JDK 21+, Docker): ktlint, unit tests, bundle, integration tests on a real Kill Bill.
- `./mvnw -Pdetekt verify`: plus detekt. `./mvnw ktlint:format` to format.
- Run one IT: `./mvnw verify -Dit.test=PluginLifecycleIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false`.

## Layout

- `src/main/kotlin/.../monobank/`: `MonobankActivator` (OSGi entry), `config/` (system and tenant settings),
  `db/` (schema check), `money/` (the only amount conversion), `api/` (Kill Bill `PaymentPluginApi`),
  `http/` (plugin routes), `health/`.
- `src/main/resources/ddl-postgresql.sql`: the schema; changes also go into `migration/` and `SchemaCheck.REQUIRED`.
- `src/test/kotlin/.../it/KillBillStack.kt`: Kill Bill + PostgreSQL in Testcontainers, bundle baked into the image.

## Rules

- Never report an unknown monobank outcome as failed; never retry a money call blindly (design A4).
- Never log or return card tokens, merchant tokens, `X-Sign`, webhook bodies. `Secret` hides values in `toString`.
- Kotlin overrides of Kill Bill interfaces take nullable parameters.
- The bundle targets Java 11 (`-Xjdk-release=11`); tests target 21.
- Use the latest stable dependency versions that Kill Bill 0.24's runtime allows; Kill Bill's APIs stay `provided`.
- Every change: tests first for bugs (a failing test proves the bug), green `./mvnw verify`, a reviewed PR.
- Releases (from 0.1.0; the release workflow and live suite are not built yet): the full suite and the live
  suite pass twice on the release candidate before publishing.
- No secrets in the repo, ever. The live monobank test token will live only in a protected `monobank-test`
  environment, never available to pull-request workflows.

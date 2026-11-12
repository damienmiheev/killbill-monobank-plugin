# Changelog

All notable changes are listed here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and the project uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.1.0] - 2026-11-13

The first release, for Kill Bill 0.24.

### Added

- Built on the monobank SDK 1.0.0 from Maven Central (`io.github.damienmiheev:monobank-acquiring`).
- Release setup: the Publish workflow to Maven Central (manual, on a `v*` tag, `maven-central` environment; refuses
  snapshots and tags off main, re-runs the build checks before asking for approval, leaves the deployment for the
  maintainer to publish), `RELEASING.md` with the twice-run checklist, and `scripts/github-settings.sh` for the
  repository's rulesets, environments and security settings.
- Bundle skeleton for Kill Bill 0.24: activator, system and tenant settings (with `env:` secrets; a tenant's
  merchant-token alias must be bound to its API key), schema check on start (tables, columns and the unique indexes
  money safety relies on), health check, PostgreSQL schema with database-level guards against a second unresolved
  charge or refund, money conversion by each currency's ISO 4217 exponent.
- Payments: hosted PURCHASE and AUTHORIZE (with card saving), saved-card charges, CAPTURE, VOID, full and partial
  REFUND in UAH only (other currencies refused with `CURRENCY_NOT_SUPPORTED`), baskets for fiscal receipts and split
  payments.
- Idempotency per Kill Bill transaction, unknown outcomes kept UNKNOWN and resolved from monobank (status, statement),
  a database guard against a second charge while one is unresolved.
- monobank's webhook (signature over the raw bytes, deduplication, forward-only updates), settling Kill Bill through
  the payment control plugins so invoices are paid, the cancel and public-key routes, and a reconcile job.
- Encrypted card tokens bound to their payment method, removal at monobank on delete.

[Unreleased]: https://github.com/damienmiheev/killbill-monobank-plugin/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/damienmiheev/killbill-monobank-plugin/releases/tag/v0.1.0

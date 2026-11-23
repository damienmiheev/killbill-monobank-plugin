# Changelog

All notable changes are listed here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and the project uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- Tenant setting `basketFromInvoice=true`: a payment of a Kill Bill invoice without the caller's `basketOrder` (auto-pay,
  Kill Bill's retries, `POST /invoices/{id}/payments`) carries one receipt item named after what the invoice charges
  (the plan's catalogue `prettyName`, else its name; an external charge's description), quantity 1, priced at the
  amount, with the tenant's item code and split receiver; it is also the page text unless `destination` is passed.
  When the invoice can't be read or used (another account's, another currency, no charge line), the default item is
  sent, a warning is logged and the health counter `basketFallback` grows. Off by default.
- A part capture or refund of a charge with the invoice's item needs no `basketOrder`: the plugin sends that item with
  quantity = amount ÷ its price (at most 3 decimals; other amounts are refused before monobank is called,
  `INVALID_BASKET`). Charges with the default item or the caller's items work as before.
- The property `amountStep` (minor units) on a hold, purchase or capture with the default item (1) or the invoice's
  item: a part capture or refund must be a multiple of it. For every tenant with baskets.

### Changed

- Item names are cut so that a surrogate pair is never split (still at most 128 UTF-16 characters).

## [0.1.4] - 2026-11-21

### Added

- The property `state`: the plugin's own state (`SENDING`, `PENDING`, `UNKNOWN`, `REVIEW`, `SUCCESS`, `FAILED`,
  `CANCELED`) of every transaction the plugin stored (not ones it refused before calling monobank, which have no
  plugin info), in plugin info (`GET /1.0/kb/payments/{id}?withPluginInfo=true`) and in the answers to payment calls.
  It can differ from Kill Bill's status after a hand fix. The plugin already refused to refund a FAILED purchase that
  was fixed to SUCCESS in Kill Bill; `state` (still `FAILED`) now makes that visible to the caller. A REVIEW row fixed
  in Kill Bill reads `REVIEW` until the reconcile job closes it. The rule: a payment is refundable only when its
  PURCHASE (or, for a hold, its CAPTURE) carries `state` = `SUCCESS`; a missing `state` or any other value is not
  refundable.

## [0.1.3] - 2026-11-20

### Added

- `GET /plugins/killbill-monobank/pubkey?refresh=true`: re-reads monobank's webhook key before answering (at most
  once a minute per merchant token, every 10 s while reads fail) and answers 503 when it can't be re-read now. A
  forwarder that checks `X-Sign` itself calls it after a failed check, so a key rotation is picked up without a
  webhook reaching the plugin or a restart. A forwarder should answer monobank 5xx, never drop, a webhook that
  still fails after a refresh (design A7).

### Changed

- Built on the monobank SDK 1.1.0 (`WebhookVerifier.refreshPublicKey()`; also fixes a webhook verification answering
  "invalid signature" instead of "key unavailable" in a race during the key fetch back-off).

## [0.1.2] - 2026-11-13

The first published release, for Kill Bill 0.24. (0.1.0 and 0.1.1 were tagged but never published: their uploads
failed at signing and at the Central Portal's validation.)

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

[Unreleased]: https://github.com/damienmiheev/killbill-monobank-plugin/compare/v0.1.4...HEAD
[0.1.4]: https://github.com/damienmiheev/killbill-monobank-plugin/releases/tag/v0.1.4
[0.1.3]: https://github.com/damienmiheev/killbill-monobank-plugin/releases/tag/v0.1.3
[0.1.2]: https://github.com/damienmiheev/killbill-monobank-plugin/releases/tag/v0.1.2

# Kill Bill monobank plugin

[![CodeQL](https://github.com/damienmiheev/killbill-monobank-plugin/actions/workflows/codeql.yml/badge.svg?branch=main)](https://github.com/damienmiheev/killbill-monobank-plugin/actions/workflows/codeql.yml)
[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/damienmiheev/killbill-monobank-plugin/badge)](https://scorecard.dev/viewer/?uri=github.com/damienmiheev/killbill-monobank-plugin)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

A [Kill Bill](https://killbill.io) payment plugin for [monobank](https://monobank.ua) internet acquiring (Ukraine).

> **Status: 0.1.0-SNAPSHOT, not released yet.** Every operation below is built and tested against a real Kill Bill and
> the SDK's fake monobank. The release follows the live tests against monobank's test merchant (see CONTRIBUTING).

It maps Kill Bill's payment API onto monobank with **one Kill Bill transaction per real monobank operation**:

| Kill Bill | monobank |
|---|---|
| PURCHASE with a saved card | `POST /api/merchant/wallet/payment` (debit) |
| PURCHASE / AUTHORIZE with `hosted=true` | `POST /api/merchant/invoice/create`, returns PENDING with `formUrl`; optionally saves the card |
| AUTHORIZE with a saved card | `wallet/payment` with `paymentType=hold` |
| CAPTURE | `POST /api/merchant/invoice/finalize` |
| VOID (an uncaptured hold only) | `POST /api/merchant/invoice/cancel` without an amount |
| REFUND, full or partial | `POST /api/merchant/invoice/cancel` with `amount` and `extRef` |
| Saved cards | Kill Bill payment methods backed by monobank wallet tokens |
| `getPaymentInfo` | refreshed from `GET /api/merchant/invoice/status`, so Kill Bill's Janitor can settle anything |

What it is built around:

- **No double charges.** monobank has no idempotency key, so the plugin keys every call by the Kill Bill
  transaction, never retries a call whose outcome is unknown (it becomes `UNKNOWN` and is resolved from monobank),
  and refuses a new charge on an account while an earlier one is unresolved.
- **No lost payments.** Webhooks are the fast path; `getPaymentInfo`, a reconcile job and Kill Bill's Janitor
  are the safety net. Hosted payments complete their Kill Bill invoice payment.
- **Hryvnia only.** monobank converts USD and EUR to hryvnia when they are paid and cannot refund such saved-card
  payments in full, so the plugin takes UAH only and refuses any other currency (`CURRENCY_NOT_SUPPORTED`) before
  calling monobank. Accounts in other currencies use another payment plugin.
- **Card data stays at monobank.** Customers enter cards on monobank's page; the plugin keeps only monobank's
  token, encrypted, and never returns or logs it.

The full design, including the state machine and the threat model, is in [docs/design.md](docs/design.md).

## Requirements

| Plugin | Kill Bill | Java (runtime) | Database |
|---|---|---|---|
| 0.1.x | 0.24.x (tested on 0.24.22) | 11 (Kill Bill 0.24's) | PostgreSQL |

A monobank acquiring merchant token. Card saving (tokenisation) must be switched on for the merchant by monobank.

## Install

1. Apply the schema to Kill Bill's database: [`src/main/resources/ddl-postgresql.sql`](src/main/resources/ddl-postgresql.sql)
   (later versions add `migration/V…sql` files). The plugin never creates tables and refuses to start without them.
2. Put the bundle at `/var/lib/killbill/bundles/plugins/java/killbill-monobank/<version>/killbill-monobank-<version>.jar`.
   Bake it into your Kill Bill image (owned by Kill Bill's user): Kill Bill recreates its plugins directory on
   first boot, so a file copied into a container before its first start is lost.
3. Set the system settings below and restart Kill Bill.
4. Upload each tenant's settings (below).

### System settings

Kill Bill properties, or `KB_…` environment variables (`KB_org_killbill_billing_plugin_monobank_kbUsername`, …).
Any value may be `env:NAME` to read it from the environment.

| Key (`org.killbill.billing.plugin.monobank.` …) | Required | Meaning |
|---|---|---|
| `kbUsername`, `kbPassword` | yes | A dedicated Kill Bill user whose role has only `payment:transition`, used to settle pending payments. Never `admin`. |
| `cardKeys` | yes | `id:base64key[,id:base64key…]`, 32-byte AES keys for card tokens. The first encrypts new tokens; older ids stay readable. |
| `tokenHmacKey` | yes | 32-byte base64 key used to recognise the same card saved twice. |
| `merchantTokenBindings` | no | `apiKey:alias[,…]`: lets the tenant with that Kill Bill API key use `merchantToken=env:<alias>`. |
| `notificationSharedSecret` | no | If set (at least 32 characters), webhook calls must carry it in `X-Notification-Secret` (for setups that forward monobank's webhook). |
| `apiBaseUrl` | no | Default `https://api.monobank.ua`. https only; plain http only for local and test hosts. |
| `readTimeoutSeconds` | no | Default 15, at most 25 (Kill Bill gives a plugin call 30 s). |
| `reconcileIntervalSeconds` | no | Default 300. |
| `statementMinIntervalSeconds` | no | Default 60. |

The plugin refuses to start, listing every problem, when a required setting is missing or invalid.

### Tenant settings

```bash
curl -u admin:password \
  -H "X-Killbill-ApiKey: $API_KEY" -H "X-Killbill-ApiSecret: $API_SECRET" \
  -H "X-Killbill-CreatedBy: admin" -H "Content-Type: text/plain" \
  --data-binary @monobank.properties \
  http://localhost:8080/1.0/kb/tenants/uploadPluginConfig/killbill-monobank
```

```properties
# monobank.properties
org.killbill.billing.plugin.monobank.merchantToken=env:shop
org.killbill.billing.plugin.monobank.webhookUrl=https://example.com/webhooks/monobank
```

| Key | Required | Meaning |
|---|---|---|
| `merchantToken` | yes | monobank's `X-Token`, or `env:<alias>` to read `MONOBANK_MERCHANT_TOKEN_<ALIAS>` from Kill Bill's environment and keep the token out of the database. The alias must be bound to this tenant's API key in `merchantTokenBindings`, so a tenant can never use another tenant's token. |
| `webhookUrl` | yes | The public URL monobank calls. Route it to `POST /plugins/killbill-monobank/notification`. |
| `redirectUrl` | no | Where monobank's page sends the customer back. |
| `destination` | no | Default text on monobank's page and receipt. |
| `language` | no | `uk` (default) or `en`. |
| `checkoutValiditySeconds` | no | Lifetime of a hosted page. Default 3600. |
| `basketItemCode` | no | When set, every payment carries a one-item basket (`merchantPaymInfo.basketOrder`) with this product code, unless the caller passes its own. Merchants with fiscal receipts or split payments need a basket. |
| `splitReceiverId` | no | The split receiver every basket item goes to (merchants with split payments). Implies a basket. |

There is no global fallback: a tenant without valid settings cannot make payments and reports unhealthy.

## Using it

Plugin properties (`pluginProperty=key=value` on Kill Bill's payment calls):

| Property | Meaning |
|---|---|
| `hosted=true` | Pay on monobank's page. The transaction is PENDING; read `formUrl` from the transaction's plugin properties (`GET /1.0/kb/payments/{id}?withPluginInfo=true`) and send the customer there. Without it, the payment method's saved card is charged; with no saved card the charge is declined (`NO_ACTIVE_CARD`), never turned into a page nobody sees. |
| `saveCard=true` | With `hosted=true`: save the card on the payment method (the customer must agree). |
| `redirectUrl`, `destination`, `validitySeconds` | Overrides for one hosted payment. |
| `callerRef` | Your own id for the payment (e.g. a checkout id); stored and returned as a property. |
| `basketOrder` | A JSON array of `{name, qty, sum, code}` (plus `splitReceiverId`, `unit`, `tax`, `uktzed`) adding up to the amount. |
| `initiationKind=client` | A saved-card charge made with the customer present (default `merchant`). |

Transactions return `formUrl`, `tdsUrl` (3-D Secure), `invoiceId`, `expiresAt`, `callerRef` and `reference` as plugin
properties.

Routes under `/plugins/killbill-monobank/`:

| Route | Auth | What |
|---|---|---|
| `POST /notification` | monobank's signature (and `X-Notification-Secret` if configured) | monobank's webhook. |
| `POST /payments/{paymentId}/cancel` | Kill Bill credentials with `payment:transition` | Gives up an unpaid hosted page: 200 when cancelled, 409 when the customer paid or is paying, 503 when monobank has not settled the page yet (ask again), 404 for a payment of another tenant. |
| `GET /pubkey` | Kill Bill credentials | monobank's current webhook public key, for a forwarder that verifies before forwarding. |
| `GET /healthcheck` | none | Database, schema, reconcile job; with Kill Bill credentials also the counters, with tenant headers the tenant's settings. |

## Health

`GET /plugins/killbill-monobank/healthcheck` (and Kill Bill's `/1.0/healthcheck`). With tenant API key headers it
also reports whether that tenant's settings are valid. It is unhealthy when the database or schema is missing, the
tenant's settings are invalid, or the reconcile job has not run for three intervals. The counters an operator alerts
on (`stateConflict`, `unexpectedReversal`, `amountMismatch`, `unknownClosed`, `signatureFailed`, `holdsNearExpiry`,
`operationStale`)
are shown only to authenticated Kill Bill callers. It never calls monobank and never returns secrets.

## Payments that need an operator

- **REVIEW**: monobank reported a different amount or currency than Kill Bill asked for, or held a purchase. The
  Kill Bill transaction stays `UNKNOWN` and the plugin stores monobank's invoice id and status. Fix the transaction
  in Kill Bill (admin API); the reconcile job then closes the plugin's row in the same state.
- **state_conflict**: Kill Bill and monobank disagree on a final state, or monobank reports success for a charge the
  plugin had closed as failed. Nothing is overwritten; the counter rises and the log names the transaction.
- **unexpected_reversal**: a refund or chargeback the plugin did not start (made in monobank's portal).
- **holdsNearExpiry**: holds made more than 7 days ago that are neither captured nor voided; monobank releases them
  at 9.
- **operationStale**: a capture, void or refund monobank has not finished after 24 hours; it is held for review
  because it blocks the next operation on that payment.

## Security

Kill Bill does not authenticate plugin routes, so do not expose Kill Bill itself to the internet; route only the
webhook. See the threat model in [docs/design.md](docs/design.md) and report vulnerabilities as described in
[SECURITY.md](SECURITY.md).

## Building and testing

Needs JDK 21 or newer (the bundle is compiled for Java 11) and Docker for the integration tests.

```bash
./mvnw verify            # unit tests, ktlint, the bundle, integration tests against a real Kill Bill
./mvnw -Pdetekt verify   # plus detekt
```

The integration tests boot Kill Bill 0.24.22 and PostgreSQL in Testcontainers with the built bundle baked into the
image. See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

[Apache License 2.0](LICENSE). Not affiliated with monobank or Universal Bank.

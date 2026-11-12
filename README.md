# Kill Bill monobank plugin

A [Kill Bill](https://killbill.io) payment plugin for [monobank](https://monobank.ua) internet acquiring (Ukraine).

> **Status: under construction (0.1.0-SNAPSHOT).** The bundle loads, checks its schema and settings and reports
> health. Payment operations are being implemented one by one and are refused (`PLUGIN_FAILURE`,
> `NOT_IMPLEMENTED`) until then. Do not use it for real money before 0.1.0 is released.

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

What it is designed around (being built; see the status above):

- **No double charges.** monobank has no idempotency key, so the plugin keys every call by the Kill Bill
  transaction, never retries a call whose outcome is unknown (it becomes `UNKNOWN` and is resolved from monobank),
  and refuses a new charge on an account while an earlier one is unresolved.
- **No lost payments.** Webhooks are the fast path; `getPaymentInfo`, a reconcile job and Kill Bill's Janitor
  are the safety net. Hosted payments complete their Kill Bill invoice payment.
- **Multi-currency.** Any ISO 4217 currency the merchant's monobank contract allows, with each currency's own
  minor unit (never an assumed 2 decimals).
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
| `readTimeoutSeconds` | no | Default 15. |
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
org.killbill.billing.plugin.monobank.allowedCurrencies=UAH
```

| Key | Required | Meaning |
|---|---|---|
| `merchantToken` | yes | monobank's `X-Token`, or `env:<alias>` to read `MONOBANK_MERCHANT_TOKEN_<ALIAS>` from Kill Bill's environment and keep the token out of the database. The alias must be bound to this tenant's API key in `merchantTokenBindings`, so a tenant can never use another tenant's token. |
| `webhookUrl` | yes | The public URL monobank calls. Route it to `POST /plugins/killbill-monobank/notification`. |
| `redirectUrl` | no | Where monobank's page sends the customer back. |
| `destination` | no | Default text on monobank's page and receipt. |
| `language` | no | `uk` (default) or `en`. |
| `allowedCurrencies` | no | Comma-separated ISO 4217 codes the merchant's contract allows. Default `UAH`. |
| `checkoutValiditySeconds` | no | Lifetime of a hosted page. Default 3600. |

There is no global fallback: a tenant without valid settings cannot make payments and reports unhealthy.

## Health

`GET /plugins/killbill-monobank/healthcheck` (and Kill Bill's `/1.0/healthcheck`). With tenant API key headers it
also reports whether that tenant's settings are valid. It never calls monobank and never returns secrets.

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

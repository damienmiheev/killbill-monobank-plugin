<!-- Kept in step with the code: a change to behaviour updates this document in the same pull request. -->

# Design

## A1. What it is

`killbill-monobank` is a Kill Bill payment plugin for monobank's internet acquiring (Ukraine).

- **Repo:** `damienmiheev/killbill-monobank-plugin`, Apache-2.0 (Kill Bill's licence).
- **Artifact:** `io.github.damienmiheev:killbill-monobank-plugin` on Maven Central, an OSGi bundle jar.
- **Name in Kill Bill:** `killbill-monobank`.
- **Package:** `io.github.damienmiheev.killbill.monobank`.

It maps Kill Bill's payment API onto monobank with **one Kill Bill transaction per real monobank operation**. There are no hidden payments, no authorisations made up on the side and no money state outside a Kill Bill transaction.

| Kill Bill | monobank | Notes |
|---|---|---|
| PURCHASE with a saved card | `POST /wallet/payment`, `paymentType=debit` | Merchant-initiated by default (`initiationKind=merchant`). |
| PURCHASE with `hosted=true` | `POST /invoice/create`, `paymentType=debit`, optional `saveCardData` | Returns PENDING with `formUrl`. A webhook or a poll settles it. |
| AUTHORIZE (hosted or with a saved card) | the same calls with `paymentType=hold` | monobank keeps a hold for 9 days. |
| CAPTURE | `POST /invoice/finalize` on the AUTHORIZE's invoice | Amount ≤ the hold. monobank finalises once. |
| VOID | `POST /invoice/cancel` on the AUTHORIZE's invoice, no amount | Only for a held AUTHORIZE that was not captured. Any other VOID is refused ("use refund"). |
| REFUND (full or partial) | `POST /invoice/cancel` with `amount` and `extRef` | Asynchronous: PENDING until `cancelList` shows the result. |
| Cancel an unpaid hosted page | `POST /invoice/remove` | Plugin endpoint (A7). Kill Bill has no VOID for a PENDING PURCHASE. |
| CREDIT, CHARGEBACK | not supported | CREDIT is refused. A reversal monobank reports that the plugin did not start is recorded and alerted (A7). |
| Payment methods | monobank wallet card tokens | The token never leaves the plugin (A8). |
| `getPaymentInfo` | local state, refreshed from `GET /invoice/status` when not final | Kill Bill's Janitor and `GET /payments/{id}?withPluginInfo=true` use it (A6). |

**Hosted vs saved card** is the caller's explicit choice (`hosted=true`), never inferred from the payment method. A PURCHASE without `hosted=true` on a payment method with no active card fails as a decline (`NO_ACTIVE_CARD`, PAYMENT_FAILURE). That way Kill Bill's auto-pay, run with no customer present, can never open a payment page nobody will see.

**Not in v1:**
- monobank's "Регулярні платежі" API, split payments, QR;
- `buildFormDescriptor`: hosted pages are PENDING transactions instead, so every page belongs to a transaction;
- search APIs and refresh-from-gateway (they serve Kaui; empty results);
- MySQL (PostgreSQL only; A3);

Kill Bill drives recurring billing; the plugin only moves money.

**Multi-currency.** Every payment carries its own currency from Kill Bill, and the plugin supports any ISO 4217 currency that monobank accepts for the merchant:
- The currency is sent as its ISO 4217 numeric code (`ccy`), taken from `java.util.Currency`, not from a hand-written table.
- Amounts are converted with the currency's own minor-unit exponent (`getDefaultFractionDigits`: 2 for UAH, USD and EUR, 0 for JPY, 3 for KWD), never an assumed 2. An amount with more decimals than its currency allows is refused (CANCELED), never rounded.
- A tenant lists the currencies its monobank contract allows (`allowedCurrencies`, default `UAH`). Anything else is refused before calling monobank, never silently charged in hryvnia.
- Holds: monobank documents and enforces UAH-only holds for payment pages (live: `1001` "hold payments are allowed only for UAH"), so a hosted AUTHORIZE in another currency is refused before any call. For a saved card monobank documents no limit, so a hold in EUR is sent and monobank's answer decides.
- CAPTURE, VOID and REFUND must use the currency of the transaction they act on. A webhook or status whose `ccy` differs from the row's goes to REVIEW.
- Saved cards are not tied to a currency: one card can be charged in any allowed currency.
- Whether monobank accepts a currency for a given merchant (and for tokenised charges) is a matter of the merchant's contract. The plugin reports monobank's refusal as a decline with its code.

## A2. Platform, language, build

**Platform.**
- Kill Bill **0.24.22**, the current stable release (image `killbill/killbill:0.24.22`).
- Libraries: `killbill-oss-parent` 0.146.87, `killbill-api` 0.54.0, `killbill-plugin-api` 0.27.3, `killbill-base-plugin` 5.1.12 (for `PaymentApiWrapper` and `PluginProperties`).
- The 0.24 image runs Java 11, so the bundle targets **Java 11** bytecode.
- Kill Bill 0.25 (JDK 21) has no release or image yet. The plugin follows it in a minor version.

**Kotlin**, compiled for JVM 11. Why:
- The monobank SDK is Kotlin, so the Kotlin standard library is in the bundle anyway.
- Null safety and exhaustive `when` over sealed types make the status mapping (A4) a total function the compiler checks. That mapping is where the plugin this replaces had most of its bugs.
- It is the owner's language, the same as the SDK's.

Costs, accepted:
- Kill Bill's own plugins are Java, so contributors expect Java.
- **Every Kill Bill parameter is declared nullable in the overrides.** Kill Bill passes nulls (properties, offsets, contexts), and a non-null Kotlin parameter would throw inside Kill Bill's threads.
- `jackson-module-kotlin` (and `kotlin-reflect`) is used only if the SDK needs it.

**Maven**, with `killbill-oss-parent` as the parent so that every Kill Bill dependency has the version Kill Bill ships. The hello-world plugin and the official plugins build the same way.
- Plugins: `kotlin-maven-plugin`, plus `maven-bundle-plugin` for the OSGi manifest.
- Embedded privately: the SDK, Kotlin and Jackson.
- `Import-Package`: excludes the embedded packages (`!kotlin.*`, `!com.fasterxml.*`, `!org.jetbrains.annotations`). Imports SLF4J `[1.7,2)`, Kill Bill APIs and Joda from Kill Bill. Everything else is `resolution:=optional`.
- Slice 1's harness proves the bundle resolves inside a real Kill Bill.

**Checks:** detekt and ktlint (the SDK's configs), the parent's dependency checks, and `mvn verify` with unit and Testcontainers tests.

**Plain JDBC** with a small helper, not jOOQ and not `PluginPaymentDao`. The base DAO is built around a "responses log" table, which is the wrong shape for a state machine. Each change that touches several rows runs in one DB transaction.

## A3. Data model (PostgreSQL)

**Database and schema.**
- PostgreSQL only in v1. Most new Kill Bill installs use it, and it gives partial unique indexes and `RETURNING`. MySQL support is welcome as a contribution; the SQL stays portable where that costs nothing.
- Files: `src/main/resources/ddl-postgresql.sql`, then `migration/V<yyyyMMddHHmm>__<what>.sql` for changes. This is the layout `kpm migrations` and Kill Bill's Flyway tool expect.
- Kill Bill doesn't create plugin tables and the plugin doesn't either. The operator applies them. On start the plugin **checks** its tables and columns and refuses to start if they are missing.

**Values.**
- Money: minor units (`BIGINT`) of the row's currency plus its ISO 4217 alpha code. It is converted to and from Kill Bill's `BigDecimal` in one place, using the currency's exponent (A1).
- Times: UTC `TIMESTAMPTZ` from Kill Bill's clock.

**`monobank_transactions`** holds one row per Kill Bill transaction. It is the state store.

| Column | Notes |
|---|---|
| `record_id` | PK |
| `kb_tenant_id`, `kb_account_id`, `kb_payment_id`, `kb_payment_method_id`, `kb_invoice_id` | `kb_invoice_id` comes from Kill Bill's `IPCD_INVOICE_ID` property when the payment pays an invoice. |
| `kb_transaction_id` | **UNIQUE**. The idempotency key. |
| `transaction_type` | AUTHORIZE, PURCHASE, CAPTURE, VOID, REFUND |
| `reference` | **UNIQUE**. A random UUID made with the row and sent as `merchantPaymInfo.reference` (charges) or `extRef` (refunds, voids). It is opaque: no Kill Bill IDs or customer data leave the plugin. |
| `caller_ref` | The caller's own ID for this payment (property `callerRef`, e.g. a checkout ID). Stored and returned as a property. |
| `invoice_id` | monobank's invoice. CAPTURE, VOID and REFUND carry their parent's. Partial unique index on (`invoice_id`) where the type is AUTHORIZE or PURCHASE. |
| `amount_minor`, `currency` | What Kill Bill asked for. |
| `processed_amount_minor` | What monobank reports for this operation. A refund never changes its parent's value. |
| `state` | `SENDING`, `PENDING`, `UNKNOWN`, `SUCCESS`, `FAILED`, `CANCELED`, `REVIEW` (A4) |
| `kb_settled_at` | When Kill Bill was last brought in line with a final state. Null means there is still work to do (A6). |
| `mono_status`, `mono_modified_at` | The raw monobank status and `modifiedDate` of the last applied event. |
| `error_code`, `error_message` | monobank's `errCode` / `failureReason`, or the plugin's own code. Returned as `gatewayErrorCode` / `gatewayError`. |
| `form_url`, `tds_url`, `expires_at` | For hosted pages and 3-D Secure. |
| `save_card` | Whether this hosted payment asked monobank to save the card. |
| `attempted_at`, `next_check_at`, `created_at`, `updated_at` | `next_check_at` is always set while the row is open; the reconcile job's queue. |

**`monobank_payment_methods`** holds one row per Kill Bill payment method.

| Column | Notes |
|---|---|
| `kb_payment_method_id` | UNIQUE |
| `kb_tenant_id`, `kb_account_id` | |
| `wallet_id` | monobank `walletId`: the Kill Bill account ID, a random UUID with no personal data. One customer's cards share one wallet. |
| `card_token_ciphertext`, `card_token_key_id` | The token, encrypted (A8). Null until monobank reports `walletData.status = created`. |
| `card_token_hmac` | HMAC-SHA256 of the token. Used to find the same card saved twice without decrypting. |
| `card_status` | `NONE`, `PENDING`, `ACTIVE`, `FAILED`, `DELETED` |
| `source_invoice_id` | The hosted invoice the card came from, so a lost card webhook can be polled (A6). |
| `masked_pan`, `card_brand`, `card_country` | For display. |
| `created_at`, `updated_at` | |

There is no `is_default`: Kill Bill owns the default payment method.

**`monobank_notifications`** records every verified webhook.

| Column | Notes |
|---|---|
| `body_sha256` | **UNIQUE**. A byte-identical redelivery is a duplicate. Different bodies for the same invoice (a card status change, a `cancelList` change) are all processed. |
| `kb_tenant_id`, `reference`, `invoice_id`, `mono_status`, `mono_modified_at` | |
| `received_at`, `outcome` | `APPLIED`, `STALE`, `IGNORED` |

The body itself is not stored, because it contains the card token. Rows older than the configured retention (default 400 days) are pruned by the reconcile job.

## A4. Idempotency and the state machine

monobank documents **no idempotency key**. Two calls with the same `reference` or `extRef` create two operations. So idempotency is the plugin's job.

**Every money-moving call:**

1. **Guard.** For AUTHORIZE and PURCHASE, refuse when the same account has a charge row in `SENDING`, `UNKNOWN` or `REVIEW`. The answer is CANCELED, code `PREVIOUS_CHARGE_UNRESOLVED`. The guard is a **partial unique index** (`kb_account_id` where the type is a charge and the state is unresolved), so the claim INSERT in step 2 *is* the check: two concurrent charges on one account can never both pass, even across Kill Bill nodes.
   - This is needed because Kill Bill starts a **new transaction** (a new ID) for a new invoice payment or the next auto-pay, even while an earlier charge's outcome is unknown. Kill Bill's own invoice control code notes that this can double-charge.
   - For REFUND, the same guard applies per parent invoice (a second partial unique index).
2. **Claim.** Insert the row as `SENDING` with a new `reference`, in its own committed DB transaction.
   - If a row for this `kb_transaction_id` already exists, **don't call monobank**. Return the row's state, after refreshing it from monobank if it is not final.
   - This covers Kill Bill retrying a call, a restart mid-call and the Janitor.
3. **Call** monobank once. The read timeout defaults to 15 s. The SDK never retries a POST.
4. **Record** the result with a conditional, forward-only update. Then **return the row's state as it is after the update**, never the raw response. A webhook may have settled the row already.

**What each outcome becomes:**

| Outcome | Row | Kill Bill status |
|---|---|---|
| `success` (PURCHASE, CAPTURE, REFUND), `hold` (AUTHORIZE) | SUCCESS | PROCESSED |
| `created` / `processing`, a hosted `formUrl`, or a `tdsUrl` | PENDING | PENDING |
| `failure` or `expired`, a card decline, `TOKEN_NOT_FOUND`, `NO_ACTIVE_CARD` | FAILED | **ERROR → PAYMENT_FAILURE**, with the code |
| The request was refused for our own reasons: HTTP 4xx validation, currency not allowed, more decimals than the currency allows, `PREVIOUS_CHARGE_UNRESOLVED`, missing config | CANCELED | **CANCELED → PLUGIN_FAILURE** |
| The request **was not sent**: connect refused, DNS or TLS handshake failure | CANCELED | CANCELED → PLUGIN_FAILURE (safe to retry) |
| The request **may have been sent** with no answer: read timeout, reset after the write, HTTP 5xx | UNKNOWN | **UNDEFINED → UNKNOWN**, never a failure |
| The amount or currency monobank reports doesn't match the row | REVIEW | UNDEFINED → UNKNOWN, plus an alert |

`TOKEN_NOT_FOUND` also marks the payment method's card `FAILED`, so later charges fail fast without calling monobank.

**Exits from states that are not final:**
- **SENDING** older than the timeout plus 1 minute (a crash between claim and call) is treated as UNKNOWN without an invoice ID. `getPaymentInfo` reports SENDING as UNDEFINED.
- **UNKNOWN with an invoice ID** is settled by `invoice/status`.
- **UNKNOWN without an invoice ID:** monobank has no lookup by reference, so the reconcile job reads `GET /api/merchant/statement` for a window around `attempted_at` and matches `reference` on our side.
  - Found: the row takes that invoice and its status.
  - Not found: the row **stays UNKNOWN**. It is re-checked at 15 minutes, 1 hour and 6 hours. At 24 hours with no trace it becomes FAILED and raises `unknown_closed`, so an operator double-checks it.
  - Meanwhile the guard in step 1 blocks a second charge.
  - The charge's own webhook still matches by `reference`, because the row exists before the call. That resolves most cases long before the statement does.
  - Statement calls are made only for these rows, one window per tenant per run, at most once a minute.
- **UNKNOWN refunds and voids** follow the same rule, matched by `extRef` in `cancelList`.
- **PENDING captures, voids and refunds** that monobank has not finished after 24 hours go to REVIEW and raise `operation_stale`: they would otherwise block the next operation on that payment without anyone noticing.
- **REVIEW** waits for an operator. Kill Bill's `fixPaymentTransactionState` (staff, through the caller's admin) closes it; the reconcile job reads that Kill Bill status and closes the plugin row in the same state (settled). The row keeps monobank's invoice id and status so the operator can find the payment.
- **WRONG_CANCEL_AMOUNT** on a refund or void is a rejection: the row is CANCELED (nothing was cancelled by this call). The refund guard counts only refunds in SUCCESS, PENDING, SENDING, UNKNOWN or REVIEW, and a refund whose earlier attempt is still unresolved blocks a new one on the same invoice, so a retry can never refund twice.

**Forward only.** SENDING → PENDING/UNKNOWN → SUCCESS/FAILED/REVIEW. Updates are guarded by `WHERE state IN (…)` and by `mono_modified_at`. A late synchronous response never overwrites a newer webhook, and `processing` never overwrites `success`. When evidence of success arrives for a FAILED row (only possible after `unknown_closed`), the row is not changed. It raises `state_conflict` for an operator, who refunds or fixes it.

## A5. Flows

### Hosted payment, optionally saving the card

1. The caller adds a payment method for this plugin. It has no card yet (`card_status = NONE`). Plugin properties that try to set a token are **ignored**: a token only ever comes from monobank.
2. The caller makes a PURCHASE (or AUTHORIZE) with `hosted=true`. To pay a Kill Bill invoice, it calls `POST /invoices/{id}/payments`, so Kill Bill's invoice payment control runs. Properties:
   - `hosted=true` (required for this flow);
   - `redirectUrl` (falls back to tenant config);
   - `saveCard` (default **false**: card-on-file needs the customer's consent, so the caller passes `true` when it has it);
   - `callerRef` (optional, stored and returned);
   - `destination` (the text on monobank's page and receipt) and `language` (`uk` or `en`, default `uk`);
   - `validitySeconds` (default from config, 3600; monobank's own default is 24 h);
   - `basketOrder` (optional JSON for monobank's fiscal receipts; its total must equal the amount).
3. The plugin claims the row, calls `invoice/create` (debit or hold, `saveCardData{saveCard, walletId}`, `validity`, `webHookUrl` from tenant config) and returns **PENDING** with the properties `formUrl`, `invoiceId`, `expiresAt` and `callerRef`. The caller sends the customer to `formUrl`.
4. monobank reports the result by webhook (A7) or the plugin polls it (A6).
   - `success` (or `hold` for AUTHORIZE) moves the row to SUCCESS.
   - Separately, `walletData.status = created` stores the encrypted token on that payment method and marks it ACTIVE. The card result may arrive in its own webhook, so the two are applied independently.
5. **Settling in Kill Bill** (A6) completes the PENDING transaction **through its payment control plugins**. For an invoice payment that means the invoice is paid and `INVOICE_PAYMENT_SUCCESS` fires. The plain `notifyPendingTransactionOfStateChanged` would leave the invoice unpaid, so the plugin never uses it.
6. **Expiry.** monobank sends no webhook for an expired page.
   - After `expires_at` plus 5 minutes, the reconcile job calls `invoice/remove` and then `invoice/status`.
   - The row becomes FAILED (`expired`) **only when monobank reports `expired`, `failure` or a removed invoice**, never from the local clock alone.
   - A customer who paid in the last second therefore stays SUCCESS. Kill Bill then gets PAYMENT_FAILURE and the invoice stays unpaid.
7. **Cancel early** (the customer abandons the checkout): A7's cancel endpoint.

### Charge a saved card (renewals, any later payment)

1. A PURCHASE without `hosted=true` needs an ACTIVE card on the payment method. If there is none, the answer is ERROR `NO_ACTIVE_CARD` (PAYMENT_FAILURE, so the caller's dunning applies).
2. The plugin calls `wallet/payment` with the decrypted token and `initiationKind = merchant`. Property `initiationKind=client` is for when the customer is present.
3. The response is often final. `processing` returns PENDING and the webhook settles it.
4. A `tdsUrl` returns PENDING with `tdsUrl` as a property. The caller decides whether to send it to the customer. After `validitySeconds` the same remove-then-confirm rule as for expiry applies.

### Capture, void, refund

- **CAPTURE** finds the AUTHORIZE row of the same Kill Bill payment. It must be SUCCESS (held), not yet captured and younger than 9 days. The plugin calls `finalize` (amount ≤ hold) and maps the response through A4. The reconcile job alerts on holds older than 8 days.
- **VOID** of a held, uncaptured AUTHORIZE calls `cancel` without an amount, which releases the hold. Any other VOID is refused (CANCELED, "use refund"), so a void can never refund captured money.
- **CAPTURE and REFUND carry receipt items** (`items`): the caller's `basketOrder` for this operation, or one item for the amount with the tenant's `splitReceiverId`. monobank refuses a partial finalize without them on a merchant with receipts or split payments (seen live: `1001`). A purchase split between several receivers needs the caller's `basketOrder` on each refund, naming who gives the money back.
- **REFUND** calls `cancel` with `amount` (partial or full) and `extRef` = the refund row's `reference`. The response (`processing`, `success`, `failure`) maps through A4. A pending refund is settled from the `cancelList` entry carrying our `extRef`.

### Payment methods

- **Details and lists** return the masked PAN, brand, country and card status as properties. `externalPaymentMethodId` is an opaque plugin ID, **never the token**.
- **Delete** marks the row DELETED. The plugin then removes the card at monobank (`DELETE /wallet/card`), unless another live payment method has the same `card_token_hmac`. A failed remote delete is retried by the reconcile job.
- **Making the saved card the account's default** is left to the caller (one Kill Bill call when it sees the payment succeed). That keeps the plugin's Kill Bill permissions to one (A7).

## A6. Settling, getPaymentInfo, the reconcile job

**`getPaymentInfo`** returns one entry per Kill Bill transaction of the payment, built from `monobank_transactions`.
- Rows that are PENDING, or UNKNOWN with an invoice ID, are first refreshed from `invoice/status` through the same forward-only update a webhook uses.
- Kill Bill calls it from its Janitor and whenever a payment is read with `withPluginInfo=true`. A lost webhook is therefore always recoverable by reading the payment.

**Settling in Kill Bill** happens when a row is final (SUCCESS or FAILED) but `kb_settled_at` is null. A final state returned to Kill Bill in the synchronous answer counts as settled at once (`kb_settled_at` is set with it), so only asynchronous results wait in this queue.
1. Read the Kill Bill transaction.
2. **PENDING:** call `PaymentApiWrapper.transitionPendingTransaction` from the plugin framework. It calls `notifyPendingTransactionOfStateChangedWithPaymentControl` with the payment's control plugins, so the invoice payment completes. This needs the Kill Bill permission `payment:transition` (verified in the 0.24 sources).
3. **UNKNOWN:** `notifyPending…` refuses anything that is not PENDING (verified). So the plugin calls `getPayment(paymentId, withPluginInfo=true)`, which runs Kill Bill's on-the-fly Janitor. The Janitor reads `getPaymentInfo` and repairs the transaction. Slice 2 proves that this path also completes the invoice payment; if it doesn't, the fallback is Kill Bill's admin fix for UNKNOWN.
4. **Already in the same final state:** just record `kb_settled_at`.
5. **The opposite final state:** don't overwrite. Raise `state_conflict`.
6. Kill Bill's error **codes** are used, never message text.

Slice 2 also tries settling everything through the on-the-fly Janitor. If that completes invoice payments for PENDING too, the plugin can drop its Kill Bill user entirely, and the design gets simpler.

**The reconcile job** runs every 5 minutes on each Kill Bill node. Every update is conditional and forward-only, and settling first checks Kill Bill's state, so two nodes doing the same row is harmless. There are no row locks and no lock is held across HTTP calls. It handles:
- rows that are not final, older than 2 minutes, due by `next_check_at`: refresh from `invoice/status`, or from the statement for UNKNOWN without an invoice ID (A4);
- rows that are final but not settled: settle;
- hosted pages and 3-D Secure past their expiry: remove, then confirm (A5);
- cards still PENDING whose `source_invoice_id` is final: poll `invoice/status` for `walletData`;
- failed remote card deletions: retry;
- old notification rows: prune.

Calls to monobank back off on HTTP 429. Statement reads are single-flight per tenant.

## A7. Plugin HTTP endpoints and webhooks

Kill Bill serves plugin routes under `/plugins/killbill-monobank/` on its own port. **Kill Bill authentication is optional there** (verified: `KillBillShiroWebModule` puts `/plugins/**` behind an optional-auth filter), so each route decides for itself. There are three routes, and nothing else.

### `POST /notification`: monobank's webhook

It uses its own route, not Kill Bill's `/1.0/kb/paymentGateways/notification`, because that route drops request headers and `X-Sign` would never arrive. The plugin never assumes it is reachable from the internet. The operator decides how monobank's calls reach it (directly, through a proxy, or forwarded by an application) and sets the public URL as `webhookUrl` in tenant config. monobank tries **at most 3 times** and doesn't guarantee order, so this is the fast path and A6 is the safety net.

1. If the operator set `notificationSharedSecret`, require a matching `X-Notification-Secret` header (constant-time compare). This is the cheap gate for a forwarding setup. Then read the **raw bytes** of the body (max 64 KB) and `X-Sign`.
2. Parse `invoiceId`, `reference`, `status` and `modifiedDate` as strings. An unknown status stays raw text and is never dropped as unparseable.
3. Find the row by `reference`, or the payment method by `source_invoice_id` for a card-only event.
   - Unknown → 200, ignored. It may belong to another system on the same merchant.
   - Known reference whose `invoice_id` doesn't match → 200, ignored, counted.
4. Verify `X-Sign` (ECDSA over SHA-256 of the raw bytes) with **that row's tenant's merchant key**.
   - On failure, refetch the key once and verify again. Refetching is limited to once a minute per merchant, as monobank advises.
   - Still failing → **401**, counted as `notification_signature_failed`. Not 200: a genuine webhook signed with a key monobank has just rotated is then sent again, by when the key may be refetched. The 401 does tell a prober that the invoice or reference is known here (unknown ones get 200); references are random UUIDs, so this is accepted.
5. Insert into `monobank_notifications` (unique on the body hash).
   - A duplicate whose row is final and settled → 200, done.
   - A duplicate whose row is not yet settled → go on to step 7.
6. If a row for this invoice or reference is still SENDING (its own call is running), answer **503** without applying: monobank sends it again after the call has answered. Otherwise, in one DB transaction: apply the invoice status to the row (forward-only, `modifiedDate` monotonic), `walletData` to the payment method, and `cancelList` entries to refund rows. Each part applies on its own, even when another is stale.
7. If the row is final and not settled, settle it (A6). Skip this while the row is SENDING, because the synchronous call in progress will return the row's state to Kill Bill itself.
8. Answer **200** when the event was applied, was a duplicate or was deliberately ignored, **401** for a failed shared secret or signature, **503** for transient failures (database unavailable, monobank's key unavailable). A row that was still SENDING when the webhook arrived is not settled here: its own call answers Kill Bill.

**Reversals.** Each `cancelList` entry must match a refund or void row by `extRef`. An entry with no match is a cancel the plugin didn't start (a chargeback, or a cancel in monobank's portal). It is recorded and raises `unexpected_reversal`. The PURCHASE row stays SUCCESS, and v1 doesn't create a Kill Bill chargeback by itself.

### `POST /payments/{kbPaymentId}/cancel`: give up an unpaid hosted page

- **Requires Kill Bill authentication** (Basic auth plus the tenant's API key and secret). The route checks that the Shiro subject is authenticated and has `payment:transition`, and that the payment belongs to that tenant.
- It calls `invoice/remove`, then `invoice/status`.
  - **Removed or expired:** the row becomes FAILED (`expired`, or `CANCELED_BY_MERCHANT` when monobank no longer finds the removed invoice) and is settled, so Kill Bill shows PAYMENT_FAILURE and the invoice stays unpaid. Answer 200 with the final state.
  - **The customer paid at the same moment** (status `processing`, `success` or `hold`; `remove` refuses a paid invoice): nothing is cancelled. Answer **409** with the live state. The payment then settles normally, and the caller must treat it as paid.
  - **Anything else** (monobank did not answer, or the page is in no state that says either): **503**, ask again.
  - Settling runs on the reconcile job's thread: the settler logs in as the plugin's own Kill Bill user, which must not replace the caller's session.
- It is idempotent: a second call returns the current state.

### `GET /healthcheck`

The database is reachable, the tables are present and the reconcile job has run within three intervals. With a tenant it also reports whether the config is valid. Counters, the last monobank call and the last verified webhook (from memory) and the config's errors are shown only to authenticated Kill Bill callers; Kill Bill's own `/1.0/healthcheck` gets none of them. It never calls monobank per probe and never returns secrets or customer data.

**The plugin's Kill Bill user.** Settling a PENDING transaction needs a Shiro subject with `payment:transition`, and webhooks and the reconcile job arrive without one. The plugin logs in as a **dedicated Kill Bill user whose role has only `payment:transition`**, given in system config, never `admin`. It logs out in `finally` on every thread. Without this user the plugin refuses to start. (See A6 for possibly dropping it.)

## A8. Card data

- Card numbers never reach the plugin. Card entry happens on monobank's page, and monobank returns a token, a masked PAN, the brand and the country.
- **The token** is encrypted with AES-256-GCM:
  - a random 96-bit nonce per encryption;
  - additional data = `tenant_id|kb_payment_method_id`, so a token copied to another row doesn't decrypt;
  - a key ID per ciphertext. Rotation means adding a new key: old IDs stay readable, and new writes use the new key. Retiring a key is an operator step, re-encrypting with a one-off command shipped with the plugin.
- The token is decrypted only in memory, right before `wallet/payment` or `DELETE /wallet/card`. It is never returned through a Kill Bill API, plugin property, log line, health output or error.
- Webhook bodies are not stored, because they contain the token.
- A token taken from the database is useless without the encryption key. Even with the key, it only works with the merchant's API token, which the recommended setup keeps out of the database (A9).

## A9. Configuration

**Per tenant**, uploaded with Kill Bill's `uploadPluginConfig` for `killbill-monobank`. The prefix is `org.killbill.billing.plugin.monobank.`

| Key | Required | Notes |
|---|---|---|
| `merchantToken` | yes | monobank's `X-Token`. Either the value itself (stored in Kill Bill's database) or `env:<alias>`, which reads the environment variable `MONOBANK_MERCHANT_TOKEN_<ALIAS>` of the Kill Bill process, **only if the operator bound that alias to this tenant** (system setting `merchantTokenBindings`). A tenant can't point the plugin at any other variable or at another tenant's token, and the error is the same whether such a variable exists or not. |
| `webhookUrl` | yes | The public URL monobank calls. Without it the plugin refuses to create monobank payments. |
| `redirectUrl`, `destination`, `language` | no | Defaults for hosted pages (`language` `uk`). |
| `allowedCurrencies` | no | Comma-separated ISO 4217 codes the merchant's monobank contract allows. Default `UAH`. Anything else is refused before calling monobank. |
| `checkoutValiditySeconds` | no | Default 3600. |

There is no global fallback token. A tenant without valid config can't make payments and reports unhealthy.

**Plugin system properties**, in Kill Bill's properties or `KB_…` environment:

| Key | Notes |
|---|---|
| `…monobank.kbUsername` / `kbPassword` | The plugin's own Kill Bill user (A7). |
| `…monobank.cardKeys` | `id:base64key[,id:base64key…]`, the first one used for new writes, or `env:NAME`. Required. |
| `…monobank.tokenHmacKey` | Base64 key for `card_token_hmac`, or `env:NAME`. Required. |
| `…monobank.merchantTokenBindings` | `apiKey:alias[,…]`: which `env:` alias each tenant (named by its Kill Bill API key, chosen before the tenant exists) may use. |
| `…monobank.notificationSharedSecret` | Optional (A7), at least 32 characters. |
| `…monobank.reconcileIntervalSeconds`, `readTimeoutSeconds`, `statementMinIntervalSeconds` | Tuning. `readTimeoutSeconds` is at most 25: Kill Bill gives a plugin call 30 s. |
| `…monobank.apiBaseUrl` | Default `https://api.monobank.ua`. Must be https; plain http only for local and test hosts (`localhost`, `*.internal`, `*.local`, `*.test`), where tests run a fake. |

## A10. Threat model (plugin)

| Asset | Threat | Mitigation |
|---|---|---|
| Money | Double charge after a timeout or retry | A4: one monobank call per Kill Bill transaction; an unknown outcome is UNKNOWN, never FAILED; a new charge on the account is refused while one is unresolved. |
| Money | A forged webhook marks an unpaid payment as paid | Signature with the tenant's merchant key over the raw body; the invoice ID must match the row; amount and currency are checked (REVIEW on mismatch). |
| Money | A replayed genuine webhook | Deduplicated by body hash; forward-only state; settling checks Kill Bill's state first. |
| Money | An API caller fakes success through plugin properties | No property can mark anything paid or set a token. Success comes only from monobank: a response, a verified webhook or a status poll. |
| Money | VOID used as a hidden refund | VOID only releases an uncaptured hold. |
| Money | A paid page cancelled | `remove` refuses a paid invoice; the cancel endpoint answers 409 and the payment settles. Expiry is decided by monobank's status, not the local clock. |
| Money | A paid invoice left unpaid in Kill Bill | Settling goes through the payment control plugins (A6); there is a test asserting the invoice balance reaches 0. |
| Card token | Leaks through Kill Bill APIs, logs, backups | A8: encrypted and bound to its row, never returned or logged, bodies not stored. |
| Merchant token | Leaks from Kill Bill's database or backups | `env:<alias>` keeps it out of the database; never logged; health shows only "configured". |
| Plugin routes | Called by anyone who can reach Kill Bill's port | Unauthenticated routes are only `notification` (signature, optional shared secret, 64 KB limit) and `healthcheck` (no data). `cancel` requires Kill Bill authentication. Operators shouldn't expose Kill Bill publicly. |
| Kill Bill | The plugin's user misused | Its role has only `payment:transition`; credentials are system config, not tenant config. |
| Cross-tenant | A webhook applied to another tenant, or a tenant using another's token | The tenant comes from the row found by the opaque reference, verified with that tenant's key, and the invoice ID must match. `env:` reads only `MONOBANK_MERCHANT_TOKEN_<alias>` and only for the alias the operator bound to that tenant's API key. |

**Logs** carry Kill Bill IDs, `reference`, `invoiceId`, statuses, amounts and error codes. They never carry the card token, the merchant token, `X-Sign`, a webhook body or a masked PAN.

## A11. Observability

Counters and gauges go into Kill Bill's metrics registry (`/1.0/metrics`):
- calls by operation and outcome;
- `unknown_open` and `pending_open` by age;
- `notification_signature_failed`;
- `state_conflict`, `unexpected_reversal`, `review_open`, `unknown_closed`, `hold_near_expiry`;
- the reconcile job's last run.

Alerts an operator should wire:
- any UNKNOWN or REVIEW older than 1 hour;
- any `state_conflict`, `unexpected_reversal` or `unknown_closed`;
- signature failures above zero for 15 minutes;
- the reconcile job not having run for 15 minutes.

## A12. Tests

**Unit tests** (JUnit 5):
- the A4 mapping table, as data;
- the forward-only rules and every exit rule;
- amount and currency conversion for every exponent (UAH, USD, EUR, JPY, KWD), including refusal of extra decimals;
- the order of the webhook steps;
- signature verification with a generated EC key pair (real ECDSA, no mock);
- `cancelList` matching per entry;
- encryption with additional data, including the row-swap case;
- the `env:` prefix guard.

**Integration tests** (Testcontainers, on every PR, one Kill Bill container per suite):
- **Setup:** `killbill/killbill:0.24.22` with the built bundle in `/var/lib/killbill/bundles/plugins/java/killbill-monobank/<version>/`, Postgres from `killbill/postgresql:0.24` plus the plugin's DDL.
- **A fake monobank** with its own EC key. It serves `pubkey`, `invoice/create`, `remove`, `status`, `finalize`, `cancel`, `wallet/payment`, `wallet`, `wallet/card` and `statement`, and sends signed webhooks. It can delay, drop, duplicate, reorder and time out. It is published as a test artifact so callers can test against it.
- **Driven through Kill Bill's REST API** (the Java client).

Cases:
- a hosted invoice payment with card saving, settled by webhook: **the invoice balance is 0 and `INVOICE_PAYMENT_SUCCESS` fires**; then a saved-card renewal;
- the same, settled by `GET /payments/{id}?withPluginInfo=true` with no webhook;
- the same, settled by the reconcile job alone;
- the same, settled by Kill Bill's own Janitor with webhooks and the job off (short `janitor.*.retries` in the container);
- auto-pay on a payment method without a card → PAYMENT_FAILURE `NO_ACTIVE_CARD`, and no invoice at the fake;
- **a timeout after the charge reached monobank** → UNKNOWN → found by the statement or the webhook → SUCCESS in Kill Bill, with exactly one charge at the fake;
- a second invoice payment while one charge is UNKNOWN → refused, still one charge;
- a timeout before sending → PLUGIN_FAILURE, retry works;
- Kill Bill killed between claim and call → the SENDING row is resolved;
- the webhook arrives during the synchronous call, and after it;
- duplicate and reordered webhooks;
- Kill Bill unavailable during the settle, then a duplicate webhook → still settled;
- the card webhook carries the same status and `modifiedDate` as the payment webhook → the card still becomes ACTIVE;
- a lost card webhook → the card becomes ACTIVE through polling;
- the page paid right at expiry → SUCCESS, never FAILED;
- cancel endpoint: unpaid → FAILED; paid at the same moment → 409 and SUCCESS; without Kill Bill auth → 401;
- authorize → capture; authorize → void; capture after 9 days → error;
- partial and full refunds; a refund timeout; a retry answered `WRONG_CANCEL_AMOUNT`;
- a decline; 3-D Secure pending then expired;
- a bad signature; key rotation; an unknown reference; a mismatched invoice ID;
- an amount mismatch → REVIEW;
- payment method deletion, including the same card saved twice;
- an unexpected reversal;
- two Kill Bill nodes running the reconcile job at once → one charge, one settle;
- payments in UAH and USD on one account, and a refund in the wrong currency refused;
- a tenant without config.

**Live tests against monobank's test mode.** monobank gives a test token, so the whole plugin is also tested against the real API, and production only changes the token.
- **One code path.** Test and production differ only in configuration: the token and, where needed, the base URL. There are no test-only branches, flags or mocks inside the plugin.
- **The token is configuration only.** It is never in the repo. It lives in a GitHub environment secret (`monobank-test`, with required reviewers) and, for the owner's use, in the password manager or Vault.
- **Where they run:** a separate workflow, `live.yml`, started by hand (`workflow_dispatch`) and before each release. It is never triggered by `pull_request`, so fork PRs can't reach the secret. It runs the same Testcontainers Kill Bill with the plugin pointed at monobank's test API.
- **Webhooks in the live job:** monobank must reach the plugin. The job opens a short-lived tunnel to the test container's notification route (for example a Cloudflare quick tunnel) and sets it as `webhookUrl`. Without a tunnel, the job proves the polling path (`getPaymentInfo`, reconcile) and skips the webhook cases.
- **What the live job covers:** a hosted payment with card saving (the payment page is completed by a headless browser with monobank's test card), a saved-card charge, a partial and a full refund, a hosted page cancelled, a statement lookup, and webhook signature verification with monobank's real key.
- It is not documented whether tokenisation works with the test token (to ask monobank support). monobank also has to switch tokenisation on for the merchant.

## A13. Repository and releases (public)

- **Files:** `README.md` (the mapping, install, config, the threat model in short, a Kill Bill version matrix), `CONTRIBUTING.md`, `SECURITY.md`, `CHANGELOG.md`, `LICENSE` (Apache-2.0), and `docs/` with this Part A.
- **CI** on GitHub Actions: build, unit and integration tests, detekt/ktlint, actionlint. Actions pinned to SHAs, `permissions: {}`. PR CI uses only the fake monobank and needs no secrets. The test token is used only by `live.yml` (A12), behind a protected environment. No secret is ever in the repo.
- **Releases:** a tag publishes the signed bundle to Maven Central (Sonatype Central Portal), with a GitHub release carrying the jar's SHA-256. Semantic versions.
- **Release gate: tested top to bottom twice before anything is published.** The exact release-candidate commit must pass, in two separate runs on fresh environments (new containers, new database, new test tenant): the whole PR suite against the fake monobank, and the whole live suite against monobank's test token (`live.yml`, with webhooks through the tunnel). Only then is the tag pushed and the bundle published. A failure in either run blocks the release and is fixed in a new candidate, which starts both runs again. A patch release right after a release is a failure of this gate, not routine.

-- Kill Bill monobank plugin schema (PostgreSQL).
-- Applied by the operator; the plugin checks it on start and never creates or alters tables itself.
-- Later changes ship as migration/V<yyyyMMddHHmm>__<what>.sql.

create table monobank_transactions (
    record_id              bigserial primary key,
    kb_tenant_id           uuid         not null,
    kb_account_id          uuid         not null,
    kb_payment_id          uuid         not null,
    kb_payment_method_id   uuid,
    kb_invoice_id          uuid,
    kb_transaction_id      uuid         not null,
    transaction_type       varchar(16)  not null,
    reference              uuid         not null,
    caller_ref             text,
    invoice_id             text,
    amount_minor           bigint       not null,
    currency               char(3)      not null,
    processed_amount_minor bigint,
    state                  varchar(16)  not null,
    kb_settled_at          timestamptz,
    mono_status            text,
    mono_modified_at       timestamptz,
    error_code             text,
    error_message          text,
    form_url               text,
    tds_url                text,
    expires_at             timestamptz,
    save_card              boolean      not null default false,
    attempted_at           timestamptz,
    next_check_at          timestamptz,
    created_at             timestamptz  not null,
    updated_at             timestamptz  not null,
    constraint monobank_transactions_kb_transaction_id_uq unique (kb_transaction_id),
    constraint monobank_transactions_reference_uq unique (reference),
    constraint monobank_transactions_type_ck
        check (transaction_type in ('AUTHORIZE', 'PURCHASE', 'CAPTURE', 'VOID', 'REFUND')),
    constraint monobank_transactions_state_ck
        check (state in ('SENDING', 'PENDING', 'UNKNOWN', 'SUCCESS', 'FAILED', 'CANCELED', 'REVIEW')),
    constraint monobank_transactions_amount_ck check (amount_minor >= 0),
    constraint monobank_transactions_processed_amount_ck check (processed_amount_minor >= 0),
    constraint monobank_transactions_currency_ck check (currency ~ '^[A-Z]{3}$')
);
-- One monobank invoice belongs to at most one charge; captures, voids and refunds share their parent's invoice.
create unique index monobank_transactions_charge_invoice_uq
    on monobank_transactions (invoice_id)
    where invoice_id is not null and transaction_type in ('AUTHORIZE', 'PURCHASE');
create index monobank_transactions_payment_idx on monobank_transactions (kb_payment_id);
create index monobank_transactions_invoice_idx on monobank_transactions (invoice_id);
-- The guard against a second charge while one is unresolved (design A4). Unique, so the claim INSERT itself is the
-- guard: two concurrent charges on one account cannot both pass a check-then-insert.
create unique index monobank_transactions_unresolved_charge_uq
    on monobank_transactions (kb_account_id)
    where transaction_type in ('AUTHORIZE', 'PURCHASE') and state in ('SENDING', 'UNKNOWN', 'REVIEW');
-- The same guard for refunds, per monobank invoice.
create unique index monobank_transactions_unresolved_refund_uq
    on monobank_transactions (invoice_id)
    where transaction_type = 'REFUND' and state in ('SENDING', 'UNKNOWN', 'REVIEW');
-- The reconcile job's work queues: open rows by due time (next_check_at is always set while a row is open), and
-- final rows Kill Bill has not been brought in line with yet.
create index monobank_transactions_open_idx
    on monobank_transactions (next_check_at)
    where state in ('SENDING', 'PENDING', 'UNKNOWN');
create index monobank_transactions_unsettled_idx
    on monobank_transactions (record_id)
    where state in ('SUCCESS', 'FAILED') and kb_settled_at is null;

create table monobank_payment_methods (
    record_id             bigserial primary key,
    kb_tenant_id          uuid         not null,
    kb_account_id         uuid         not null,
    kb_payment_method_id  uuid         not null,
    wallet_id             text         not null,
    card_token_ciphertext bytea,
    card_token_key_id     text,
    card_token_hmac       bytea,
    card_status           varchar(16)  not null,
    source_invoice_id     text,
    masked_pan            text,
    card_brand            text,
    card_country          text,
    created_at            timestamptz  not null,
    updated_at            timestamptz  not null,
    constraint monobank_payment_methods_kb_payment_method_id_uq unique (kb_payment_method_id),
    constraint monobank_payment_methods_card_status_ck
        check (card_status in ('NONE', 'PENDING', 'ACTIVE', 'FAILED', 'DELETED')),
    constraint monobank_payment_methods_token_ck
        check ((card_token_ciphertext is null) = (card_token_key_id is null)
            and (card_token_ciphertext is null) = (card_token_hmac is null)),
    constraint monobank_payment_methods_active_ck
        check (card_status <> 'ACTIVE' or card_token_ciphertext is not null)
);
create index monobank_payment_methods_account_idx on monobank_payment_methods (kb_account_id);
create index monobank_payment_methods_hmac_idx on monobank_payment_methods (card_token_hmac)
    where card_token_hmac is not null;
create index monobank_payment_methods_source_invoice_idx on monobank_payment_methods (source_invoice_id)
    where source_invoice_id is not null;

create table monobank_notifications (
    record_id        bigserial primary key,
    body_sha256      bytea        not null,
    kb_tenant_id     uuid,
    reference        uuid,
    invoice_id       text,
    mono_status      text,
    mono_modified_at timestamptz,
    received_at      timestamptz  not null,
    outcome          varchar(16)  not null,
    constraint monobank_notifications_body_sha256_uq unique (body_sha256),
    constraint monobank_notifications_body_sha256_ck check (octet_length(body_sha256) = 32),
    constraint monobank_notifications_outcome_ck check (outcome in ('APPLIED', 'STALE', 'IGNORED'))
);
create index monobank_notifications_received_idx on monobank_notifications (received_at);

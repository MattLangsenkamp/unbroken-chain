-- V1: GitHub Gateway baseline schema
--
-- Replaces the previous OAuth-token-centric schema. Models four aggregates:
--   * installation       — a live GitHub App installation we know about
--   * linked_repo        — a repository selected for an installation (FK + cascade)
--   * pending_link_flow  — an in-flight link attempt (state nonce row, swept after expiry)
--   * webhook_delivery   — idempotency log keyed by GitHub's X-GitHub-Delivery UUID
--
-- Identifiers we own (installation.id, linked_repo.id, pending_link_flow.state) are UUIDs
-- generated app-side. Third-party identifiers GitHub assigns (gh_installation_id, account_id,
-- gh_repository_id) keep GitHub's integer type. delivery_id is GitHub's UUID.

CREATE TABLE installation (
    id                   UUID         PRIMARY KEY,
    gh_installation_id   BIGINT       NOT NULL UNIQUE,
    account_login        TEXT         NOT NULL,
    account_id           BIGINT       NOT NULL,
    account_type         TEXT         NOT NULL CHECK (account_type IN ('User', 'Organization')),
    status               TEXT         NOT NULL CHECK (status IN ('Active', 'Suspended')),
    installed_at         TIMESTAMPTZ  NOT NULL
);

CREATE TABLE linked_repo (
    id                   UUID         PRIMARY KEY,
    installation_id      UUID         NOT NULL REFERENCES installation(id) ON DELETE CASCADE,
    gh_repository_id     BIGINT       NOT NULL,
    full_name            TEXT         NOT NULL,
    UNIQUE (installation_id, gh_repository_id)
);

CREATE TABLE pending_link_flow (
    state                UUID         PRIMARY KEY,
    created_at           TIMESTAMPTZ  NOT NULL,
    expires_at           TIMESTAMPTZ  NOT NULL
);

CREATE TABLE webhook_delivery (
    delivery_id          UUID         PRIMARY KEY,
    event_type           TEXT         NOT NULL,
    received_at          TIMESTAMPTZ  NOT NULL,
    outcome              TEXT         NOT NULL CHECK (outcome IN ('Processed', 'Ignored', 'Duplicate', 'Failed'))
);

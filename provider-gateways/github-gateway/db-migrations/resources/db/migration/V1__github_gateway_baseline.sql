-- V1: GitHub Gateway baseline schema
--
-- Replaces the previous OAuth-token-centric schema. Models four aggregates:
--   * installation       — a live GitHub App installation we know about
--   * linked_repo        — a repository selected for an installation (FK + cascade)
--   * pending_link_flow  — an in-flight link attempt (state nonce row, swept after expiry)
--   * webhook_delivery   — idempotency log keyed by GitHub's X-GitHub-Delivery UUID

CREATE TABLE installation (
    id                   BIGSERIAL    PRIMARY KEY,
    gh_installation_id   BIGINT       NOT NULL UNIQUE,
    account_login        TEXT         NOT NULL,
    account_id           BIGINT       NOT NULL,
    account_type         TEXT         NOT NULL CHECK (account_type IN ('User', 'Organization')),
    status               TEXT         NOT NULL CHECK (status IN ('Active', 'Suspended')),
    installed_at         TIMESTAMPTZ  NOT NULL
);

CREATE TABLE linked_repo (
    id                   BIGSERIAL    PRIMARY KEY,
    installation_id      BIGINT       NOT NULL REFERENCES installation(id) ON DELETE CASCADE,
    gh_repository_id     BIGINT       NOT NULL,
    full_name            TEXT         NOT NULL,
    UNIQUE (installation_id, gh_repository_id)
);

CREATE TABLE pending_link_flow (
    state                TEXT         PRIMARY KEY,
    created_at           TIMESTAMPTZ  NOT NULL,
    expires_at           TIMESTAMPTZ  NOT NULL
);

CREATE TABLE webhook_delivery (
    delivery_id          TEXT         PRIMARY KEY,
    event_type           TEXT         NOT NULL,
    received_at          TIMESTAMPTZ  NOT NULL,
    outcome              TEXT         NOT NULL CHECK (outcome IN ('Processed', 'Ignored', 'Duplicate', 'Failed'))
);

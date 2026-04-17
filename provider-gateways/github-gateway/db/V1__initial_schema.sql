-- V1: github_tokens table
--
-- Stores one OAuth token per GitHub user. The token is upserted on every
-- OAuth callback (ON CONFLICT user_id DO UPDATE), so user_id is the natural
-- unique key. id is a surrogate key used by TokenId in the domain layer.
--
-- scopes is stored as a comma-separated string; the Magnum DbCodec for
-- List[TokenScope] in PrivateMagnumCodecs handles serialisation/deserialisation.

CREATE TABLE github_tokens (
    id         BIGSERIAL    PRIMARY KEY,
    user_id    TEXT         NOT NULL UNIQUE,
    token      TEXT         NOT NULL,
    scopes     TEXT         NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ
);

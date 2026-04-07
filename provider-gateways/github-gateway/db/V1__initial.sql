CREATE TABLE github_creds (
    id      BIGSERIAL    PRIMARY KEY,
    name    VARCHAR(255) NOT NULL UNIQUE,
    secret  BYTEA        NOT NULL
);

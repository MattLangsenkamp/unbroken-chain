# GitHub Gateway — Linking Spec

## 1. Overview

The GitHub Gateway is a microservice that manages the connection between our platform and GitHub. It handles linking GitHub installations, storing the resulting installation references, ingesting webhooks, minting installation access tokens on demand, and tearing down links when we're done with them.

This milestone covers local development only. No backward compatibility is required; the Flyway baseline is replaced from scratch.

## 2. Goals

- Let a caller initiate a GitHub App installation flow and complete it via callback.
- Persist the resulting installation and its repository list.
- Expose read APIs to list linked organizations and repositories.
- Ingest GitHub webhooks to keep our view in sync with GitHub's truth.
- Expose an unlink operation that tears down an entire installation on both sides.
- Support multiple concurrent linking flows without cross-contamination.
- Survive abandoned linking attempts without poisoning future attempts.

## 3. Non-Goals

- **User-level OAuth** (`ghu_` tokens, "act as user" calls). The gateway only acts as the GitHub App.
- **GitHub Enterprise Server** support. github.com only.
- **Per-repo permission negotiation.** Permissions are whatever the App manifest on GitHub declares.
- **Manifest flow / programmatic GitHub App creation.** The App is created manually once per deployment; its credentials are loaded from k8s secrets. Adding the manifest flow is a future milestone and must not require schema changes to this one.
- **Authentication and authorization** on the gateway's own endpoints. Deferred; all callers are treated as superadmin for now.
- **Tenant / multi-owner scoping** of linked installations. Deferred; linked installations are global within a deployment. A future migration will introduce an owner column.
- **Key rotation automation.** The gateway loads one private key at startup; rotation requires a pod restart.
- **Observability** beyond basic readiness checks. No metrics, structured audit logs, or rate-limit dashboards in this milestone.

## 4. Bootstrap Assumption

Before the gateway can function, an operator must have manually created a GitHub App on github.com and populated k8s secrets with:

- The App's RSA private key (`.pem`).
- The App ID (numeric).
- The App slug (for constructing the install URL).
- The webhook secret.
- Client secret (captured for completeness but unused in this milestone since we don't do user OAuth).

The gateway reads these at startup via environment variables or mounted files sourced from k8s secrets. If any required secret is missing, the gateway fails to start with a clear error.

## 5. Configuration

All configuration is supplied via command-line flags or environment variables, per the existing service pattern. The following must be configurable:

- GitHub App ID.
- GitHub App slug.
- GitHub App private key (path or value).
- GitHub webhook secret.
- Database connection parameters.
- Encryption key for at-rest secrets in the DB (sourced from k8s secret).
- Pending-link TTL (default: 10 minutes).
- Frontend success and failure redirect URLs for the callback.
- GitHub API base URL (defaulted; overridable for the fake in tests).

## 6. Domain Model

Four entities. Exact column definitions are implementation work; these are the requirements the schema must satisfy.

### 6.1 `installation`

Represents a live GitHub App installation we know about.

- Keyed by GitHub's numeric `installation_id` (stable, unique).
- Records the account login, account type (User/Organization), account numeric ID, install timestamp, and current status (active / suspended).
- Unique constraint on `github_installation_id`.

### 6.2 `linked_repo`

Represents a single repository accessible under an installation.

- Keyed by GitHub's numeric `repository_id` (stable across rename/transfer).
- Also stores current `full_name` for display, refreshed on webhook events.
- Foreign key to `installation`; cascades on installation delete.
- Unique constraint on `(installation_id, github_repository_id)`.

### 6.3 `pending_link_flow`

Represents an in-flight linking attempt between initiate and callback.

- Keyed by a server-generated `state` nonce (single-use, unique).
- Stores the PKCE `code_verifier` (encrypted at rest).
- Stores creation timestamp and expiry timestamp.
- Rows are deleted on successful callback, on explicit abandon, or by a periodic sweep of expired rows.

### 6.4 `webhook_delivery`

Represents a received webhook delivery for idempotency and debugging.

- Keyed by GitHub's `X-GitHub-Delivery` UUID, unique.
- Stores event type, received timestamp, processing outcome.
- Seeing the same delivery ID twice is a no-op.

## 7. Data Integrity Rules

- Unique constraint: `installation.github_installation_id`.
- Unique constraint: `(installation_id, github_repository_id)` on `linked_repo`.
- Unique constraint: `pending_link_flow.state`.
- Cascade: deleting an `installation` deletes its `linked_repo` rows.
- Unlinking a single repo is out of scope; the unlink operation acts on whole installations only.
- Re-linking an already-linked installation updates the existing row rather than creating a duplicate (idempotent on `github_installation_id`).

## 8. Secrets at Rest

- The GitHub App private key, webhook secret, and DB encryption key are provided via k8s secrets and read at startup. They are never written to the database.
- PKCE `code_verifier` values stored in `pending_link_flow` are encrypted at rest using the DB encryption key.
- Installation access tokens are **never persisted**. They are minted on demand from the private key + installation ID, used, and discarded. In-memory caching with expiry is permitted but not required.

## 9. HTTP API

All endpoints return JSON unless noted. No authentication in this milestone (superadmin assumed). Errors return a consistent error shape with a machine-readable code and a human-readable message.

### 9.1 Linking Flow

#### `POST /links/initiate`

Starts a new linking attempt. Generates `state` and PKCE verifier/challenge, writes a `pending_link_flow` row with the configured TTL, and returns the GitHub installation URL as JSON.

- **Request:** empty body (for this milestone).
- **Response:** `{ "install_url": "...", "state": "...", "expires_at": "..." }`.

The caller (frontend or backend) is responsible for navigating the user to `install_url`. We return JSON rather than issuing a 302 so service-to-service callers work.

#### `GET /links/callback`

Called by GitHub after the user completes the install. Query params: `installation_id`, `setup_action`, `state`, `code` (present but unused in this milestone).

Behavior:

1. Look up `pending_link_flow` by `state`. If missing or expired, redirect to the failure URL with an error code.
2. Delete the `pending_link_flow` row (single-use, even on error).
3. Fetch installation details from GitHub using an App JWT.
4. Upsert `installation` keyed by `github_installation_id`.
5. Fetch the installation's current repository list and upsert `linked_repo` rows, removing any rows no longer present.
6. Redirect to the frontend success URL.

#### `POST /links/{state}/abandon`

Explicitly cancels a pending flow. Deletes the `pending_link_flow` row if present. Returns 200 whether or not the row existed (idempotent). This is optional for callers — expired rows are swept anyway — but useful for UIs that want to clean up immediately.

### 9.2 Read APIs

#### `GET /installations`

Lists all linked installations. Returns account login, account type, install timestamp, status, repo count.

#### `GET /installations/{installation_id}/repos`

Lists repos for one installation. Paginated response shape from day one (even if the initial implementation returns everything) so the contract doesn't break later.

#### `GET /repos`

Lists all linked repos across all installations. Paginated.

### 9.3 Unlink

#### `DELETE /installations/{installation_id}`

Tears down an entire installation.

Behavior:

1. Mint an App JWT.
2. Call `DELETE /app/installations/{installation_id}` on GitHub.
3. On success (or 404 from GitHub, meaning already gone), delete the local `installation` row. Cascades delete `linked_repo` rows.
4. Return 200 whether the local row existed or not (idempotent).
5. If GitHub returns a non-404 error, return an error and leave local state alone. Caller can retry.

### 9.4 Reconcile

#### `POST /installations/{installation_id}/reconcile`

Forces a resync of one installation against GitHub's current truth. Unconditionally fetches the installation and its repo list from GitHub and overwrites our local repo list (insert missing, delete stale, update renamed). Used to recover from dropped webhooks. Returns a summary of what changed.

### 9.5 Webhook Ingestion

#### `POST /webhooks/github`

Receives webhook deliveries from GitHub.

Requirements:

1. Verify `X-Hub-Signature-256` using HMAC-SHA256 over the raw request body with the configured webhook secret. Constant-time compare. Reject with 401 on mismatch.
2. Deduplicate on `X-GitHub-Delivery`: if already seen, return 200 without reprocessing.
3. Dispatch on `X-GitHub-Event` and the event's `action` field.
4. Return 200 as fast as possible after recording the delivery; processing may complete asynchronously as long as idempotency holds.

Events that must be handled:

- `installation.deleted` — delete the local `installation` row (cascades repos).
- `installation.suspend` — mark installation suspended.
- `installation.unsuspend` — mark installation active.
- `installation_repositories.added` — insert new `linked_repo` rows.
- `installation_repositories.removed` — delete the specified `linked_repo` rows.
- `repository.renamed` — update `full_name` on the matching `linked_repo`.
- `repository.transferred` — update `full_name`; the numeric ID stays the same.

Unknown or unhandled event types return 200 and are ignored.

### 9.6 Readiness

#### `GET /health`

Returns 200 if the process is up. No dependency checks. Used by k8s liveness/readiness probes.

## 10. Behavioral Requirements

### 10.1 Concurrency

- Multiple `/links/initiate` calls may be in flight simultaneously. Each creates its own `pending_link_flow` row keyed by its own `state`; there is no global "current flow."
- The callback must tolerate flows completing out of order relative to when they were initiated.

### 10.2 Abandonment

- A pending flow that is never completed must not affect any subsequent flow. Achieved via unique `state` per flow plus TTL-based sweep.
- An explicit `POST /links/{state}/abandon` deletes the row immediately.
- A periodic sweeper deletes `pending_link_flow` rows past their `expires_at`. Sweep interval and TTL are configurable.

### 10.3 Idempotency

- Re-linking an installation updates the existing row.
- Unlinking an already-unlinked installation returns 200.
- Replaying a webhook delivery ID is a no-op.
- Abandoning a nonexistent flow returns 200.

### 10.4 PKCE

- `code_verifier` is generated per flow, 43–128 random URL-safe characters.
- `code_challenge = BASE64URL(SHA256(code_verifier))`.
- `code_challenge_method = S256`.
- Verifier is stored encrypted, used once at callback, then deleted.
- Note: PKCE is included for correctness and defense-in-depth, even though this milestone does not exercise the user-OAuth code exchange that PKCE most directly protects. The structure is in place so future user-OAuth work inherits it.

### 10.5 Installation Tokens

- Minted on demand via App JWT + `POST /app/installations/{id}/access_tokens`.
- App JWT is short-lived (≤10 minutes, `iss = app_id`, RS256).
- Tokens are not persisted. In-memory caching permitted with strict expiry.

### 10.6 GitHub API Failure Handling

- Read endpoints (`GET /installations`, `/repos`) never call GitHub on the read path; they serve from the local DB.
- Token minting and reconcile do call GitHub. On failure, they return a clear error code and leave local state untouched.
- No retry storms: a single attempt per caller request.

## 11. Testing Requirements

### 11.1 GitHub Client Seam

- All GitHub interactions go through an interface. Production uses a real HTTP client; tests use a fake implementation.
- CI has no GitHub credentials. No test hits real GitHub.

### 11.2 Unit Tests (Business Logic)

- PKCE verifier and challenge generation, including verifying `SHA256(verifier) == challenge`.
- State lifecycle: create, consume, expire, sweep, abandon.
- Callback validation: missing state, expired state, unknown state, replayed state, happy path.
- Idempotent re-link: same `installation_id` links twice, ends with one row.
- Idempotent unlink: unlink of missing installation returns 200.
- Unlink calls GitHub, handles 200, 404, and other error statuses correctly.
- Webhook signature verification: valid, invalid, missing, wrong-secret.
- Webhook dispatch: each supported event produces the expected state change.
- Webhook deduplication by `X-GitHub-Delivery`.
- Reconcile: adds missing repos, removes stale repos, updates renamed repos, handles installation-gone case.

### 11.3 Integration Tests

- Full link flow end-to-end against the fake GitHub.
- Two concurrent link flows complete without interference.
- Abandoned link flow does not affect a subsequent successful flow.
- Expired pending flow is swept and cannot be completed after expiry.
- Webhook ingestion updates the DB correctly for each supported event.

## 12. Flyway

A single fresh baseline migration creates `installation`, `linked_repo`, `pending_link_flow`, and `webhook_delivery` tables with the constraints in §7. Existing migration files are removed.

## 13. Open Questions / Future Work

- Authentication and authorization on the gateway's endpoints.
- Tenant ownership column on `installation` and subsequent scoping of all list/mutate endpoints.
- Manifest flow for automated GitHub App creation at deployment time.
- Key rotation without restart.
- Observability: metrics, structured audit logs, rate-limit surfacing.
- Webhook processing asynchrony: if processing under the HTTP handler becomes slow, move to a queue.
- Retry and backoff strategy for GitHub API failures on token minting and reconcile.
# Linking flow

The linking flow connects a UBC user to a GitHub App installation (an account or org on GitHub) and mirrors that installation's selected-repo set into the gateway's database. It is the only way an installation enters our system; once linked, webhooks and reconcile jobs keep the mirror in sync.

> **Precondition: the deployment's GitHub App must be provisioned first.** The app the user
> installs is created per-deployment by the [app provisioning flow](./app_provisioning_flow.md),
> not configured centrally. Until that one-time bootstrap completes there is no app slug to
> install and no private key to mint JWTs with, so `POST /links/initiate` fails fast and the SPA
> shows provisioning instead of the link button. The app slug, private key, and webhook secret
> used below are loaded from the stored `github_app` record, not from environment config.

The flow is a server-driven handshake keyed on a single-use `state` nonce:

- The gateway issues a single-use `state` nonce and persists a pending row keyed on it.
- The user is redirected to GitHub to install the app.
- GitHub redirects back with `state` + `installation_id`; the gateway consumes the pending row, calls GitHub as the App, and persists the installation + repos.

(GitHub App installation flow does not use PKCE — the code verifier/challenge are only relevant to GitHub's user-authorization OAuth flow, which this gateway doesn't drive.)

## Actors

- **User** — a person in a browser. Drives the flow and consumes the SPA.
- **SPA** — the Tyrian frontend at `provider-gateways/github-gateway/presentation/src/Main.scala`. Initiates the link, renders the result page after callback.
- **github-gateway** — the JVM service. Generates the nonce, persists the pending row, calls GitHub as the App, owns the installation/repo mirror.
- **DB** — Postgres. Holds `pending_link_flow` (transient) plus `installation` and `linked_repo` (durable mirror).
- **GitHub** — external. Hosts the install UI, redirects back to the callback, serves App and Installation REST endpoints.

Activity-log emission is intentionally omitted from the diagrams. Logs are observability, not flow logic; see `core/core-impl/src/ubc/githubgateway/core/Activity.scala` for the events.

## 1. Happy path

End-to-end: the user starts the link from the SPA, completes the install on GitHub, and lands back on the SPA with the installation linked and its repo set mirrored.

```mermaid
sequenceDiagram
    participant U as User
    participant SPA
    participant GW as github-gateway
    participant DB
    participant GH as GitHub

    U->>SPA: 1. Click "Link a new GitHub installation"
    SPA->>GW: 2. POST /links/initiate
    GW->>GW: 3. Generate state nonce
    GW->>DB: 4. Insert pending_link_flow row
    GW-->>SPA: 5. 200 { installUrl, state, expiresAt }
    SPA->>U: 6. Navigate browser to installUrl
    U->>GH: 7. Select account + repos, click Install
    GH-->>U: 8. 302 to /links/callback?state&installation_id
    U->>GW: 9. GET /links/callback
    GW->>DB: 10. findByState(state)
    DB-->>GW: Some(flow)
    GW->>GW: 11. Check expiresAt > now
    GW->>GW: 12. Mint App JWT
    GW->>GH: 13. GET /app/installations/{id}
    GH-->>GW: installation metadata
    GW->>DB: 14. upsertByGhInstallationId(...)
    GW->>GH: 15. POST /app/installations/{id}/access_tokens
    GH-->>GW: installation token
    GW->>GH: 16. GET /installation/{id}/repositories
    GH-->>GW: repo set
    GW->>DB: 17. replaceSet(installationId, repos)
    GW->>DB: 18. deleteByState(state)
    GW-->>U: 19. 302 to successRedirectUrl
    U->>SPA: 20. Load /link/result
    SPA-->>U: 21. Render "Linked!"
```

1. User clicks the "Link a new GitHub installation" button on the SPA Home view (`presentation/src/Main.scala:104`).
2. SPA calls `POST /links/initiate` against the gateway (`api/api-defn/.../GitHubGatewayApi.scala:27`).
3. Gateway generates a fresh `LinkState` nonce via the `SecureRandom` port.
4. Gateway inserts a `PendingLinkFlow` row keyed by `state`, with `expiresAt = now + pendingLinkTtl` (`core/ports/.../PendingLinkFlowRepository.scala:20`).
5. Gateway returns `LinkInitiation { installUrl, state, expiresAt }` where `installUrl = https://github.com/apps/<appSlug>/installations/new?state=<state>`. `<appSlug>` is read from the stored `github_app` record created during [provisioning](./app_provisioning_flow.md); if no app is provisioned, this step fails before a pending row is written.
6. SPA performs a full-page navigation to `installUrl` via `Nav.loadUrl` (`presentation/src/Main.scala:113`).
7. The user picks the account/org to install on and the repo set, then confirms.
8. GitHub redirects to the configured callback URL with `state` and `installation_id` query parameters.
9. Browser follows the redirect to the gateway (`api/internal-api-adapters/http/.../GitHubGatewayHttpController.scala:52`).
10. Gateway looks up the pending row by `state`.
11. Gateway verifies `flow.expiresAt > now`.
12. Gateway mints a short-lived RS256 App JWT signed with the GitHub App private key (`InstallationTokenMinter`, implemented by `NimbusJoseInstallationTokenMinter`). The private key and the issuer App ID are loaded from the stored `github_app` record, not from config.
13. Gateway calls `GET /app/installations/{installation_id}` to fetch installation metadata (`core/ports/.../GitHubAppClient.scala:26`).
14. Gateway upserts the local installation row by `GhInstallationId`, getting back the persisted row with our local surrogate id.
15. Gateway exchanges the App JWT for a short-lived installation access token (`GitHubAppClient.scala:51`).
16. Using the installation token, gateway fetches the selected-repo set (`GitHubAppClient.scala:31`).
17. Gateway replaces the local mirror's repo set for this installation, recording `(added, removed, renamed)` counts.
18. Gateway deletes the pending row. This runs through `acquireReleaseWith` so the row is removed on **every** exit path from step 11 onward.
19. Gateway responds with 302 to `successRedirectUrl`.
20. The browser loads the success URL, which is served by the SPA at `/link/result`.
21. SPA detects the `/link/result` path, sees no `?error` parameter, replaces the history entry with `/`, and renders the success view (`presentation/src/Main.scala:41-46, 184-200`).

## 2. Callback — state not found

The supplied `state` matches no pending row. Possible causes: the callback was already consumed, the row was swept after expiry, or the request is fabricated.

```mermaid
sequenceDiagram
    participant U as User
    participant GW as github-gateway
    participant DB

    U->>GW: 1. GET /links/callback?state&installation_id
    GW->>DB: 2. findByState(state)
    DB-->>GW: None
    GW-->>U: 3. 302 to failureRedirectUrl?error=STATE_NOT_FOUND
```

1. Browser arrives from GitHub (or is replaying an old callback).
2. Gateway looks up the pending row.
3. No row matched — gateway fails with `LinkError.StateNotFound`; the HTTP controller maps that to a 302 to `failureRedirectUrl?error=STATE_NOT_FOUND`. No row exists to delete.

## 3. Callback — state expired

The pending row exists but its `expiresAt` has passed. The gateway rejects the callback and clears the stale row.

```mermaid
sequenceDiagram
    participant U as User
    participant GW as github-gateway
    participant DB

    U->>GW: 1. GET /links/callback?state&installation_id
    GW->>DB: 2. findByState(state)
    DB-->>GW: Some(flow)
    GW->>GW: 3. flow.expiresAt <= now
    GW->>DB: 4. deleteByState(state)
    GW-->>U: 5. 302 to failureRedirectUrl?error=STATE_EXPIRED
```

1. Browser arrives from GitHub.
2. Gateway looks up the pending row.
3. Row found, but `expiresAt <= now` — gateway fails with `LinkError.StateExpired`.
4. Bracket cleanup removes the stale pending row.
5. HTTP controller responds with 302 + `?error=STATE_EXPIRED`.

## 4. Callback — GitHub failure

The pending row is valid, but a GitHub-side call (App JWT mint, `getInstallation`, `createInstallationToken`, or `listInstallationRepos`) fails. The gateway aborts and cleans up.

```mermaid
sequenceDiagram
    participant U as User
    participant GW as github-gateway
    participant DB
    participant GH as GitHub

    U->>GW: 1. GET /links/callback?state&installation_id
    GW->>DB: 2. findByState(state)
    DB-->>GW: Some(flow)
    GW->>GW: 3. Expiry OK, mint App JWT
    GW->>GH: 4. GitHub API call
    GH-->>GW: error
    GW->>DB: 5. deleteByState(state)
    GW-->>U: 6. 302 to failureRedirectUrl?error=GITHUB_FAILURE
```

1. Browser arrives from GitHub.
2. Gateway looks up the pending row, finds it.
3. Expiry passes; gateway begins GitHub-side work.
4. One of the four GitHub calls (`mintAppJwt`, `getInstallation`, `createInstallationToken`, `listInstallationRepos`) fails. The error is mapped to `LinkError.GitHubFailure(message)`.
5. Bracket cleanup deletes the pending row regardless of how far the GitHub-side work got. Local installation/repo state may have been partially upserted; the next reconcile run corrects it.
6. HTTP controller responds with 302 + `?error=GITHUB_FAILURE`.

## 5. Abandon

A caller (typically the SPA, but the endpoint is open to any authenticated client) drops a pending link request without going through GitHub. Idempotent.

```mermaid
sequenceDiagram
    participant C as Caller
    participant GW as github-gateway
    participant DB

    C->>GW: 1. POST /links/{state}/abandon
    GW->>DB: 2. deleteByState(state)
    GW-->>C: 3. 204 No Content
```

1. Caller sends `POST /links/{state}/abandon`.
2. Gateway deletes the pending row by state. No-op if no row exists.
3. Gateway responds 204.

## 6. Sweep

A periodic background job clears pending rows whose `expiresAt` has passed. This guarantees a stale row from an abandoned-by-the-user (closed-tab) flow eventually disappears even if no callback or abandon arrives.

```mermaid
sequenceDiagram
    participant S as Scheduler
    participant GW as github-gateway
    participant DB

    S->>GW: 1. sweepExpiredFlows()
    GW->>DB: 2. deleteExpired(now)
    DB-->>GW: count
```

1. A scheduled job in `server/src/Server.scala` triggers the sweep on a fixed cadence.
2. Gateway calls `pendingFlows.deleteExpired(now)`. Returns the number of rows removed.

## Pending row lifecycle

The `pending_link_flow` row is the spine of the flow. Exactly one of the following ends every row's life:

- **Successful callback** — deleted in step 19 of the happy-path diagram.
- **Expired callback** — deleted in step 4 of the state-expired diagram.
- **GitHub-failure callback** — deleted in step 5 of the GitHub-failure diagram.
- **Abandon** — deleted in step 2 of the abandon diagram.
- **Sweep** — deleted in step 2 of the sweep diagram, for rows where the user never came back.

Single-use is enforced by deletion on every callback exit path (via `acquireReleaseWith` in `GitHubGatewayService.scala`); replay of a successful callback then falls into the state-not-found path.

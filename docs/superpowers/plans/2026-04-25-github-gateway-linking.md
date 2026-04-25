# GitHub Gateway — Linking — Implementation Plan (Hypothesis)

> This is a hypothesis. It will be updated as TDD cycles reveal design decisions.

## Scope note (important)

The spec replaces the existing `github-gateway` service end-to-end. The old token-storage scaffolding (`GitHubToken`, `TokenId`, `InternalToken`, `TokenRepository`, `MagnumTokenRepository`, `InMemoryTokenRepository`, `TapirGitHubClient`, `GitHubGatewayService`, current `GitHubGatewayHttpController`, V1 migration) is removed before the new layers are built. There are no live consumers; the spec explicitly states no backward compatibility.

## Feature summary

Replace the github-gateway service with a GitHub App linking flow. New domain types model live installations, their repos, in-flight link attempts (PKCE-protected), and seen webhook deliveries. Ports cover: installation persistence, repo persistence (with set-replacement), pending-link persistence (with TTL/sweep), webhook-delivery persistence (idempotency), a GitHub API seam (installation lookup, repos list, app-DELETE), an installation-token minter, a clock, a random/PKCE generator, and a symmetric crypto port for encrypting `code_verifier` at rest. Core service composes these into `initiate / callback / abandon / list / unlink / reconcile / webhook-dispatch / sweep` operations. Driven adapters: four Magnum repositories on a fresh Flyway baseline, a Tapir+sttp GitHub client, a JJWT-based installation-token minter wired to a real RSA key, an AES-GCM crypto adapter, and a real wall-clock + secure-random adapter. Driving adapter: ZIO HTTP via Tapir for all REST endpoints plus a webhook handler that verifies HMAC-SHA256 over the raw body. Server wires it all up; a scheduled sweeper drains expired pending flows.

## Layers

| # | Type | What to build | Test strategy | Known unknowns |
|---|---|---|---|---|
| 1 | domain | newtypes (`InstallationId`, `GhInstallationId` (numeric, GitHub-side), `AccountLogin`, `AccountId`, `RepositoryId`, `RepoFullName`, `LinkState`, `CodeVerifier`, `CodeChallenge`, `DeliveryId`, `EventType`, `EventAction`, `EncryptedBytes`, `InstallUrl`, `AppId`, `AppSlug`, `AppJwt`, `InstallationAccessToken`); enums: `AccountType { User, Organization }`, `InstallationStatus { Active, Suspended }`, `WebhookOutcome { Processed, Ignored, Duplicate, Failed }`; cases: `Installation`, `LinkedRepo`, `PendingLinkFlow`, `WebhookDelivery`, `LinkInitiation`, `ReconcileSummary`, `WebhookEnvelope`; ADTs: `LinkError`, `UnlinkError`, `ReconcileError`, `WebhookError` (in `domainPrivate`); plus `common/pagination` module with `Page[A](items, total, nextCursor: Option[String])` and `PageRequest(cursor: Option[String], limit: Int)` | ZIO Test on `domainPublic.jvm`, `domainPrivate`, and `common.pagination` | Whether `Installation.installedAt` belongs in domain or only in row form |
| 2 | ports | `InstallationRepository`, `LinkedRepoRepository` (set-replace semantics for reconcile), `PendingLinkFlowRepository` (insert / find / delete / sweepExpired), `WebhookDeliveryRepository` (recordIfAbsent — returns `true` if newly recorded), `GitHubAppClient` (getInstallation, listInstallationRepos, deleteInstallation, exchangeAppJwtForInstallationToken), `InstallationTokenMinter` (mints app JWT and installation tokens — kept separate from the HTTP client because it needs the RSA private key and is pure JVM crypto), `Clock` (use `zio.Clock` directly — not a custom port), `SecureRandom` port (generates `CodeVerifier`), `Crypto` port (encrypt/decrypt `code_verifier`); plus in-memory stubs for each repo, a fake `GitHubAppClient`, an in-memory `InstallationTokenMinter`, a deterministic `SecureRandom`, and a no-op `Crypto` (identity transform). | In-memory stub spec per port exercises every method | Whether the GitHub client port should expose a single `GitHubAppClient` trait or split installation-CRUD from token-minting; the spec's "tokens not persisted" rule strongly suggests minter is a separate port that returns short-lived values |
| 3 | core | `GitHubGatewayService` with: `initiate(): UIO[LinkInitiation]`, `callback(state, ghInstallationId): IO[LinkError, Unit]`, `abandon(state): UIO[Unit]`, `listInstallations(): UIO[List[Installation]]`, `listInstallationRepos(id, page): UIO[Page[LinkedRepo]]`, `listRepos(page): UIO[Page[LinkedRepo]]`, `unlink(ghInstallationId): IO[UnlinkError, Unit]`, `reconcile(ghInstallationId): IO[ReconcileError, ReconcileSummary]`, `handleWebhook(envelope, rawBody, signatureHeader): IO[WebhookError, Unit]` (verifies signature, dedups by delivery id, dispatches), `sweepExpiredFlows(): UIO[Int]`. PKCE generation uses `SecureRandom` + SHA-256. Webhook signature verification uses HMAC-SHA-256 with constant-time compare. | ZIO Test against in-memory stubs via ZLayer; covers spec §11.2 list (PKCE, state lifecycle, callback validation, idempotent re-link, idempotent unlink, GitHub error handling, webhook signature, dispatch, dedup, reconcile semantics) | Where the install URL is constructed (core, given `AppSlug` from config) — leaning yes, core takes `AppSlug` as a constructor arg; how to express webhook event payloads — likely a small ADT `GithubWebhookEvent` discriminated by event type |
| 4 | driven-adapters | (a) `db-migrations/V1__github_gateway_baseline.sql` creates the four tables; (b) Magnum repos: `MagnumInstallationRepository`, `MagnumLinkedRepoRepository`, `MagnumPendingLinkFlowRepository`, `MagnumWebhookDeliveryRepository`; (c) `TapirSttpGitHubAppClient` (replaces the existing `TapirGitHubClient`); (d) `NimbusJoseInstallationTokenMinter` (using `com.nimbusds:nimbus-jose-jwt`) — needs a new mvnDep; (e) `AesGcmCrypto` adapter using `javax.crypto`; (f) `JavaSecureRandomGenerator` adapter | Each Magnum repo: `TestDatabase.suiteLayer` + Testcontainers (real Postgres in CI); `TapirSttpGitHubAppClient`: Tapir stub backend (no real GitHub); `NimbusJoseInstallationTokenMinter`: sign-and-verify round-trip with a generated test RSA keypair (no real GitHub); `AesGcmCrypto`: encrypt-decrypt round-trip with random key; secure-random: smoke test for distinct outputs | How the Tapir stub fakes installation-token exchange |
| 5 | driving-adapter | `GitHubGatewayHttpController` (replaces the existing one) with Tapir endpoints for all routes in §9; webhook handler must take the raw body so HMAC verification matches; pagination contract from day one. The controller delegates to `GitHubGatewayService`. The `Server` is rewired to provide all new layers, the AES key from config, the App private key from a mounted file path, the App ID/slug, webhook secret, redirect URLs, and starts the periodic flow sweeper as a background fiber. | Tapir's `ZioHttpInterpreter` with the in-memory core; covers happy path + error mappings for each endpoint plus webhook signature failure | Whether webhook verification needs Tapir's raw-body endpoint or a custom ZIO HTTP handler; integration tests covered by separate test module under `server/test` (deferred to follow-up if scope blows out) |

## Dependency map

- Layer 2 needs from 1: all domain types
- Layer 3 needs from 2: all port traits + all in-memory stub layers
- Layer 4 needs from 1+2: domain types and port traits
- Layer 5 needs from 1+2+3: api-defn, service, public domain JSON codecs

## Resolved decisions

- **Pagination shape.** `Page[A](items, total, nextCursor: Option[String])` lives in **`common`** (new module `common/pagination`) so other services can reuse it. Cursor = base64-encoded offset for the initial impl.
- **AccountType modelling.** `enum AccountType { case User, Organization }` in `domainPublic`. Explicit JSON/Magnum codecs in adapter-extension modules.
- **JWT library.** nimbus-jose-jwt. Reasons: a single mvn artifact covers JWS, broader Scala-stack precedent, cleaner `RS256` signing API against a `java.security.PrivateKey`, no Jackson pull-in (jjwt does). **Null-safety:** when implementing the JWT adapter, audit nimbus's API for nullable returns (`JWTClaimsSet.getXxx(...)`, `SignedJWT.parse`, etc.). If any method we touch can return null, wrap it in a thin `common/nimbus-jwt-safe` (or inline in the adapter, depending on size) that returns `Option`/`Either` instead. If everything we use is non-null, no wrapper.
- **Integration tests scope.** DB-stack integration tests are **in scope** — Magnum repo specs hit the real Postgres via `TestDatabase.suiteLayer` + Testcontainers. Tests that hit the real GitHub API are **out of scope** (no real GitHub credentials in CI; the GitHub client port keeps a fake for unit tests, and end-to-end webhook/install flows are tested with the in-memory `GitHubAppClient` only).
- **Branch.** `feature/github-gateway-linking`.

## Remaining open questions

- **Frontend redirect URLs.** Spec §5 lists success/failure URLs as config. The callback endpoint has to issue a 302 to one of those, so the controller needs them. Plan: pass them as constructor args to the HTTP controller from config.
- **Encryption key handling.** Spec §8 says k8s secret. Plan: `Crypto` port takes a key at construction time; the AES-GCM adapter is built from a `String` config value (base64-encoded key).
- **Sweeper scheduling.** Plan: `Server.run` launches `sweep` on a `Schedule.spaced` fiber; interval is configurable.
- **Existing token scaffolding fate.** Plan: delete it wholesale at the end of Layer 1 once the new domain types compile, so nothing stale lingers. The deletion is part of Layer 1's commit.
- **Tapir raw-body for webhook signature.** Likely use `byteArrayBody` plus a request hook to read the raw bytes; needs verification when we hit Layer 5.

## Execution order

The plan is the standard 5 layers. Each layer is one or more sub-agent dispatches followed by audit + compile + commit. Given the spec's size, I expect Layer 4 to need multiple sub-agent passes (one per adapter family — DB repos, GitHub client, JWT minter, crypto). I'll dispatch them sequentially within Layer 4 to keep prompts focused.

## Layers checklist

- [ ] Layer 1 — Domain types
- [ ] Layer 2 — Ports + in-memory stubs
- [ ] Layer 3 — Core service
- [ ] Layer 4 — Driven adapters
- [ ] Layer 5 — Driving adapter (HTTP)

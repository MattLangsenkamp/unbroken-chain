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

- [x] Layer 1 — Domain types  *(commit `13cc009`)*
- [x] Layer 2 — Ports + in-memory stubs  *(commit `0da9178`)*
- [x] Layer 3 — Core service
- [ ] Layer 4 — Driven adapters
- [ ] Layer 5 — Driving adapter (HTTP)

## Layer 1 deviations

- **`EncryptedBytes` repr changed from `Array[Byte]` → `String` (base64).** TDD revealed neotype on `Array[Byte]` inherits Array's reference-equality `==`, masked by zio-test's `assertTrue` macro rewriting to `sameElements`. Switched to `Newtype[String]` with base64 payload. Magnum codec extension (Layer 4) must translate to/from JDBC `BYTEA` at the boundary.
- **Test sources path on cross-compiled modules.** Test sources for `domainPublic.jvm` land under `domainPublic/jvm/test/src/`, not `domainPublic/test/src/`, because the `Shared` trait's `sources` override only applies to production code. The test sub-module uses Mill's default path resolution.
- **Twin newtypes `InstallationId` (local DB surrogate) vs `GhInstallationId` (GitHub-side).** Same pattern for `RepositoryId` vs `GhRepositoryId`. Ports must use the local id for primary-key arguments and the GitHub id only when interacting with GitHub or webhook payloads.

## Amendments propagated to later layers

- Layer 4 Magnum codec for `EncryptedBytes`: `DbCodec[Array[Byte]].biMap(b => EncryptedBytes(Base64.encode(b)), e => Base64.decode(e.unwrap))`.
- Layer 4 V1 migration: see Layer 1 report — four tables with text-encoded enums and `BYTEA` for the encrypted verifier.
- Layer 2 ports must distinguish `InstallationId` (local) from `GhInstallationId` (GitHub) in method signatures.
- Layer 2 `EncryptionKey` newtype is a `String` (base64); the encryption adapter decodes inside, never exposing raw key bytes through the port.

## Layer 2 deviations

- **All repository ports return `Task[A]`, not `IO[DomainError, A]`.** Repos surface infra failures as defects; the `LinkError` / `UnlinkError` / `ReconcileError` family is constructed in the core service. This keeps adapter implementations free of error-channel boilerplate.
- **`InMemoryGitHubAppClient.layer` is `ZLayer[Any, Nothing, GitHubAppClient & InMemoryGitHubAppClient]`** — single dual binding so core-service tests can `ZIO.serviceWith[InMemoryGitHubAppClient]` for seeding (`seedInstallation`, `seedRepos`) while wiring the port-typed surface. No second `layerWithAccess`.
- **`InMemoryGitHubAppClient.deleteInstallation` returns `Right(())` even for unseeded installations** — mirrors GitHub's "404 = idempotent success" semantics.
- **`DeterministicSecureRandom` shares one counter** between `newCodeVerifier` and `newLinkState` — sequential calls produce different counter values, so don't write tests assuming per-method counters.
- **In-memory `InstallationRepository.deleteByGhInstallationId` does NOT cascade** to `linked_repo`. Postgres relies on `ON DELETE CASCADE`; the in-memory contract expects the core service to handle related deletions explicitly.

## Amendments propagated to Layer 3 / Layer 4

- Use `zio.Clock` directly in core service; tests get `TestClock`.
- After `GitHubAppClient.getInstallation` (which returns `Installation` with sentinel `id = InstallationId(0L)`), core MUST call `InstallationRepository.upsertByGhInstallationId` to obtain the real local id before exposing the value through any read API.
- `GitHubAppClient.deleteInstallation` returns `Task[Either[String, Unit]]` — core unlink path uses `.flatMap` over the `Either` to map to `UnlinkError.GitHubFailure`.
- Layer 4: `WebhookDeliveryRepository.recordIfAbsent` must use `INSERT … ON CONFLICT (delivery_id) DO NOTHING` returning a row count — never SELECT-then-INSERT.
- Layer 4: `LinkedRepoRepository.replaceSet` runs in a single Magnum transaction.
- Layer 4: `Crypto` real adapter takes `EncryptionKey` as a constructor arg, AES-GCM, base64-encoded output. `InstallationTokenMinter` real adapter takes the App's `appId` + PEM-encoded private key as constructor args; signs RS256 with TTL ≤ 10 min.

## Layer 3 deviations

- **Added `WebhookDeliveryRepository.updateOutcome(deliveryId, outcome)`** to the port and the in-memory adapter. The Layer-2 port only had `recordIfAbsent`; the core service writes a provisional `Processed` row up-front (for dedup) then rewrites the outcome after dispatch. Layer 4's Magnum adapter must implement `UPDATE webhook_delivery SET outcome = ? WHERE delivery_id = ?` (no-op when zero rows).
- **Widened `InMemoryWebhookDeliveryRepository.layer`** to `WebhookDeliveryRepository & InMemoryWebhookDeliveryRepository` (dual binding) so tests can `peek` the persisted outcome — mirrors the existing `InMemoryGitHubAppClient` pattern.
- **`GithubWebhookEvent` ADT lives in `core-impl`** (not in domain). JSON parsing happens in Layer 5's driving adapter; the core never sees JSON.
- **`callback` uses `acquireReleaseWith`** to bracket the pending-flow row deletion so it always runs regardless of expiry/GitHub-failure outcome (single-use semantics).

## Amendments propagated to Layers 4 / 5

**Layer 4 (driven adapters):**
- Magnum `WebhookDeliveryRepository` must implement `updateOutcome` (new method).
- Magnum `PendingLinkFlowRepository.deleteExpired` SQL uses `expires_at <= now` (boundary inclusive) to match `sweepExpiredFlows` semantics.
- `TapirSttpGitHubAppClient.deleteInstallation` maps HTTP 204 + 404 → `Right(())`, all other non-2xx → `Left(s"$status: $body")`. Service uses the message as `UnlinkError.GitHubFailure(message)`.

**Layer 5 (driving HTTP adapter):**
- Driving adapter parses JSON into `GithubWebhookEvent` before calling `handleWebhook`. Core never sees JSON.
- Webhook route MUST pass the raw byte body to `handleWebhook` — HMAC verification is byte-for-byte.
- Forward `X-Hub-Signature-256` verbatim (with the `sha256=` prefix).
- Suggested HTTP error mapping:
  - `LinkError.StateNotFound` / `StateExpired` → 410 Gone
  - `LinkError.GitHubFailure` → 502 Bad Gateway
  - `WebhookError.InvalidSignature` → 401
  - `WebhookError.MalformedPayload` → 400
  - `ReconcileError.InstallationNotFound` → 404
  - `ReconcileError.GitHubFailure` / `UnlinkError.GitHubFailure` → 502
- `Server.scala` will read `GitHubGatewayConfig` from env (`GITHUB_APP_ID`, `GITHUB_APP_SLUG`, `GITHUB_PENDING_LINK_TTL`, `GITHUB_WEBHOOK_SECRET`) via `zio-config`. Add a `GitHubGatewayConfig.live` layer that reads these.

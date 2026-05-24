---
name: ports-and-adapters
description: Use when adding a new port trait, implementing a new adapter (repository, HTTP client, or in-memory stub), or understanding how core/ports and core/adapters modules relate in a service.
---

# Ports and Adapters

The core domain is isolated from infrastructure through port traits; adapters wire infrastructure to those traits.

- **Port** — a trait in `core/ports/` declaring what the core needs, using only domain types and ZIO effect types (`IO[DomainError, A]` preferred; `Task[A]` for unmodeled failures). No infrastructure types cross this boundary.
- **Driven adapter** (outbound) — implements a port against real infrastructure (e.g., `magnum-token-repository`, `tapir-github-client`). Lives in `core/adapters/`.
- **Driving adapter** (inbound) — receives external requests and delegates immediately to core. Lives in `api/internal-api-adapters/`. No business logic — request decoding only.
- **In-memory adapter** — implements a port using `Ref`-backed state. Used for local dev and testing. Also lives in `core/adapters/`.

## Naming philosophy

**Ports are abstractions — names say WHAT, not HOW.** Infrastructure never appears in a port name.

**Adapters are concrete — names say exactly HOW they are implemented.** Lean into technology names: the reader should know the full stack from the class name alone.

| Type | Example names | Why |
|---|---|---|
| Port | `TokenRepository`, `GitHubPort`, `GitHubGatewayApi` | Pure intent, zero infra |
| Driven adapter | `MagnumTokenRepository`, `TapirGitHubClient` | Tech stack in the name |
| Driving adapter | `GitHubGatewayHttpController` | Protocol + role in the name |
| Typed client (this service's API) | `TapirGitHubGatewayClient` | Tapir+sttp — one cross-built impl for JVM + Scala.js |
| In-memory adapter | `InMemoryTokenRepository` | Storage mechanism explicit |

A service's own typed HTTP client is **one cross-built class** (`TapirGitHubGatewayClient`) — Tapir endpoints and the `SttpClientInterpreter` both cross-compile to Scala.js, so the JVM and the browser share the same implementation. Only the *backend* differs per platform, and that lives in a tiny JS-only entry point (`SttpFetchGitHubGatewayClient.apply` supplies `FetchZioBackend`). Do not hand-roll a separate JS client; see "Shared HTTP contract" below.

## Port traits

Location: `core/ports/src/ubc/<service>/core/ports/`

```scala
package ubc.githubgateway.core.ports

import ubc.githubgateway.domain.*
import ubc.githubgateway.domain.internal.*
import zio.Task

trait TokenRepository:
  def save(token: InternalToken): Task[Unit]
  def findByUserId(userId: UserId): Task[Option[InternalToken]]
  def delete(id: TokenId): Task[Unit]
```

Rules:
- Import only domain types and ZIO effect types — never Magnum, Tapir, JDBC, or any infrastructure
- Prefer `IO[DomainError, A]` when a domain error type models the failure; use `Task[A]` for unmodeled defects
- Domain errors live in `domainPrivate`; adapters convert infrastructure errors to them at the boundary
- One port per concern — don't bundle unrelated operations

## Adapter modules

Location: `core/adapters/<tech>-<name>/src/ubc/<service>/core/adapters/<tech>/`

Naming: `<tech>-<name>` — e.g., `magnum-token-repository`, `tapir-github-client`, `in-memory-token-repository`.

Every adapter:
1. Extends the port trait
2. Takes its infrastructure dependency via constructor
3. Exposes a `ZLayer` in its companion object

```scala
class InMemoryTokenRepository(store: Ref[Map[UserId, InternalToken]]) extends TokenRepository:
  def save(token: InternalToken): Task[Unit] =
    store.update(_.updated(token.userId, token))
  def findByUserId(userId: UserId): Task[Option[InternalToken]] =
    store.get.map(_.get(userId))
  def delete(id: TokenId): Task[Unit] =
    store.update(_.filter { case (_, t) => t.id != id })

object InMemoryTokenRepository:
  val layer: ULayer[TokenRepository] =
    ZLayer.fromZIO(
      Ref.make(Map.empty[UserId, InternalToken]).map(new InMemoryTokenRepository(_))
    )
```

## In-memory adapters

Always provide an `in-memory-<name>` adapter alongside any infrastructure adapter. It:
- Requires no external dependencies (`ULayer[Port]`, not `ZLayer[Dep, E, Port]`)
- Uses `Ref` for mutable state
- Is the default for unit tests and local dev without infrastructure

## Driving adapters (inbound)

Location: `api/internal-api-adapters/<tech>/src/ubc/<service>/api/internal/<tech>/`

Driving adapters translate external requests into core calls. They **do not define endpoint shapes** — those live in the shared `api/shared-adapter-dependencies/tapir-endpoints` module (see "Shared HTTP contract"). The controller imports those shapes, attaches server logic, decodes requests, and delegates — nothing else.

```scala
import ubc.githubgateway.api.endpoints.GitHubGatewayEndpoints.*

// Inbound HTTP adapter: attaches server logic to the shared endpoints, delegates to core.
object GitHubGatewayHttpController:
  def routes(service: GitHubGatewayService, tracing: Tracing): Routes[Any, Response] =
    // initiateEndpoint, reconcileEndpoint, … come from the shared tapir-endpoints module.
    val reconcile = reconcileEndpoint.zServerLogic[Any] { ghId =>
      service.reconcile(ghId).mapError(toApiError)
    }
    interpreter.toHttp(reconcile) ++ ...

  val layer: ZLayer[GitHubGatewayService & Tracing, Nothing, Routes[Any, Response]] =
    ZLayer.fromFunction(routes)
```

In `build.mill`, `api/internal-api-adapters/<tech>` depends on `api/shared-adapter-dependencies/tapir-endpoints.jvm` (for the shapes) and `core/core-impl` (for the service) — never on ports directly, and never the typed-client module.

## Shared HTTP contract (Tapir endpoints + typed client)

A service's HTTP API lives in three `api/` modules so the wire contract is defined exactly once and shared by the server and every client (including the browser SPA):

| Module | Cross-built? | Holds | Depends on |
|---|---|---|---|
| `api/api-defn` | JVM + JS | The semantic trait (`GitHubGatewayApi`) + transport-free error envelope (`ApiError`) | domainPublic, pagination — **no Tapir** |
| `api/shared-adapter-dependencies/tapir-endpoints` | JVM + JS | The Tapir `Endpoint` vals (paths, methods, in/out/errors) — the single wire contract | api-defn, zio-json codecs, pagination; `tapir-core` + `tapir-json-zio` + `neotype-tapir` |
| `api/external-api-adapters/tapir-http` | JVM + JS | One `TapirGitHubGatewayClient(backend, baseUri)` implementing the trait via `SttpClientInterpreter` | api-defn, tapir-endpoints; `tapir-sttp-client` |

Why split, not one module:
- **`api-defn` stays Tapir-free** so the trait can be implemented over a different transport later, and the SPA can depend on the pure contract.
- **`tapir-endpoints` carries only the define-endpoints surface** (no server interpreter, no client interpreter). The server adds `tapir-zio-http-server`; the client adds `tapir-sttp-client`. Dependency surfaces stay minimal.
- **One cross-built `tapir-http`** because Tapir endpoints + `SttpClientInterpreter` compile and link on Scala.js. The JVM injects its backend via a `ZLayer` (in shared `src/`); the browser supplies `FetchZioBackend` from a JS-only `src-js/` entry point (`SttpFetchGitHubGatewayClient.apply`). `SttpBackend[F, +P]` is covariant in capabilities, so a `FetchZioBackend` passes where `SttpBackend[Task, Any]` is expected.

Endpoints that legitimately differ between server and client (e.g. a 302-redirect `callback`) are defined once with the **server's** canonical shape; the client adapts the output (e.g. issues the request with `followRedirects(false)` and discards the redirect). Server-only routes (e.g. inbound `webhook`) also live here as the single source of truth even though no typed client calls them.

Cross-built module shape in `build.mill` (mirror `api-defn`): a `trait Shared extends UbcScalaModule` that points `sources` at `moduleDir / os.up / "src"`, an `object jvm extends Shared`, and an `object js extends Shared with UbcScalaJSModule`. Put `-Xmax-inlines 64` (Tapir Schema derivation for `Page[A]`) and any `_sjs1_3` artifact overrides on the concrete `jvm`/`js` objects, **not** on `Shared` — the `UbcScalaJSModule` mixin can shadow a `scalacOptions` override placed only on `Shared`.

## Module wiring

```
domainPublic ──→ api/api-defn (trait + ApiError)        domainPrivate ──→ core/ports
                        │                                                      │
                        ↓                                            core/adapters/<tech>-<name>
        api/shared-adapter-dependencies/tapir-endpoints  (the wire contract; cross-built)
                 │                               │
                 ↓                               ↓
   api/internal-api-adapters/http       api/external-api-adapters/tapir-http
   (server; + core-impl,                (typed client, jvm+js; + tapir-sttp-client)
    tapir-zio-http-server)                       │
                 │                               ↓
               server                   presentation (SPA, uses tapir-http.js)
       (only place that wires adapters)
```

`api/api-defn` depends only on `domainPublic` — it must never import `domainPrivate` types, and never depend on Tapir. `api/shared-adapter-dependencies/tapir-endpoints` and `api/external-api-adapters/tapir-http` are cross-built (jvm + js).

In `build.mill`, each driven adapter lists `ports` in `moduleDeps` plus its infrastructure deps. The driving adapter lists `shared-adapter-dependencies.tapir-endpoints.jvm` and `core-impl`; the typed client lists `shared-adapter-dependencies.tapir-endpoints` (jvm/js). Adapters never depend on each other, and the server/client never depend on one another's modules.

## Related skills

- **`relational-database-modeling`** — Magnum SQL adapter, DbCodec wiring, Flyway migrations
- **`feature-tdd`** — full vertical-slice TDD for ports + adapters; layer templates with concrete patterns; `service-setup.md` for new-service module scaffolding

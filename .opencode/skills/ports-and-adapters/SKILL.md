---
name: ports-and-adapters
description: Use when adding a new port trait, implementing a new adapter (repository, HTTP client, or in-memory stub), or understanding how core/ports and core/adapters modules relate in a service.
---

# Ports and Adapters

The core domain is isolated from infrastructure through port traits; adapters wire infrastructure to those traits.

- **Port** — a trait in `core/ports/` declaring what the core needs, using domain types and `zio.Task` only. No infrastructure types cross this boundary.
- **Driven adapter** (outbound) — implements a port against real infrastructure (e.g., `magnum-token-repository`, `tapir-github-client`). Lives in `core/adapters/`.
- **Driving adapter** (inbound) — receives external requests and delegates immediately to core. Lives in `api/internal-api-adapters/`. No business logic — request decoding only.
- **In-memory adapter** — implements a port using `Ref`-backed state. Used for local dev and testing. Also lives in `core/adapters/`.

## Naming philosophy

**Ports are abstractions — names say WHAT, not HOW.** Infrastructure never appears in a port name.

**Adapters are concrete — names say exactly HOW they are implemented.** Lean into technology names: the reader should know the full stack from the class name alone.

| Type | Example names | Why |
|---|---|---|
| Port | `TokenRepository`, `GitHubPort`, `GitHubGatewayApi` | Pure intent, zero infra |
| Driven adapter | `MagnumTokenRepository`, `TapirGitHubClient`, `TapirSttpGitHubGatewayClient` | Tech stack in the name |
| Driving adapter | `GitHubGatewayHttpController` | Protocol + role in the name |
| In-memory adapter | `InMemoryTokenRepository` | Storage mechanism explicit |
| JS adapter | `SttpFetchGitHubGatewayClient` | sttp + browser Fetch API — not "Tapir" if Tapir isn't used |

When two adapters implement the same port with different technology (e.g., Tapir+sttp JVM vs sttp+Fetch JS), the name must distinguish them completely — `TapirSttpGitHubGatewayClient` vs `SttpFetchGitHubGatewayClient`.

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
- Import only domain types and `zio.Task` — never Magnum, Tapir, JDBC, or any infrastructure
- Return `Task[A]` for operations that can fail with defects, or `IO[E, A]` for typed errors
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

Driving adapters translate external requests into core calls. They define endpoint shapes (Tapir), decode requests, and delegate — nothing else.

```scala
// Inbound HTTP adapter: decodes requests, delegates to core, no business logic.
object GitHubGatewayHttpController:
  def routes(service: GitHubGatewayService, tracing: Tracing): Routes[Any, Response] =
    val getRepo = getRepoEndpoint.zServerLogic[Any] { case (owner, repo) =>
      service.fetchRepo(...).mapError(_.getMessage)
    }
    interpreter.toHttp(getRepo) ++ ...

  val layer: ZLayer[GitHubGatewayService & Tracing, Nothing, Routes[Any, Response]] =
    ZLayer.fromFunction(routes)
```

In `build.mill`, `api/internal-api-adapters/<tech>` depends on `api/api-defn` and `core/core-impl` — never on ports directly.

## Module wiring

```
domainPublic / domainPrivate
        ↑
   core/ports          api/api-defn
        ↑                    ↑
core/adapters/<tech>-<name>  api/internal-api-adapters/<tech>
        ↑                    ↑
              server
     (only place that picks which adapters to wire)
```

In `build.mill`, each driven adapter lists `ports` in `moduleDeps` plus its infrastructure deps. Each driving adapter lists `api-defn` and `core-impl`. Adapters never depend on each other.

## Related skills

- **`relational-database-modeling`** — Magnum SQL adapter, DbCodec wiring, Flyway migrations
- **`scala-zio`** — ZIO layer composition, service pattern

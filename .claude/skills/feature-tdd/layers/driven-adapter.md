# Driven Adapter Layer — Sub-Agent Instructions

You are implementing a driven (outbound) adapter — the infrastructure implementation of a port trait. The infrastructure could be anything: a relational database, a NoSQL store, an HTTP API, a message queue, gRPC, GraphQL, or any other outbound concern. The adapter pattern is the same regardless of technology.

## Naming rule (critical)

The class name must describe the full tech stack so the reader knows exactly what technology is in use. `MagnumOrgRepository`, `TapirSttpGitHubClient`, `SqsNotificationPublisher` are correct. `OrgRepository`, `GitHubClient`, `NotificationPublisher` are wrong — those are port names, not adapter names.

## General pattern

1. Implement the port trait
2. Take infrastructure dependencies (connection pool, client, credentials) via constructor
3. Expose a `ZLayer` in the companion object
4. Adapt infrastructure-specific errors to domain errors at this boundary

```scala
class <Tech><Name>(dep: InfraDep) extends <Port>:
  def portMethod(input: DomainType): IO[DomainError, Result] =
    dep.doInfraStuff(input)
      .mapError(DomainError.fromInfraError)  // error adapter at the boundary

object <Tech><Name>:
  val layer: ZLayer[InfraDep, Nothing, <Port>] =
    ZLayer.fromFunction(new <Tech><Name>(_))
```

## Choose the right reference

Pick the reference that matches your adapter's technology:

- **PostgreSQL via Magnum** → [`adapter-examples/magnum-repository.md`](adapter-examples/magnum-repository.md)
- **HTTP client via Tapir + sttp (JVM)** → [`adapter-examples/tapir-sttp-http-client.md`](adapter-examples/tapir-sttp-http-client.md)

For technologies not listed above, follow the same principles: name the class for the full stack, implement the port trait, adapt errors at the boundary, test with whatever stub or in-memory equivalent the library provides.

## TDD cycle (Iron Law — no exceptions)

**RED** — Write one failing test for one port method. Run it. Confirm it fails because the implementation does not exist.

**GREEN** — Write the minimal implementation for that one method. Run the test. Confirm it passes.

**REFACTOR** — Naming matches the full tech stack? Errors adapted to domain types at the boundary?

Repeat for each port method.

## Report back

When complete:
1. **Adapter implemented** — class name, port it implements, each method
2. **Tests written** — one line per test: what it verifies
3. **Infrastructure assumptions** — schema, endpoint URLs, queue names, any constraints relied upon (e.g. `ON CONFLICT (name)` requires `UNIQUE (name)` in the migration)
4. **Deviations from plan** — anything that changed
5. **Proposed amendments** — changes needed to the driving adapter layer or server wiring

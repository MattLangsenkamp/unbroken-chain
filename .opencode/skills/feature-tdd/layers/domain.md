# Domain Layer — Sub-Agent Instructions

You are implementing the domain layer of a feature. Define the new domain types this feature introduces. No infrastructure. No business logic.

## What to build

Domain types live in:
- `<service>/domainPublic/src/ubc/<service>/domain/` — types shared with the frontend or other services (cross-compiled JVM + JS)
- `<service>/domainPrivate/src/ubc/<service>/domain/internal/` — types private to this service (JVM only)

Use neotype `Newtype` for primitive wrappers:
```scala
import neotype.*

object OrgId extends Newtype[Long]
type OrgId = OrgId.Type

object OrgName extends Newtype[String]
type OrgName = OrgName.Type
```

Use `case class` for multi-field entities. Derive `JsonCodec` for types that cross HTTP boundaries:
```scala
import zio.json.JsonCodec
import java.time.Instant

case class GitHubOrg(
  id: OrgId,
  name: OrgName,
  createdAt: Instant
) derives JsonCodec
```

**Never import** Magnum, Tapir, JDBC, or any infrastructure in domain files.

## TDD cycle (Iron Law — no exceptions)

**RED** — Write one failing test that constructs or uses the type you are about to define.

```scala
// In domainPublic/test/src/ubc/<service>/domain/OrgDomainSpec.scala
object OrgDomainSpec extends ZIOSpecDefault:
  override def spec = suite("OrgDomainSpec")(
    test("OrgId wraps and unwraps correctly") {
      val id = OrgId(42L)
      assertTrue(id.unwrap == 42L)
    },
    test("OrgName wraps and unwraps correctly") {
      val name = OrgName("my-org")
      assertTrue(name.unwrap == "my-org")
    }
  )
```

Run: `./mill <service>.domainPublic.jvm.test`
Expected: compilation failure — type not yet defined.

**GREEN** — Define the type. Run the test again.
Expected: PASS.

**REFACTOR** — Minimum fields only. Right module (public vs private)?

Repeat for each type.

## Naming rules
- Type names reflect the domain concept, never the infrastructure: `OrgId` not `OrgDatabaseId`
- Newtypes for every primitive that has domain meaning: IDs, names, tokens, scopes

## Report back

When complete:
1. **Types defined** — name, location (public/private), what it wraps or its fields
2. **Tests written** — one line per test: what it verifies
3. **Deviations from plan** — anything that changed from the hypothesis
4. **Proposed amendments** — changes needed to the ports, core, or adapter layers

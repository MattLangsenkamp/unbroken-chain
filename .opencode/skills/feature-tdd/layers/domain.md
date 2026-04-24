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

Use `case class` for multi-field entities. Do not derive any codec in the domain type itself:
```scala
import java.time.Instant

case class GitHubOrg(
  id: OrgId,
  name: OrgName,
  createdAt: Instant
)
```

**Never import** Magnum, Tapir, JDBC, ZIO JSON, or any infrastructure in domain files.

## Prefer domain types directly

Do not introduce a separate "row" or "DTO" type for an adapter unless the schema or wire format genuinely cannot be expressed with the domain type. Any case class whose fields all have codec instances in scope can be serialised directly — no shadow type needed.

Only introduce a row/DTO model when the external shape diverges from the domain (e.g. a normalised collection stored as a delimited string, a legacy schema, a third-party API field that doesn't map cleanly). When you do, keep the row type inside the adapter module and use [Chimney](https://github.com/scalalandio/chimney) for the domain ↔ row transformation:

```scala
// Inside the adapter module — never in the domain
import io.scalaland.chimney.dsl.*

case class GitHubOrgRow(id: Long, name: String, tags: String) derives DbCodec

object GitHubOrgRow:
  def fromDomain(o: GitHubOrg): GitHubOrgRow = o.into[GitHubOrgRow]
    .withFieldComputed(_.tags, _.tags.mkString(","))
    .transform

  extension (row: GitHubOrgRow)
    def toDomain: GitHubOrg = row.into[GitHubOrg]
      .withFieldComputed(_.tags, _.tags.split(",").toList.filter(_.nonEmpty))
      .transform
```

Default to no row type. Add one only when the shape mismatch forces it.

## Adapter-extension modules

Infrastructure-specific codec instances (Magnum `DbCodec`, ZIO JSON `JsonCodec`, Tapir `Schema`) live in separate per-adapter extension modules — never in the domain type itself. This means each infrastructure dependency is only pulled in by the adapter that actually needs it.

```
domain/
  domainPublic/                          # pure types, no infrastructure
  domainPrivate/                         # internal types, no infrastructure
  domainPublicAdapterExtensions/
    magnum/src/.../adapters/magnum/      # DbCodec instances for public types
    zio-json/src/.../adapters/json/      # JsonCodec instances for public types
  domainPrivateAdapterExtensions/
    magnum/src/.../adapters/magnum/      # DbCodec instances for private types
    zio-json/src/.../adapters/json/      # JsonCodec instances for private types
```

**Why this matters — it's not just Scala.js:** Pulling infrastructure codecs into the domain type itself poisons every module that imports it:
- A Scala.js frontend module (`domainPublic.js`) that imports a type with `derives JsonCodec` gets the zio-json JVM codec dragged in at link time, breaking the JS build.
- A pure JVM adapter (e.g. a Magnum repo) that only needs `DbCodec` would unnecessarily acquire a zio-json compile dependency, inflating its classpath and risking transitive version conflicts.
- Two adapters that share a domain type but use different serialisation stacks (JSON vs Protobuf, Magnum vs JDBC) would both be forced to depend on the other's library.

Define only the core type here. Adapter-extension modules (and their infrastructure deps) are added when you build the driven adapter that needs them.

See `adapter-extension-examples/` for concrete build.mill and codec patterns.

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

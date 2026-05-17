# Domain Layer — Sub-Agent Instructions

You are implementing the domain layer of a feature. Define the new domain types this feature introduces. No infrastructure. No business logic.

## What to build

Domain types live in:
- `<service>/domainPublic/src/ubc/<service>/domain/` — types shared with the frontend or other services (cross-compiled JVM + JS)
- `<service>/domainPrivate/src/ubc/<service>/domain/internal/` — types private to this service (JVM only)

### Core principles

- All data is **immutable** — `val` everywhere, never `var`
- Case classes for product types, Scala 3 `enum` for sum types, neotype `Newtype` for primitive wrappers
- **Avoid raw primitives** (`String`, `Int`, `Float`, `Boolean`) for domain values — wrap them in newtypes
- **No infrastructure** in domain files — never import Magnum, Tapir, JDBC, ZIO JSON, or any codec library. The single exception is `zio-config` inside `domainPrivate`: config types live there and the server module always pulls them in anyway, so isolating `zio-config` from that classpath buys nothing

### Newtypes with neotype

[neotype](https://github.com/kitlangton/neotype) creates zero-cost newtypes with optional compile-time or runtime validation.

Simple newtype (no validation):
```scala
import neotype.*

object OrgId extends Newtype[Long]
type OrgId = OrgId.Type

object OrgName extends Newtype[String]
type OrgName = OrgName.Type
```

With validation:
```scala
import neotype.*

object Title extends Newtype[String]:
  override inline def validate(value: String): Boolean | String =
    if value.nonEmpty then true
    else "Title must not be empty"

type Title = Title.Type
```

Constructing and unwrapping:
```scala
val id: OrgId   = OrgId(42L)
val raw: Long   = id.unwrap   // requires `import neotype.*`
```

### Case classes and enums

`case class` for product types — immutable by default, with structural equality, `copy`, and pattern matching:
```scala
import java.time.Instant

case class GitHubOrg(
  id: OrgId,
  name: OrgName,
  createdAt: Instant
)
```

Convenience methods are fine as long as they are **pure** (no side effects, no `Unit` returns):
```scala
case class FullName(first: FirstName, last: LastName):
  def display: String = s"${first.unwrap} ${last.unwrap}"
```

Scala 3 `enum` for sum types:
```scala
enum IngestionSource:
  case Wikipedia, Wikidata

enum Status:
  case Active
  case Inactive(reason: String)
```

Prefer `enum` over `sealed trait` + `case class`. Reach for `sealed trait` only when variants need to mix in interfaces.

### Newtype vs opaque type

Scala 3 has both. Default to neotype.

| Situation | Use |
|---|---|
| Domain ID or value with no validation | `Newtype` — free codecs via adapter-extension modules |
| Domain value with validation rules | `Newtype` with `validate` override |
| Internal-only alias, never serialised | `opaque type` |
| Need full control over representation, no codecs ever | `opaque type` |

Opaque type only makes sense when there are zero codec needs. Once you need a `JsonCodec`, `DbCodec`, or Tapir `Schema`, neotype's interop libraries make this trivial — opaque types do not.

### Model-to-model translations

Conversions between domain models and external representations (e.g. webhook payloads, search-index documents) belong in the **domain package** as Scala 3 extension methods, not in service implementations:

```scala
package ubc.<service>.domain.internal

import ubc.<service>.domain.IngestionEvent
import org.apache.lucene.document.{Document, Field, StringField, TextField}

extension (event: IngestionEvent)
  def toLuceneDocument: Document = ???
```

This applies only when the translation has no infrastructure dependency on the **input** side. Translations that consume codec instances belong in the adapter-extension module that owns the codec.

### What to avoid

| Pattern | Why | Alternative |
|---|---|---|
| `var x = ...` | Mutable state breaks reasoning | `val` + `copy` |
| `def process(name: String, id: String)` | Primitive confusion | `def process(name: Name, id: UserId)` |
| Mutable collections (`ArrayBuffer`, etc.) | Hidden mutation | `List`, `Vector`, `Seq` |
| Methods returning `Unit` on case classes | Implies side effect | Pure methods only |
| `null` | Partial values | `Option[A]` |
| `derives JsonCodec` / `derives DbCodec` on a domain type | Pulls infrastructure into the domain — see "Adapter-extension modules" below | Adapter-extension module |
| `implicit val` / `given` codec written by hand | Brittle boilerplate | `derives` in the adapter-extension module |

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

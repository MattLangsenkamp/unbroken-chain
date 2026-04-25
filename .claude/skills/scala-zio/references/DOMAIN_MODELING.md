# Domain Modeling Reference

This document covers conventions for modeling data in this project.

---

## Core Principles

- Follow standard Scala 3 and functional programming conventions
- All data is **immutable** — `val` everywhere, `var` is heavily discouraged
- Case classes and enums are the primary modeling tools
- **Avoid raw primitives** (`String`, `Int`, `Float`, `Boolean`) for domain values — wrap them in newtypes or opaque types instead

---

## Case Classes

Use `case class` for product types (data with multiple fields). Immutable by default, with structural equality, `copy`, and pattern matching built in.

```scala
case class Article(
  id: ArticleId,
  title: Title,
  content: Content,
  author: UserId
)
```

Convenience methods are fine as long as they are **pure** (no side effects, no mutation):

```scala
case class FullName(first: FirstName, last: LastName):
  def display: String = s"${first.value} ${last.value}"
```

Never define methods that mutate state or return `Unit`.

---

## Enums

Use Scala 3 `enum` for sum types:

```scala
enum IngestionSource:
  case Wikipedia, Wikidata

enum Status:
  case Active
  case Inactive(reason: String)
```

Prefer `enum` over `sealed trait` + `case class` for simple sum types. Use `sealed trait` only when you need more control (e.g., mixed-in interfaces on variants).

---

## Newtypes with Neotype

Avoid using primitives directly for domain values. A raw `String` for a user ID and a raw `String` for a queue URL are indistinguishable to the compiler — wrapping them catches mistakes at compile time and makes code self-documenting.

Use the [neotype](https://github.com/kitlangton/neotype) library. It creates zero-cost newtypes with optional compile-time or runtime validation.

### Simple newtype (no validation)

```scala
import neotype.*

object UserId extends Newtype[String]
type UserId = UserId.Type

object QueueUrl extends Newtype[String]
type QueueUrl = QueueUrl.Type
```

### Newtype with validation

```scala
import neotype.*

object Title extends Newtype[String]:
  override inline def validate(value: String): Boolean | String =
    if value.nonEmpty then true
    else "Title must not be empty"

type Title = Title.Type
```

### Constructing and unwrapping

```scala
val id: UserId = UserId("abc-123")
val raw: String = id.unwrap   // requires: import neotype.unwrap
```

---

## Opaque Types

Use Scala 3 `opaque type` when:
- You want a zero-cost type alias with no runtime wrapper
- The type needs **no validation** and **no library integration** (no JSON codec, no DB codec)
- You want to hide the underlying representation entirely within a module

```scala
object Ids:
  opaque type CorrelationId = String
  object CorrelationId:
    def apply(s: String): CorrelationId = s
    extension (id: CorrelationId) def value: String = id
```

If you need JSON codecs, DB codecs, or validation, prefer **neotype** — deriving those integrations manually for opaque types is tedious.

---

## Choosing Between Newtype and Opaque Type

| Situation | Use |
|---|---|
| Domain ID or value with no validation | `Newtype` (neotype) — free codecs via extensions |
| Domain value with validation rules | `Newtype` with `validate` override |
| Internal-only alias, never serialized | `opaque type` |
| Need full control over representation, no codec | `opaque type` |

When in doubt, default to neotype.

---

## Codecs and `derives`

### Always use `derives` syntax

```scala
// correct
case class Article(id: ArticleId, title: Title) derives JsonCodec

// avoid — manual codec is unnecessary boilerplate
object Article:
  given JsonCodec[Article] = DeriveJsonCodec.gen[Article]
```

Enums derive the same way:

```scala
enum IngestionSource derives JsonCodec:
  case Wikipedia, Wikidata
```

### Codecs live next to the type definition

Define codecs in the **same file as the type**. Do not collect them in a separate `Codecs.scala` file.

```scala
// domainPublic/src/Article.scala
package app.myservice.domain

import zio.json.*
import neotype.*
import neotype.ziojson.*

object ArticleId extends Newtype[String]
type ArticleId = ArticleId.Type

case class Article(id: ArticleId, title: String) derives JsonCodec
```

### ZIO JSON + neotype

Add `neotypeDeps.withJson` to the module. Import `neotype.ziojson.*` to enable automatic newtype codec derivation — no manual codec needed.

```scala
import neotype.*
import neotype.ziojson.*
import zio.json.*

object UserId extends Newtype[String]
type UserId = UserId.Type

case class User(id: UserId, name: String) derives JsonCodec
```

---

## Model-to-Model Translations

Conversions between internal domain models and external representations belong in the **domain package**, not in service implementations.

Use Scala 3 **extension methods**:

```scala
// writer/domainPrivate/src/LuceneConversions.scala
package app.writer.domain.internal

import app.ingestion.domain.IngestionEvent
import org.apache.lucene.document.{Document, Field, StringField, TextField}

extension (event: IngestionEvent)
  def toLuceneDocument: Document = ...
```

---

## What to Avoid

| Pattern | Why | Alternative |
|---|---|---|
| `var x = ...` | Mutable state breaks reasoning | `val` + `copy` |
| `def process(name: String, id: String)` | Primitive confusion | `def process(name: Name, id: UserId)` |
| Mutable collections (`ArrayBuffer`, etc.) | Hidden mutation | `List`, `Vector`, `Seq` |
| Methods returning `Unit` on case classes | Implies side effect | Pure methods only |
| `null` | Partial values | `Option[A]` |
| Codecs in a separate `Codecs.scala` | Hard to find, implicit leakage | `derives` codec next to the type |
| `implicit val` / `given` codec written by hand | Brittle boilerplate | `derives JsonCodec` |

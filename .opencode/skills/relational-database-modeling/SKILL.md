---
name: relational-database-modeling
description: Conventions for schema migrations, Magnum repository adapters, and DbCodec wiring in this project.
---

# Relational Database Modeling

This skill covers the full stack from SQL schema to domain type: where migrations live, how repositories implement port interfaces, and how to wire Magnum `DbCodec` instances through the adapter extension modules.

---

## Migrations

Migrations use [Flyway](https://flywaydb.org/) and live in `<service>/db/`:

```
<service>/
  db/
    Dockerfile          # FROM flyway/flyway:latest; COPY . /flyway/sql/
    V1__initial_schema.sql
    V2__add_column.sql  # versioned additions — never edit past migrations
```

### Naming convention

`V{version}__{description}.sql` — two underscores, version monotonically increasing. Flyway checksums past migrations; editing them breaks the deployment.

### Building and loading

```bash
# Build image + import into k3d in one step
make prepare-migrations

# Under the hood (bin/build-migrations.sh):
docker build -t unbrokenchain/<service>-migrations:latest <service>/db/
k3d image import unbrokenchain/<service>-migrations:latest --cluster unbroken-chain
```

The Helm chart runs a pre-install/pre-upgrade Job (`k8s/templates/migrate-job.yaml`) that executes `flyway migrate` against the service's database before the app pod starts.

### Column type mapping

| Scala type | SQL type |
|---|---|
| `Long` / `Newtype[Long]` | `BIGSERIAL` (PK) or `BIGINT` |
| `String` / `Newtype[String]` | `TEXT` |
| `Instant` | `TIMESTAMPTZ` |
| `Option[Instant]` | `TIMESTAMPTZ` (nullable — no `NOT NULL`) |
| `List[T]` (comma-encoded) | `TEXT NOT NULL DEFAULT ''` |

Magnum maps case class fields to columns by name using snake_case (`userId` → `user_id`, `createdAt` → `created_at`). Make your SQL column names match the snake_case of your Scala field names.

---

## Port interface

The port trait lives in `core/ports/` and declares what the repository must do in terms of domain types only — no Magnum, no SQL.

```scala
// core/ports/src/ubc/<service>/core/ports/MyRepository.scala
package ubc.<service>.core.ports

import zio.*

trait MyRepository:
  def save(entity: MyEntity): Task[Unit]
  def findById(id: MyId): Task[Option[MyEntity]]
  def delete(id: MyId): Task[Unit]
```

---

## Magnum repository adapter

The implementation lives in `core/adapters/magnum-<name>-repository/`. It depends on:
- `core/ports` (the trait it implements)
- `domain/domainPublicAdapterExtensions/magnum` (public domain `DbCodec`s)
- `domain/domainPrivateAdapterExtensions/magnum` (private domain `DbCodec`s, if needed)

```scala
// core/adapters/magnum-<name>-repository/src/.../MagnumMyRepository.scala
package ubc.<service>.core.adapters.magnum

import ubc.<service>.core.ports.MyRepository
import ubc.<service>.domain.*
import ubc.<service>.domain.internal.*
import ubc.<service>.domain.adapters.magnum.PublicMagnumCodecs.given
import ubc.<service>.domain.internal.adapters.magnum.PrivateMagnumCodecs.given
import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.*
import zio.*

// Derive a DbCodec for the entity using the imported given instances above.
private given DbCodec[MyEntity] = DbCodec.derived[MyEntity]

class MagnumMyRepository(xa: TransactorZIO) extends MyRepository:

  def save(entity: MyEntity): Task[Unit] =
    xa.transact {
      sql"""
        INSERT INTO my_table (col_a, col_b)
        VALUES (${entity.colA}, ${entity.colB})
      """.update.run()
    }.unit

  def findById(id: MyId): Task[Option[MyEntity]] =
    xa.connect {
      sql"SELECT id, col_a, col_b FROM my_table WHERE id = $id"
        .query[MyEntity].run().headOption
    }

  def delete(id: MyId): Task[Unit] =
    xa.transact {
      sql"DELETE FROM my_table WHERE id = $id".update.run()
    }.unit

object MagnumMyRepository:
  val layer: ZLayer[TransactorZIO, Nothing, MyRepository] =
    ZLayer.fromFunction(new MagnumMyRepository(_))
```

**`xa.connect`** — single read query, no transaction overhead.  
**`xa.transact`** — wraps in a transaction; use for writes or multi-statement reads.

The `SELECT` column order must match the case class field declaration order; Magnum maps by position.

---

## DbCodec wiring

Magnum cannot derive `DbCodec` instances for neotype newtypes automatically. Provide them in the adapter extension modules so the derivation chain works.

### Public codecs — `domain/domainPublicAdapterExtensions/magnum/`

For types in `domainPublic` (shared across services):

```scala
object PublicMagnumCodecs:
  given DbCodec[MyId]    = DbCodec[Long].biMap(MyId(_), _.unwrap)
  given DbCodec[MyToken] = DbCodec[String].biMap(MyToken(_), _.unwrap)
```

### Private codecs — `domain/domainPrivateAdapterExtensions/magnum/`

For types in `domainPrivate` (internal to this service):

```scala
object PrivateMagnumCodecs:
  given DbCodec[UserId]     = DbCodec[String].biMap(UserId(_), _.unwrap)
  given DbCodec[TokenScope] = DbCodec[String].biMap(TokenScope(_), _.unwrap)

  // Collections have no native ARRAY codec in Magnum — use comma-encoding.
  given DbCodec[List[TokenScope]] =
    DbCodec[String].biMap(
      s => s.split(",").toList.filter(_.nonEmpty).map(TokenScope(_)),
      _.map(_.unwrap).mkString(",")
    )
```

### Importing in the repository

Bring both sets of givens into scope before calling `DbCodec.derived`:

```scala
import ubc.<service>.domain.adapters.magnum.PublicMagnumCodecs.given
import ubc.<service>.domain.internal.adapters.magnum.PrivateMagnumCodecs.given

private given DbCodec[MyEntity] = DbCodec.derived[MyEntity]
```

`DbCodec.derived` is a macro that walks the case class fields and looks up a `DbCodec` for each field type. All field codecs must be in implicit scope at the derivation site.

---

## Module dependency graph

```
domainPublic
    ↑
domainPublicAdapterExtensions/magnum   ←── PublicMagnumCodecs
    ↑
domainPrivate
    ↑
domainPrivateAdapterExtensions/magnum  ←── PrivateMagnumCodecs
    ↑
core/ports
    ↑
core/adapters/magnum-<name>-repository  (imports both codec modules, derives DbCodec, implements port)
    ↑
server  (wires HikariMagnumTransactor.layer → MagnumMyRepository.layer)
```

The repository adapter module declares both codec extension modules in its `moduleDeps` in `build.mill`.

# Magnum Adapter Extension — Reference

Provides `DbCodec` instances for domain types. Lives in `domainPublicAdapterExtensions/magnum` and `domainPrivateAdapterExtensions/magnum`. Imported only by Magnum repository adapters — no other module acquires this dependency.

## Location

```
domain/domainPublicAdapterExtensions/magnum/src/ubc/<service>/domain/adapters/magnum/PublicMagnumCodecs.scala
domain/domainPrivateAdapterExtensions/magnum/src/ubc/<service>/domain/internal/adapters/magnum/PrivateMagnumCodecs.scala
```

## Implementation

Newtypes need a manual `biMap` — Magnum cannot derive `DbCodec` for opaque types automatically:

```scala
package ubc.<service>.domain.adapters.magnum

import ubc.<service>.domain.*
import OrgId.given, OrgName.given   // bring Newtype's unwrap/apply into scope
import com.augustnagro.magnum.*
import neotype.*

object PublicMagnumCodecs:
  given DbCodec[OrgId]   = DbCodec[Long].biMap(OrgId(_), _.unwrap)
  given DbCodec[OrgName] = DbCodec[String].biMap(OrgName(_), _.unwrap)
```

For `domainPrivateAdapterExtensions/magnum`, follow the same pattern for internal types (e.g. `TokenId`, `UserId`).

## Prefer domain types directly — no row models

Once `DbCodec` instances exist for the newtypes a domain case class is built from, Magnum's `DbCodec.derived[GitHubOrg]` works on the domain type directly. Do not introduce a `GitHubOrgRow` shadow type unless the schema cannot be expressed with the domain (see the "Prefer domain types directly" section in `layers/domain.md`).

In the repository adapter:
```scala
// Inside core/adapters/magnum-org-repository — derive directly on the domain type
private given DbCodec[GitHubOrg] = DbCodec.derived[GitHubOrg]

class MagnumOrgRepository(xa: TransactorZIO) extends OrgRepository:
  def findById(id: OrgId): Task[Option[GitHubOrg]] =
    xa.connect {
      sql"SELECT ... FROM github_orgs WHERE id = $id".query[GitHubOrg].run().headOption
    }
```

Only introduce a row type when the SQL schema diverges from the domain (e.g. a normalised collection stored as a delimited column). When you do, keep the row case class and the Chimney transformation inside the repo adapter module — never in this extension module.

## build.mill

Magnum is JVM-only — this module is not cross-compiled:

```scala
object domainPublicAdapterExtensions extends Module {
  object magnum extends ScalaModule {
    def scalaVersion = scalaVer
    override def moduleDeps = Seq(domainPublic.jvm)
    override def mvnDeps = magnumDeps
  }
}

object domainPrivateAdapterExtensions extends Module {
  object magnum extends ScalaModule {
    def scalaVersion = scalaVer
    // private types depend on domainPrivate; also import public codecs transitively
    override def moduleDeps = Seq(domainPrivate, domainPublicAdapterExtensions.magnum)
    override def mvnDeps = magnumDeps
  }
}
```

The Magnum repo adapter adds `domain.domainPublicAdapterExtensions.magnum` and `domain.domainPrivateAdapterExtensions.magnum` to its `moduleDeps`. A Tapir HTTP client that doesn't touch the DB adds neither — it stays free of Magnum entirely.

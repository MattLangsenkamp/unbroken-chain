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

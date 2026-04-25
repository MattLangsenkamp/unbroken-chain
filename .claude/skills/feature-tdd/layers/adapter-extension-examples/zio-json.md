# ZIO JSON Adapter Extension — Reference

Provides `JsonCodec` instances for domain types. Lives in `domainPublicAdapterExtensions/zio-json` (for types shared across services) or `domainPrivateAdapterExtensions/zio-json` (for internal types). Imported by driving adapters (HTTP controllers) and any module that serialises domain types over the wire.

## Location

```
domain/domainPublicAdapterExtensions/zio-json/src/ubc/<service>/domain/adapters/json/PublicJsonCodecs.scala
domain/domainPrivateAdapterExtensions/zio-json/src/ubc/<service>/domain/internal/adapters/json/PrivateJsonCodecs.scala
```

## Implementation

```scala
package ubc.<service>.domain.adapters.json

import ubc.<service>.domain.*
import zio.json.*
import neotype.interop.ziojson.given  // gives JsonCodec for all Newtype wrappers automatically

object PublicJsonCodecs:
  // Newtypes: summon the instance derived by neotype-zio-json interop
  given JsonCodec[OrgId]   = summon[JsonCodec[OrgId]]
  given JsonCodec[OrgName] = summon[JsonCodec[OrgName]]

  // Case classes: derive explicitly here, not in the domain type
  given JsonCodec[GitHubOrg] = DeriveJsonCodec.gen
```

## build.mill

The `zio-json` extension is a cross-compiled module (JVM + JS) so the frontend can import it alongside the JVM server:

```scala
object domainPublicAdapterExtensions extends Module {
  object `zio-json` extends Module {
    trait Shared extends ScalaModule {
      def scalaVersion = scalaVer
      override def sources = Task.Sources(moduleDir / os.up / "src")
      override def mvnDeps = zioJsonDeps ++ neotypeZioJsonDeps
    }
    object jvm extends Shared {
      override def moduleDeps = Seq(domainPublic.jvm)
    }
    object js extends Shared with ScalaJSModule {
      def scalaJSVersion = scalaJSVer
      override def moduleDeps = Seq(domainPublic.js)
      // Explicit _sjs1_3 suffix required — top-level `::` resolves JVM-only at link time
      override def mvnDeps = super.mvnDeps() ++ Seq(
        mvn"dev.zio::zio-json_sjs1_3:0.7.3"
      )
    }
  }
}
```

Modules that need JSON serialisation add `domainPublicAdapterExtensions.\`zio-json\`.jvm` (or `.js`) to their `moduleDeps`. Modules that don't (e.g. a pure Magnum repo) add nothing and stay free of the zio-json dependency.

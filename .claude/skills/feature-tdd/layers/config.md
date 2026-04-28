# Configuration Reference

How services read configuration. Use this when a layer needs a config value (an external URL, a TTL, a secret reference) — typically the core service or a driven adapter.

---

## Core principles

- Config is read from **environment variables** at startup
- Missing or invalid values **abort the process** with a structured error
- Every config field is mandatory — no Scala-side defaults
- Config types live in each service's `domainPrivate` module
- Never read environment variables directly inside service or adapter implementations — always go through a config layer

---

## Pattern: zio-config with automatic derivation

Use `zio-config-magnolia`. Annotate the case class with `derives Config` and load it via `ZIO.config[T]`.

```scala
package ubc.<service>.domain.internal

import zio.*
import zio.config.*
import zio.config.magnolia.*

final case class MyServiceConfig(
    queueUrl: String,
    timeoutMs: Long,
    maxRetries: Int
) derives Config

object MyServiceConfig:
  val layer: TaskLayer[MyServiceConfig] =
    ZLayer.fromZIO(ZIO.config[MyServiceConfig])
```

Wire the config provider once in `server/src/Server.scala`'s bootstrap:

```scala
object Server extends ZIOAppDefault:
  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.setConfigProvider(ConfigProvider.envProvider.snakeCase)
```

`envProvider` is case-insensitive. `.snakeCase` converts camelCase field names to snake_case lookup keys, so `queueUrl` is read from either `QUEUE_URL` or `queue_url`.

### Field-name conversion gotcha

`snakeCase` splits on digit-letter boundaries:
- `s3Bucket` → `s_3_bucket` (unexpected — env var becomes `S_3_BUCKET`)
- `storageBucket` → `storage_bucket` (correct)

Avoid digits inside field names. If you must use one, name the env var explicitly via `Config.string("S3_BUCKET")` rather than relying on derivation.

---

## Where config types live

Config types belong in `domainPrivate` — each service's internal types module:

```
<service>/
  domain/
    domainPrivate/src/
      ubc/<service>/domain/internal/
        MyServiceConfig.scala    ← here
```

Server bootstrap consumes them via `.provide(MyServiceConfig.layer, ...)`. They are never exposed across the service boundary.

If a config is shared across multiple services (e.g. `HikariMagnumTransactor`'s connection settings), it lives next to the consumer in the `common/<module>` directory rather than in any service's `domainPrivate`. That is the only exception.

---

## Structural requirements

- Every field is mandatory — no `Option` defaults unless the field is genuinely optional
- New config fields require corresponding environment variables in the deployment manifests (Kubernetes ConfigMaps, Helm values, Docker Compose, etc.)
- Never read environment variables directly inside service implementations — always go through a config layer

### No silent fallbacks

Never use `orElseSucceed`, `getOrElse`, or hardcoded fallback values on a `ZIO.config[T]` call:

```scala
// ❌ NEVER do this — a missing or misnamed env var silently uses the wrong value
ZIO.config[OtelConfig].orElseSucceed(OtelConfig("http://some-default:4317"))

// ✅ Fail loudly at startup so misconfiguration is caught immediately
ZIO.config[OtelConfig]
```

A silent default means a misconfigured deployment starts successfully, connects to the wrong endpoint, and produces no telemetry — the failure is invisible until someone notices missing data in dashboards. Failing at startup with a structured error message is always preferable.

---

## Dependency in build.mill

```scala
val zioConfigDeps = Seq(
  mvn"dev.zio::zio-config-magnolia:4.0.3"
)
```

Add `zioConfigDeps` to `mvnDeps` of any module that defines a config case class.

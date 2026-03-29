# Configuration Reference

This document covers the configuration pattern for ZIO services.

---

## Core Principles

- Config is read from **environment variables** at startup
- Missing or invalid values **abort the process** with a structured error
- Every config field is mandatory — no defaults in code
- Config types live in each service's `domainPrivate` module

---

## Pattern: zio-config with Automatic Derivation

Use `zio-config-magnolia` for all config classes. Annotate the case class with `derives Config` and load it via `ZIO.config[T]`.

```scala
package app.myservice.domain.internal

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

Wire the config provider in the `server` bootstrap so all config reads use environment variables in `SCREAMING_SNAKE_CASE`:

```scala
object Server extends ZIOAppDefault:
  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.setConfigProvider(ConfigProvider.envProvider.snakeCase.upperCase)
```

### Field Name Conversion

Field names automatically convert to `SCREAMING_SNAKE_CASE`. **Avoid digits in field names** — `zio-config`'s `snakeCase` splits on digit-letter boundaries:
- `s3Bucket` → `S_3_BUCKET` (unexpected)
- `storageBucket` → `STORAGE_BUCKET` (correct)

---

## Where Config Types Live

Config types belong in `domainPrivate`:

```
myService/
  domainPrivate/src/
    app/myservice/domain/internal/
      MyServiceConfig.scala   ← here
```

They are consumed in `server` via `provide(...)` but are never exposed outside the service.

---

## Structural Requirements

- Every field is mandatory — no `Option` defaults unless the field is genuinely optional
- New config fields require corresponding environment variables in deployment manifests (Kubernetes ConfigMaps, Docker Compose, etc.)
- Never read environment variables directly inside service implementations — always go through a config layer

---

## Dependency in build.sc

```scala
ivy"dev.zio::zio-config:4.0.3",
ivy"dev.zio::zio-config-magnolia:4.0.3"
```

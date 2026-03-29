# Configuration Reference

This document covers the configuration pattern for ZIO services.

---

## Core Principles

- Config is read from **environment variables** at startup
- Missing or invalid values **abort the process** with a structured error
- Every config field is mandatory — no defaults in code
- Config types live in each service's `domainPrivate` module

---

## Pattern: Manual Environment Variable Loading

Config classes are `final case class`es that load from environment variables via `ZLayer.fromZIO`. Use `System.env` to read each variable and fail immediately if it is absent or invalid.

```scala
package app.myservice.domain.internal

import zio.*

final case class MyServiceConfig(
  queueUrl: String,
  timeoutMs: Long,
  maxRetries: Int
)

object MyServiceConfig:
  val layer: TaskLayer[MyServiceConfig] =
    ZLayer.fromZIO:
      for
        queueUrl   <- env("MY_QUEUE_URL")
        timeoutMs  <- envLong("MY_TIMEOUT_MS")
        maxRetries <- envInt("MY_MAX_RETRIES")
      yield MyServiceConfig(queueUrl, timeoutMs, maxRetries)

  private def env(name: String): Task[String] =
    System
      .env(name)
      .someOrFail(new IllegalArgumentException(s"Missing required environment variable: $name"))

  private def envLong(name: String): Task[Long] =
    env(name).flatMap: value =>
      ZIO
        .attempt(value.toLong)
        .mapError(_ => new IllegalArgumentException(s"Invalid numeric value for $name: '$value'"))

  private def envInt(name: String): Task[Int] =
    env(name).flatMap: value =>
      ZIO
        .attempt(value.toInt)
        .mapError(_ => new IllegalArgumentException(s"Invalid numeric value for $name: '$value'"))
```

---

## Alternative: zio-config with Automatic Derivation

For services that prefer automatic derivation, use `zio-config-magnolia`:

```scala
import zio.config.*
import zio.config.magnolia.*

final case class SqsConsumerConfig(sqsQueueUrl: String) derives Config
```

Then in the companion object:
```scala
object SqsConsumerConfig:
  val layer: TaskLayer[SqsConsumerConfig] =
    ZLayer.fromZIO(ZIO.config[SqsConsumerConfig])
```

Wire the config provider in the `server` bootstrap:
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

For the manual pattern, no extra dependency is needed beyond ZIO core.

For `zio-config-magnolia` derivation:
```scala
ivy"dev.zio::zio-config:4.0.3",
ivy"dev.zio::zio-config-magnolia:4.0.3"
```

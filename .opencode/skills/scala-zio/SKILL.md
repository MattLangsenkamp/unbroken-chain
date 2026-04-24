---
name: scala-zio
description: Guidance for Scala 3 and ZIO ecosystem development — effect composition, streaming, HTTP services, functional patterns, and testing. Consult this skill when writing or reviewing any Scala/ZIO code.
---

# Scala / ZIO Skill

This skill provides guidance for Scala 3 and ZIO ecosystem development. It covers effect composition, streaming, HTTP services, functional patterns, domain modeling, configuration, and testing.

## Reference Files

Choose the reference most relevant to your task:

| Reference | When to use it |
|---|---|
| [`references/SERVICE_SETUP.md`](references/SERVICE_SETUP.md) | Project structure, build conventions, service patterns, ZIO layer wiring, `common` module |
| [`references/DOMAIN_MODELING.md`](references/DOMAIN_MODELING.md) | Case classes, enums, newtypes, opaque types, JSON codecs |
| [`references/CONFIGURATION.md`](references/CONFIGURATION.md) | Config classes, environment variables, `zio-config` derivation |

## Workflow

1. Identify which topic area applies to the task
2. Read the relevant reference file(s)
3. Apply the established conventions consistently
4. If unsure where to start, read `SERVICE_SETUP.md` first

## Key Principles

- **Immutability everywhere** — `val` only, no `var`
- **Typed errors** — prefer `IO[DomainError, A]`; use `Task[A]` only for unmodeled defects
- **ZIO Service Pattern** — services are plain `case class`es taking port deps via constructor; companion exposes a `ZLayer`. No backing trait, no `Live` suffix.
- **Ports and adapters** — domain logic isolated from infrastructure via port traits; see the `ports-and-adapters` skill
- **Composition over inheritance** — combine `ZLayer`s rather than subclassing
- **Wrap primitives** — use neotype `Newtype` for domain values instead of raw `String`/`Int`

## Quick Reference

### Service pattern (plain case class, no trait)

```scala
case class MyService(dep: MyDep, repo: MyRepository):
  def doThing(input: DomainInput): IO[DomainError, Result] = ???

object MyService:
  val layer: URLayer[MyDep & MyRepository, MyService] =
    ZLayer.fromFunction(MyService.apply)
```

### Wiring

Only call `.provide(...)` or `ZLayer.make` in the `server` module's `ZIOAppDefault`. Never wire layers inside service implementations.

```scala
object Server extends ZIOAppDefault:
  def run = appProgram.provide(
    MyService.layer,
    MagnumMyRepository.layer,
    // ... more layers
  )
```

For implementing a feature end-to-end (domain types, ports, core, adapters, controllers), use the **`feature-tdd`** skill — its layer templates contain the concrete TDD patterns and build.mill snippets for each layer.

For the architectural rules around ports and adapters, see the **`ports-and-adapters`** skill.

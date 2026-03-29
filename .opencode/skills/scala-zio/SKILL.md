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
| [`references/SERVICE_SETUP.md`](references/SERVICE_SETUP.md) | Project structure, build conventions, service patterns, ZIO layer wiring |
| [`references/DOMAIN_MODELING.md`](references/DOMAIN_MODELING.md) | Case classes, enums, newtypes, opaque types, JSON codecs |
| [`references/CONFIGURATION.md`](references/CONFIGURATION.md) | Config classes, environment variables, `zio-config` derivation |

## Workflow

1. Identify which topic area applies to the task
2. Read the relevant reference file(s)
3. Apply the established conventions consistently
4. If unsure where to start, read `SERVICE_SETUP.md` first

## Key Principles

- **Immutability everywhere** — `val` only, no `var`
- **Typed errors** — use `ZIO[R, E, A]` with specific error types, not `Task` everywhere
- **ZIO Service Pattern** — every service has a trait, a `Live` implementation, a `ZLayer`, and a **test implementation** backed by `Ref` or another in-memory construct
- **Composition over inheritance** — combine `ZLayer`s rather than subclassing
- **Derive codecs** — use `derives JsonCodec`, never write codec instances by hand
- **Wrap primitives** — use newtypes or opaque types for domain values instead of raw `String`/`Int`

## Quick Reference

### Basic ZIO Service Pattern

```scala
// Trait
trait MyService:
  def doThing(input: String): Task[Unit]

// Live implementation
final case class MyServiceLive(dep: MyDep) extends MyService:
  override def doThing(input: String): Task[Unit] = ???

object MyServiceLive:
  val layer: URLayer[MyDep, MyService] =
    ZLayer.fromFunction(MyServiceLive.apply)
```

### Effect Composition

```scala
for
  result <- myService.doThing("input")
  _      <- ZIO.logInfo(s"Done: $result")
yield result
```

### Running Effects

Only call `.provide(...)` or `ZLayer.make` in the `server` module's `ZIOAppDefault`. Never wire layers inside service implementations.

```scala
object Server extends ZIOAppDefault:
  def run = myProgram.provide(
    MyServiceLive.layer,
    MyDepLive.layer
  )
```

For full patterns — service structure, config, domain modeling, and server wiring — see the reference files above.

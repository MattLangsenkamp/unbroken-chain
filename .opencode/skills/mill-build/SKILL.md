---
name: mill-build
description: Guidance for working with the Mill build tool for Scala projects — commands, module configuration, dependency management, and service architecture.
---

# Mill Build System

This skill provides guidance for working with Mill for Scala projects.

## Core Concepts

Mill is a modern build tool for Scala that emphasizes:
- Fast incremental compilation
- Simple configuration using Scala code
- Built-in caching and parallelization
- Clean module hierarchy

## Project Structure

This project uses **programmatic build configuration** (`build.sc`):

```
build.sc                  # Build configuration
<service>/
  domainPublic/src/       # Public domain types
  domainPrivate/src/      # Internal types
  api/src/                # Endpoint definitions
  services/src/           # Service implementations
  server/src/             # Entry point / wiring
```

## Common Commands

### Building
```bash
./mill <module>.compile          # Compile a specific module
./mill __.compile                # Compile all modules
```

### Testing
```bash
./mill <module>.test             # Run tests for a module
./mill __.test                   # Run all tests
```

### Resolving Modules
```bash
./mill resolve _                 # Show top-level modules
./mill resolve __                # Show all modules recursively
./mill resolve myService.__      # Show all submodules of a service
```

### Cleaning & Running
```bash
./mill clean                     # Clean all build artifacts
./mill <module>.run              # Run a module's main class
```

## Module Configuration

Define all modules in `build.sc`. Dependency groups are declared as top-level `val`s and composed per module — never inline individual deps inside module definitions.

```scala
import mill._, scalalib._

val scalaVer = "3.3.7"

val zioDeps = Agg(
  ivy"dev.zio::zio:2.1.17",
  ivy"dev.zio::zio-streams:2.1.17"
)

val zioHttpDeps = Agg(
  ivy"dev.zio::zio-http:3.0.1"
)

object myService extends Module {
  object domainPublic extends ScalaModule {
    def scalaVersion = scalaVer
  }

  object domainPrivate extends ScalaModule {
    def scalaVersion = scalaVer
    def moduleDeps = Seq(domainPublic)
  }

  object api extends ScalaModule {
    def scalaVersion = scalaVer
    def moduleDeps = Seq(domainPublic)
    def ivyDeps = Agg(
      ivy"com.softwaremill.sttp.tapir::tapir-core:1.11.10"
    )
  }

  object services extends ScalaModule {
    def scalaVersion = scalaVer
    def moduleDeps = Seq(domainPublic, domainPrivate, api)
    def ivyDeps = zioDeps
  }

  object server extends ScalaModule {
    def scalaVersion = scalaVer
    def moduleDeps = Seq(domainPublic, domainPrivate, api, services)
    def ivyDeps = zioDeps ++ zioHttpDeps
  }
}
```

## Adding Dependencies

Use `ivy"groupId::artifactId:version"` format:
- `::` for Scala libraries (adds Scala version suffix automatically)
- `:` for Java libraries (no suffix)

```scala
def ivyDeps = Agg(
  ivy"dev.zio::zio:2.1.17",                   // Scala library
  ivy"org.apache.lucene:lucene-core:9.9.1"    // Java library
)
```

## Centralized Scala Version

Always define `scalaVersion` as a shared `val` at the top of `build.sc` and reference it in every module. Never hardcode it per module.

## Dependency Direction

The enforced module dependency graph is:

```
domainPublic
    ↑         ↑
domainPrivate  api
    ↑         ↑
       services
          ↑
        server
```

Cross-service dependencies are declared explicitly in `moduleDeps`:
```scala
def moduleDeps = Seq(domainPublic, api, otherService.domainPublic)
```

## Creating a New Service

1. Create directories:
```bash
mkdir -p <service>/{domainPublic,domainPrivate,api,services,server}/src
```

2. Add the service object to `build.sc` following the template above.

3. Verify and test:
```bash
./mill resolve <service>.__
./mill <service>.server.compile
```

## Troubleshooting

| Problem | Fix |
|---|---|
| Modules not recognized | Run `./mill clean && ./mill resolve __` to refresh |
| Scala version mismatch | Ensure all modules use the shared `scalaVer` val |
| Dependency not found | Check `::` vs `:` for Scala vs Java libraries |
| Circular dependency error | Review `moduleDeps` — cycles are not allowed |

## Service Architecture

Each service follows this 5-layer pattern:

1. **domainPublic** — Public types safe for other services to consume
2. **domainPrivate** — Internal types that never leave the service boundary
3. **api** — Declarative, effect-agnostic endpoint definitions
4. **services** — ZIO service implementations and business logic
5. **server** — `ZIOAppDefault` entry point; wires all layers together

## Additional Resources

- [Mill Documentation](https://mill-build.org/)
- [Mill ScalaLib](https://mill-build.org/mill/scalalib/intro.html)

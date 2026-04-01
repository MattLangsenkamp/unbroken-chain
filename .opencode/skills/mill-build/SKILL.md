---
name: mill-build
description: Guidance for working with the Mill build tool for Scala projects — commands, module configuration, dependency management, and service architecture.
allowed-tools: Bash
---

# Mill Build System

This skill provides guidance for working with Mill for Scala projects.

**This project uses Mill 1.x** — see the [Mill 1.x Migration](#mill-1x-changes) section for key differences from 0.11.x.

## Core Concepts

Mill is a modern build tool for Scala that emphasizes:
- Fast incremental compilation
- Simple configuration using Scala code
- Built-in caching and parallelization
- Clean module hierarchy

## Project Structure

This project uses **programmatic build configuration** (`build.mill`):

```
build.mill                # Build configuration (Mill 1.x uses .mill extension)
<service>/
  domainPublic/src/       # Public domain types
  domainPrivate/src/      # Internal types
  api/src/                # Endpoint definitions
  services/src/           # Service implementations
  server/src/             # Entry point / wiring
```

**Source file placement**: Source files go directly in the `src/` directory — do NOT use maven-style nested directories (e.g., `src/com/example/`). Place `.scala` files directly in `<module>/src/`.

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

### Docker
```bash
./mill <service>.server.docker.build    # Build Docker image for a service
```

## Mill 1.x Changes

Mill 1.x introduces several breaking changes from 0.11.x:

| 0.11.x | 1.x | Notes |
|--------|-----|-------|
| `build.sc` | `build.mill` | New file extension |
| `import mill._` | `import mill.*` | Scala 3 wildcard syntax |
| `ivy"group::artifact:version"` | `mvn"group::artifact:version"` | New dependency format |
| `def ivyDeps = Agg(...)` | `def mvnDeps = Seq(...)` | `Agg` → `Seq`, `ivyDeps` → `mvnDeps` |
| `import $ivy.\`...\`` | `//\| mvnDeps: ["..."]` | Header-based plugin imports |
| `import contrib.docker.DockerModule` | `import mill.contrib.docker.DockerModule` | Full path for contrib |

### Build Header

Mill 1.x uses a header comment for plugin dependencies:

```scala
//| mvnDeps: ["com.lihaoyi::mill-contrib-docker:$MILL_VERSION"]

package build

import mill.*
import mill.scalalib.*
import mill.contrib.docker.DockerModule
```

## Module Configuration

Define all modules in `build.mill`. Dependency groups are declared as top-level `val`s and composed per module — never inline individual deps inside module definitions.

```scala
//| mvnDeps: ["com.lihaoyi::mill-contrib-docker:$MILL_VERSION"]

package build

import mill.*
import mill.scalalib.*
import mill.contrib.docker.DockerModule

val scalaVer = "3.3.7"

val zioDeps = Seq(
  mvn"dev.zio::zio:2.1.17",
  mvn"dev.zio::zio-streams:2.1.17"
)

val zioHttpDeps = Seq(
  mvn"dev.zio::zio-http:3.0.1"
)

val tapirDeps = Seq(
  mvn"com.softwaremill.sttp.tapir::tapir-core:1.11.10"
)

// Shared trait for service modules with Docker support
trait ServiceModule extends ScalaModule with DockerModule {
  def scalaVersion = scalaVer
  override def mvnDeps = super.mvnDeps() ++ zioDeps

  trait ServiceDockerConfig extends DockerConfig {
    def baseImage = "eclipse-temurin:21-jre"
  }
}

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
    override def mvnDeps = super.mvnDeps() ++ tapirDeps
  }

  object services extends ScalaModule {
    def scalaVersion = scalaVer
    def moduleDeps = Seq(domainPublic, domainPrivate, api)
    override def mvnDeps = super.mvnDeps() ++ zioDeps
  }

  object server extends ServiceModule {
    def moduleDeps = Seq(domainPublic, domainPrivate, api, services)
    override def mvnDeps = super.mvnDeps() ++ zioHttpDeps

    object docker extends ServiceDockerConfig {
      def tags = List("unbrokenchain/my-service:latest")
    }
  }
}
```

## Adding Dependencies

Use `mvn"groupId::artifactId:version"` format:
- `::` for Scala libraries (adds Scala version suffix automatically)
- `:` for Java libraries (no suffix)

```scala
override def mvnDeps = super.mvnDeps() ++ Seq(
  mvn"dev.zio::zio:2.1.17",                   // Scala library
  mvn"org.apache.lucene:lucene-core:9.9.1"    // Java library
)
```

## Centralized Scala Version

Always define `scalaVersion` as a shared `val` at the top of `build.mill` and reference it in every module. Never hardcode it per module.

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

2. Add the service object to `build.mill` following the template above.

3. Verify and test:
```bash
./mill resolve <service>.__
./mill <service>.server.compile
```

## Provider Gateways

Provider gateway services live under the `provider-gateways/` directory:

```
provider-gateways/
  github-gateway/
    server/src/
  gitlab-gateway/
    server/src/
```

In `build.mill`, these are nested under a `provider-gateways` module:

```scala
object `provider-gateways` extends Module {
  object `github-gateway` extends Module {
    object server extends ServiceModule {
      object docker extends ServiceDockerConfig {
        def tags = List("unbrokenchain/github-gateway:latest")
      }
    }
  }
}
```

## Troubleshooting

| Problem | Fix |
|---|---|
| Modules not recognized | Run `./mill clean && ./mill resolve __` to refresh |
| Scala version mismatch | Ensure all modules use the shared `scalaVer` val |
| Dependency not found | Check `::` vs `:` for Scala vs Java libraries |
| Circular dependency error | Review `moduleDeps` — cycles are not allowed |
| `Agg` not found | Use `Seq` instead (Mill 1.x change) |
| `ivyDeps` not found | Use `mvnDeps` instead (Mill 1.x change) |

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
- [Mill Docker Contrib](https://mill-build.org/mill/contrib/docker.html)
- [Mill Migration Guide](https://mill-build.org/mill/migrating/migrating.html)

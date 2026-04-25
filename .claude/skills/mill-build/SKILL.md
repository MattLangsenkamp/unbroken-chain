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
  server/src/             # Entry point / wiring
  presentation/src/       # Tyrian SPA (if service has a frontend)
  k8s/                    # Helm chart
```

For services that adopt the full 5-layer pattern, the layout is:
```
<service>/
  domainPublic/src/       # Public domain types (cross-compiled JVM + JS if needed)
  domainPrivate/src/      # Internal types
  api/src/                # Endpoint definitions (cross-compiled JVM + JS if needed)
  services/src/           # Service implementations
  server/src/             # Entry point / wiring
  presentation/src/       # Tyrian SPA (optional)
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

### Docker (JVM services)
```bash
./mill <service>.server.docker.build    # Build Docker image for a JVM service
```

### Scala.js (presentation modules)
```bash
./mill <service>.presentation.fastLinkJS          # Dev build — fast, no DCE
./mill <service>.presentation.fullLinkJS          # Production build — optimised, DCE
./mill -w <service>.presentation.fastLinkJS       # Watch mode for dev
```

Output lands in `out/<service>/presentation/{fast,full}LinkJS.dest/main.js`.

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

Mill 1.x uses a header comment for plugin dependencies. This project uses both `mill-contrib-docker` and `mill-scalablytyped`:

```scala
//| mvnDeps: ["com.lihaoyi::mill-contrib-docker:$MILL_VERSION", "com.github.lolgab::mill-scalablytyped::0.4.1"]

package build

import mill.*
import mill.scalalib.*
import mill.scalajslib.*
import mill.contrib.docker.DockerModule
import com.github.lolgab.mill.scalablytyped.*
```

## Module Configuration

Define all modules in `build.mill`. Dependency groups are declared as top-level `val`s — never inline individual deps inside module definitions.

```scala
val scalaVer   = "3.6.4"
val scalaJSVer = "1.19.0"

val zioDeps = Seq(
  mvn"dev.zio::zio:2.1.17"
)

val tyrianDeps = Seq(
  // Single colon + explicit _sjs1_3 suffix required.
  // Mill doesn't auto-add the ScalaJS platform suffix to deps defined outside a module.
  // ::: adds the full Scala version (_3.6.4), not the platform suffix (_sjs1_3).
  mvn"io.indigoengine:tyrian-io_sjs1_3:0.14.0"
)
```

Note the `:::` (triple colon) for Tyrian — this is required for Scala.js full cross-version libraries.

### ServiceModule — JVM backend services

```scala
trait ServiceModule extends ScalaModule with DockerModule {
  def scalaVersion = scalaVer
  override def mvnDeps = super.mvnDeps() ++ zioDeps

  trait ServiceDockerConfig extends DockerConfig {
    def baseImage = "eclipse-temurin:21-jre"
  }
}

object myService extends Module {
  object server extends ServiceModule {
    object docker extends ServiceDockerConfig {
      def tags = List("unbrokenchain/my-service:latest")
    }
  }
}
```

### PresentationModule — Tyrian SPA frontend

Every per-service Tyrian app extends `PresentationModule`. `ScalablyTyped` is NOT in the base trait — opt in via `WithScalablyTyped` only when you have npm packages with TypeScript definitions. Build tools (`vite`, `typescript`) don't count; adding ScalablyTyped when there are no typed packages causes "All libraries in package.json ignored" and a compile failure.

```scala
trait PresentationModule extends ScalaJSModule {
  def scalaVersion   = scalaVer
  def scalaJSVersion = scalaJSVer
  override def mvnDeps = tyrianDeps
  override def moduleKind = ModuleKind.ESModule  // required — without it linker produces no output
}

// Opt in when you have npm packages with TypeScript definitions
trait WithScalablyTyped extends ScalablyTyped {
  override def scalablyTypedBasePath    = Task { moduleDir }
  override def scalablyTypedPackageJson = Task.Source { moduleDir / "package.json" }
}

object myService extends Module {
  object presentation extends PresentationModule   // base — no ScalablyTyped
  // object presentation extends PresentationModule with WithScalablyTyped  // opt-in
  object server extends ServiceModule { ... }
}
```

See `.claude/skills/presentation/SKILL.md` for the full presentation workflow.

### CrossPlatform — shared JVM + Scala.js modules

Use for `domainPublic` and `api` layers that the frontend needs to consume. Sources live in `<module>/src/` and are compiled to both targets:

```scala
trait CrossPlatform extends Module {
  trait Shared extends ScalaModule {
    def scalaVersion = scalaVer
    override def sources = Seq(PathRef(millSourcePath / os.up / "src"))
  }
  object jvm extends Shared
  object js extends Shared with ScalaJSModule {
    def scalaJSVersion = scalaJSVer
  }
}

object myService extends Module {
  object domainPublic extends CrossPlatform

  // api with explicit per-platform deps
  object api extends Module {
    trait Shared extends ScalaModule {
      def scalaVersion = scalaVer
      override def sources = Seq(PathRef(millSourcePath / os.up / "src"))
    }
    object jvm extends Shared { def moduleDeps = Seq(domainPublic.jvm) }
    object js  extends Shared with ScalaJSModule {
      def scalaJSVersion = scalaJSVer
      def moduleDeps = Seq(domainPublic.js)
    }
  }

  object server extends ServiceModule {
    def moduleDeps = Seq(domainPublic.jvm, api.jvm)
  }

  object presentation extends PresentationModule {
    override def moduleDeps = Seq(domainPublic.js, api.js)
  }
}
```

## Adding Dependencies

Use `mvn"groupId::artifactId:version"` format:
- `::` for Scala libraries (adds `_3` suffix automatically) — use inside a module definition
- `:` for Java libraries (no suffix)
- `:::` for full cross-version Scala libraries (adds `_3.6.4` — rarely needed)

**ScalaJS platform suffix caveat**: `::` and `:::` on deps defined in a `val` outside a module do NOT add the `_sjs1` platform suffix. For Tyrian, use an explicit artifact name with single `:`:

```scala
// In a top-level val (outside any module) — must use explicit platform artifact name
val tyrianDeps = Seq(
  mvn"io.indigoengine:tyrian-io_sjs1_3:0.14.0"   // single : + explicit _sjs1_3
)

// Inside a ScalaJSModule — :: picks up _sjs1_3 correctly
override def mvnDeps = super.mvnDeps() ++ Seq(
  mvn"dev.zio::zio:2.1.17",                    // Scala library → _3
  mvn"org.apache.lucene:lucene-core:9.9.1",    // Java library → no suffix
  mvn"io.indigoengine::tyrian-io:0.14.0"       // inside module → _sjs1_3
)
```

## Centralized Versions

Always use the shared `val`s at the top of `build.mill`. Never hardcode versions per module:

| Val | Value | Used by |
|---|---|---|
| `scalaVer` | `"3.6.4"` | All modules |
| `scalaJSVer` | `"1.19.0"` | All ScalaJS modules |

## Dependency Direction

The enforced module dependency graph for a full-stack service is:

```
domainPublic.jvm / domainPublic.js
         ↑                  ↑
  domainPrivate         api.jvm / api.js
         ↑                  ↑
       services          server      presentation
           ↑               ↑              ↑
           └───────────────┘    domainPublic.js, api.js
```

## Provider Gateways

Provider gateway services live under the `provider-gateways/` directory and are nested in `build.mill`:

```scala
object `provider-gateways` extends Module {
  object `github-gateway` extends Module {
    object presentation extends PresentationModule
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
| Dependency not found | Check `::` vs `:` vs `:::` for library type |
| Circular dependency error | Review `moduleDeps` — cycles are not allowed |
| `Agg` not found | Use `Seq` instead (Mill 1.x change) |
| `ivyDeps` not found | Use `mvnDeps` instead (Mill 1.x change) |
| ScalaJS module not found | Ensure `import mill.scalajslib.*` is present |
| `ModuleKind` not found | Add `import mill.scalajslib.api.ModuleKind` |
| ScalaJS linker produces empty output | `moduleKind = ModuleKind.ESModule` is required; also need `@JSExportTopLevel` on the app object |
| ScalablyTyped "All libraries ignored" | Only mix in `WithScalablyTyped` when you have npm packages with TypeScript definitions — `vite`/`typescript` don't count |
| ScalablyTyped fails at compile | `npm install` must run before `./mill compile` — ScalablyTyped reads `node_modules` during compilation |

## Additional Resources

- [Mill Documentation](https://mill-build.org/)
- [Mill ScalaLib](https://mill-build.org/mill/scalalib/intro.html)
- [Mill Docker Contrib](https://mill-build.org/mill/contrib/docker.html)
- [Mill Migration Guide](https://mill-build.org/mill/migrating/migrating.html)

---
name: presentation
description: Conventions for per-service Tyrian Scala.js frontend apps — module structure, dev workflow, production build, ScalablyTyped JS facades, and k8s wiring. Consult this before adding or modifying any presentation module.
---

# Presentation (Tyrian SPA) Skill

Each service that needs a frontend has its own `presentation/` directory at the same level as `k8s/` and `server/`. Presentations are **fully isolated** — no shared `node_modules`, `package.json`, or npm lock files across services. This is intentional.

## Versions

| Thing | Version |
|---|---|
| Tyrian | 0.14.0 |
| Scala | 3.6.4 (shared `scalaVer`) |
| Scala.js | 1.19.0 (`scalaJSVer`) |
| mill-scalablytyped | 0.4.1 |
| Vite | ^6.0.0 (requires Node ≥ 18) |

## Directory Layout

```
<service>/
  k8s/                          # Helm chart for the JVM backend
  server/src/                   # JVM backend source
  presentation/
    src/
      Main.scala                # Tyrian TyrianIOApp entry point + @JSExportTopLevel
    index.html                  # HTML entry — uses __SCALA_JS_MAIN__ placeholder
    vite.config.js              # Auto-detects repo root; handles dev/prod path switching
    package.json                # npm deps scoped here (NOT repo root); must include typescript
    nginx.conf                  # SPA routing: try_files → index.html fallback
    Dockerfile                  # nginx:alpine serving dist/ (build context = this dir)
    k8s/
      Chart.yaml
      values.yaml               # image.repository: unbrokenchain/<service>-presentation
      templates/
        deployment.yaml         # nginx on port 80, no env vars needed
        service.yaml            # ClusterIP port 80
```

## Mill Module

`PresentationModule` is defined in `build.mill`:

```scala
trait PresentationModule extends ScalaJSModule {
  def scalaVersion   = scalaVer
  def scalaJSVersion = scalaJSVer
  override def mvnDeps = tyrianDeps
  // ESModule is required for Tyrian/Cats Effect's @JSExportTopLevel entry point
  override def moduleKind = ModuleKind.ESModule
}
```

Key points:
- `tyrianDeps` uses single `:` with explicit `tyrian-io_sjs1_3` artifact name — Mill's `::` / `:::` don't add the ScalaJS platform suffix for deps defined outside a module
- `moduleKind = ESModule` is required; without it the linker produces no output
- `ScalablyTyped` is NOT in the base trait — opt in via `WithScalablyTyped` when you need JS facades

Add a presentation to a service with one line in `build.mill`:

```scala
object `my-service` extends Module {
  object presentation extends PresentationModule   // ← one line
  object server extends ServiceModule { ... }
}
```

## Scala Entry Point

Every `Main.scala` must include a `@JSExportTopLevel` **val** (not `def`) that calls `TyrianIOApp.launch`. Without it the ScalaJS linker produces no output.

**Critical**: use `val`, not `def`. A `def` creates a named export function that nobody calls. A `val` executes its right-hand side at module load time, which is what actually mounts the app. ES module scripts are deferred so the DOM is ready when the val initializes.

**Critical**: use `"#app"` (CSS ID selector), not `"app"` (which selects a `<app>` tag).

```scala
import tyrian.*
import tyrian.Html.*
import cats.effect.IO
import scala.scalajs.js.annotation.JSExportTopLevel

object Main extends TyrianIOApp[Msg, Model]:
  def router: Location => Msg = _ => Msg.NoOp   // required in Tyrian 0.14.0
  def init(flags: Map[String, String]): (Model, Cmd[IO, Msg]) = (Model.init, Cmd.None)
  def update(model: Model): Msg => (Model, Cmd[IO, Msg]) = case Msg.NoOp => (model, Cmd.None)
  def view(model: Model): Html[Msg] = div()(text("My Service"))
  def subscriptions(model: Model): Sub[IO, Msg] = Sub.None

case class Model()
object Model:
  val init = Model()

enum Msg:
  case NoOp

// val — RHS executes at module load time (ES module scripts are deferred, DOM is ready)
// "#app" is the CSS selector for <div id="app"> — bare "app" selects a <app> tag
@JSExportTopLevel("tyrianMain")
val tyrianMain: Unit = TyrianIOApp.launch(Map("#app" -> Main))
```

## Mill Commands

```bash
./mill my-service.presentation.fastLinkJS    # dev build (fast, no DCE)
./mill my-service.presentation.fullLinkJS    # production build (optimised, DCE)
./mill -w my-service.presentation.fastLinkJS # watch mode for dev
```

Output lands in:
- `out/my-service/presentation/fastLinkJS.dest/main.js`
- `out/my-service/presentation/fullLinkJS.dest/main.js`

## Dev Workflow

```bash
# Terminal 1 — watch Scala.js recompile
./mill -w my-service.presentation.fastLinkJS

# Terminal 2 — Vite dev server (hot reload for HTML/CSS, picks up new JS on rebuild)
cd my-service/presentation && npm install && npm run dev
```

`vite.config.js` defaults `SCALA_JS_DEST` to `fastLinkJS.dest/main.js` and allows Vite to serve files from the repo root so Mill's `out/` is accessible.

Vite 6 requires **Node ≥ 18**. Use `nvm use 20` if your shell defaults to an older version. The build script handles this automatically.

## Production Build

Use the parameterised script — never run these steps manually:

```bash
bin/build-presentation.sh <presentation_dir> <image_name> [cluster_name]

# Examples:
bin/build-presentation.sh ubc-control-plane/presentation unbrokenchain/ubc-control-plane-presentation:latest
bin/build-presentation.sh ubc-control-plane/presentation unbrokenchain/ubc-control-plane-presentation:latest unbroken-chain
```

Or via Make (preferred):

```bash
make build-ubc-presentation
make build-github-gateway-presentation
make build-presentations                         # all at once
make build-ubc-presentation CLUSTER_NAME=my-k3d  # build + import into k3d
```

The script order (order matters — ScalablyTyped and Vite both need `node_modules`):
1. `npm install` in the presentation dir
2. `./mill <module>.fullLinkJS`
3. Copy ScalaJS output into `.scala-js-out/main.js` inside the presentation dir (Vite cannot bundle files outside its root)
4. `SCALA_JS_DEST=<staged path> npm run build` → Vite writes `dist/`
5. Clean up `.scala-js-out/`
6. `docker build -t <image> <presentation_dir>` (context = presentation dir)
7. Optionally `k3d image import` if cluster name provided

## How `vite.config.js` Works

The config is self-contained and identical across all services — no hardcoded paths:

1. Walks up from `__dirname` until it finds `build.mill` → repo root
2. Computes `millModulePath = relative(repoRoot, __dirname)` (e.g. `ubc-control-plane/presentation`)
3. Constructs Mill's output path: `repoRoot/out/<millModulePath>/fastLinkJS.dest/main.js`
4. If `SCALA_JS_DEST` env var is set (by build script), uses that as the absolute path instead
5. Makes the path relative to `__dirname` for the HTML `src` attribute
6. Replaces `__SCALA_JS_MAIN__` placeholder in `index.html` using `{ order: 'pre' }` so the replacement runs before Vite resolves script imports

## Adding JS Libraries with ScalablyTyped

`PresentationModule` does NOT include `ScalablyTyped` by default. Mix in `WithScalablyTyped` when you need to generate Scala.js facades from TypeScript definitions. Requires at least one npm package with TypeScript types in `package.json` (build tools like `vite` and `typescript` themselves don't count).

```scala
// build.mill
object `my-service` extends Module {
  object presentation extends PresentationModule with WithScalablyTyped
}
```

Then add the npm package:
```json
{
  "dependencies": { "my-lib": "^1.0.0" },
  "devDependencies": { "@types/my-lib": "^1.0.0", "typescript": "^5.0.0", "vite": "^6.0.0" }
}
```

For custom JS wrapping without TypeScript definitions, use `@JSImport` directly:

```scala
import scala.scalajs.js
import scala.scalajs.js.annotation.*

@js.native @JSImport("my-lib", JSImport.Default)
object MyLib extends js.Object:
  def doThing(x: String): String = js.native
```

## `package.json` Requirements

Every presentation `package.json` must include `typescript` as a dev dependency — ScalablyTyped requires it even when not actively generating facades:

```json
{
  "devDependencies": {
    "vite": "^6.0.0",
    "typescript": "^5.0.0"
  }
}
```

## Adding a Presentation to a New Service

Checklist:

- [ ] `mkdir -p <service>/presentation/src`
- [ ] Copy `src/Main.scala`, `index.html`, `vite.config.js`, `package.json`, `nginx.conf`, `Dockerfile` from an existing presentation — update `<title>` in `index.html`, the text in `view()`, and the `"name"` in `package.json`
- [ ] `mkdir -p <service>/presentation/k8s/templates` — copy `Chart.yaml`, `values.yaml`, `templates/deployment.yaml`, `templates/service.yaml`; set `name` and `image.repository`
- [ ] Add `object presentation extends PresentationModule` to the service in `build.mill`
- [ ] Add image to `bin/load-images.sh` `IMAGES` array
- [ ] Add `deploy_service <name>-presentation "$REPO_ROOT/<service>/presentation/k8s"` to `bin/deploy-local.sh`
- [ ] Add image variable and Make targets to `Makefile` (follow `UBC_PRESENTATION_IMAGE` pattern)

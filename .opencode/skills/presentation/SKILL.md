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
| Vite | ^6.0.0 |

## Directory Layout

```
<service>/
  k8s/                          # Helm chart for the JVM backend
  server/src/                   # JVM backend source
  presentation/
    src/
      Main.scala                # Tyrian TyrianIOApp entry point
    index.html                  # HTML entry — uses __SCALA_JS_MAIN__ placeholder
    vite.config.js              # Auto-detects repo root; handles dev/prod path switching
    package.json                # npm deps scoped here (NOT repo root)
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

Every presentation module extends `PresentationModule` (defined in `build.mill`):

```scala
trait PresentationModule extends ScalaJSModule with ScalablyTypedModule {
  def scalaVersion   = scalaVer
  def scalaJSVersion = scalaJSVer
  override def mvnDeps = tyrianDeps
  override def scalablyTypedPackageJson = Task { PathRef(millSourcePath / "package.json") }
}
```

Add a presentation to a service by extending it:

```scala
object `my-service` extends Module {
  object presentation extends PresentationModule   // ← one line
  object server extends ServiceModule { ... }
}
```

Mill module path maps directly to `millSourcePath` = `my-service/presentation/`.

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

The script:
1. Runs `./mill <module>.fullLinkJS`
2. `npm install` in the presentation dir
3. `SCALA_JS_DEST=<absolute fullLinkJS path> npm run build` → Vite writes `dist/`
4. `docker build -t <image> <presentation_dir>` (context = presentation dir)
5. Optionally `k3d image import` if cluster name provided

## How `vite.config.js` Works

The config is self-contained and identical across all services — no hardcoded paths:

1. Walks up from `__dirname` until it finds `build.mill` → repo root
2. Computes `millModulePath = relative(repoRoot, __dirname)` (e.g. `ubc-control-plane/presentation`)
3. Constructs Mill's output path: `repoRoot/out/<millModulePath>/fastLinkJS.dest/main.js`
4. If `SCALA_JS_DEST` env var is set (by build script), uses that instead
5. Makes the path relative to `__dirname` for the HTML `src` attribute
6. Replaces `__SCALA_JS_MAIN__` placeholder in `index.html`

## Adding JS Libraries with ScalablyTyped

ScalablyTyped auto-generates Scala.js facades from TypeScript definitions.

1. Add the npm package and its `@types/*` to `<service>/presentation/package.json`:
   ```json
   {
     "dependencies": {
       "my-lib": "^1.0.0"
     },
     "devDependencies": {
       "@types/my-lib": "^1.0.0"
     }
   }
   ```
2. Run `npm install` in the presentation directory
3. Run `./mill my-service.presentation.compile` — ScalablyTyped generates facades
4. Import the generated types in Scala as normal

For custom JS wrapping without TypeScript definitions, use `@JSImport` directly:

```scala
import scala.scalajs.js
import scala.scalajs.js.annotation.*

@js.native @JSImport("my-lib", JSImport.Default)
object MyLib extends js.Object:
  def doThing(x: String): String = js.native
```

## Adding a Presentation to a New Service

Checklist:

- [ ] `mkdir -p <service>/presentation/src`
- [ ] Copy `src/Main.scala`, `index.html`, `vite.config.js`, `package.json`, `nginx.conf`, `Dockerfile` from an existing presentation — update `<title>` in `index.html` and the `"name"` in `package.json`
- [ ] `mkdir -p <service>/presentation/k8s/templates` — copy `Chart.yaml`, `values.yaml`, `templates/deployment.yaml`, `templates/service.yaml`; set `name` and `image.repository` in `Chart.yaml` and `values.yaml`
- [ ] Add `object presentation extends PresentationModule` to the service in `build.mill`
- [ ] Add image to `bin/load-images.sh` `IMAGES` array
- [ ] Add `deploy_service <name>-presentation "$REPO_ROOT/<service>/presentation/k8s"` to `bin/deploy-local.sh`
- [ ] Add image variable and Make targets to `Makefile` (follow `UBC_PRESENTATION_IMAGE` pattern)

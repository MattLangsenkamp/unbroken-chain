# Claude Context

This project uses **OpenCode** for agentic workflows. Always check the `.opencode/` directory before starting any task — it contains skills and context that define the conventions for this codebase.

## Skills

- `.opencode/skills/mill-build/` — Mill build commands, module structure, dependency management, ScalaJS/CrossPlatform/PresentationModule traits
- `.opencode/skills/scala-zio/` — Scala 3 / ZIO patterns, domain modeling, configuration, service setup
- `.opencode/skills/make-utils/` — Makefile and bin/ script conventions; all logic lives in bin/
- `.opencode/skills/local-dev/` — Local k3d cluster setup, kubeconfig, k9s, and dev environment lifecycle
- `.opencode/skills/k8s/` — Helm umbrella chart structure, adding services, adding operators
- `.opencode/skills/presentation/` — Per-service Tyrian SPA apps: dev workflow, production build, ScalablyTyped JS facades, nginx Docker, k8s wiring

More skills may be added over time — always check `.opencode/skills/` for the full list.

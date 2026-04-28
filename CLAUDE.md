# Claude Context

Project skills live under `.claude/skills/`. Always check that directory before starting any task — it contains skills and context that define the conventions for this codebase. Each skill's `SKILL.md` declares when to invoke it via the `description` frontmatter field.

## Skills

- `.claude/skills/mill-build/` — Mill build commands, module structure, dependency management, ScalaJS/CrossPlatform/PresentationModule traits
- `.claude/skills/make-utils/` — Makefile and bin/ script conventions; all logic lives in bin/
- `.claude/skills/local-dev/` — Local k3d cluster setup, kubeconfig, k9s, and dev environment lifecycle
- `.claude/skills/k8s/` — Helm umbrella chart structure, adding services, adding operators
- `.claude/skills/presentation/` — Per-service Tyrian SPA apps: dev workflow, production build, ScalablyTyped JS facades, nginx Docker, k8s wiring
- `.claude/skills/ports-and-adapters/` — Port traits, adapter naming, in-memory stubs
- `.claude/skills/relational-database-modeling/` — Flyway migrations, Magnum repositories, DbCodec wiring
- `.claude/skills/feature-tdd/` — Inside-out TDD for full vertical slices (domain → ports → core → driven → driving)

More skills may be added over time — always check `.claude/skills/` for the full list.

---
name: feature-tdd
description: Use when implementing a new feature from a spec — full vertical slice across domain types, ports, core logic, driven adapters, and driving adapters. Trigger when handed requirements or a spec for a new capability to build.
---

# Feature TDD

Implements a full vertical slice from a spec using inside-out TDD. Each layer is dispatched to a dedicated sub-agent that writes real code, runs TDD cycles, and reports findings. The orchestrator audits, compiles, commits, and revises the plan before dispatching the next layer.

**Layer order (inside-out):**
```
domain → ports + in-memory stubs → core service → driven adapters → driving adapter
```

## Phase 1: Plan (before writing any code)

Read the spec. Identify which layers this feature needs — not every feature touches all five. Save the plan to `docs/superpowers/plans/YYYY-MM-DD-<feature>.md`. Show it to the user and get confirmation before proceeding.

**Plan structure:**

```markdown
# <Feature Name> — Implementation Plan (Hypothesis)

> This is a hypothesis. It will be updated as TDD cycles reveal design decisions.

## Feature summary
[Domain types needed, ports affected, adapters to build — one paragraph]

## Layers
| # | Type | What to build | Test strategy | Known unknowns |
|---|---|---|---|---|
| 1 | domain | ... | ZIO Test unit tests | ... |
| 2 | ports | ... | In-memory stub exercises the contract | ... |
| 3 | core | ... | ZIO Test + in-memory adapters via ZLayer | ... |
| 4 | driven-adapter | ... | TestDatabase.suiteLayer + Testcontainers | ... |
| 5 | driving-adapter | ... | Tapir stub interpreter + in-memory core | ... |

## Dependency map
- Layer 2 needs from 1: [domain types]
- Layer 3 needs from 2: [port trait signatures, in-memory stub layer]
- Layer 4 needs from 2: [port trait, domain types]
- Layer 5 needs from 3: [service layer]

## Open questions
[Things that cannot be resolved until a TDD cycle runs]
```

Show the plan to the user and get confirmation before proceeding.

## Phase 2: Execution loop

### Branch check (before first layer)
If on `main` or a `claude/*` branch, create a feature branch:
```bash
git checkout -b feature/<slug>
```

### Per-layer loop

For each layer in the plan, in order:

**1. Dispatch sub-agent**

Read the matching layer template:
- `layers/domain.md` — new domain types
- `layers/ports.md` — port traits + in-memory stubs
- `layers/core.md` — core service logic
- `layers/driven-adapter.md` — Magnum repos, Tapir HTTP clients
- `layers/driving-adapter.md` — HTTP controllers

Build the sub-agent prompt by combining:

```
<full contents of layers/<type>.md>

---
## Feature context
<feature summary paragraph from the plan>

## Code from previous layers
<paste the actual .scala file contents produced by all previous sub-agents>

## Plan hypothesis for this layer
<the "what to build", "test strategy", and "known unknowns" row for this layer>

## Feature branch
<branch name>
```

Dispatch using the Agent tool. The sub-agent has full write access.

**2. Audit the sub-agent's output**

Check the report and code for:
- TDD compliance: tests written before implementation, each test watched to fail
- Port contract: implementation correctly satisfies the port trait signature
- Naming: port names infra-free, adapter names describe the full tech stack (`ports-and-adapters` skill)
- Compilation: run `./mill __.compile` — must pass clean with no errors or warnings

If any check fails, re-dispatch the same layer with the specific issue described. Do not proceed to the next layer.

**3. Commit**

```bash
git add <changed files>
git commit -m "feat(<service>): <what was implemented> [layer N/M]"
```

**4. Revise the plan**

Update `docs/superpowers/plans/YYYY-MM-DD-<feature>.md`:
- Mark the completed layer with a checkmark
- Record deviations from the hypothesis and why
- Update remaining layers if dependencies changed

Show the plan diff to the user. Proceed to the next layer.

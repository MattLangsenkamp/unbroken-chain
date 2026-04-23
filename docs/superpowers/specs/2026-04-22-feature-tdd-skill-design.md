# Feature TDD Skill Design

**Date:** 2026-04-22  
**Status:** Approved

## Overview

A skill that takes a feature spec and implements a full vertical slice — domain types, port traits, core business logic, driven adapters, and driving adapters — using TDD and a sub-agent-per-layer execution model. Each layer is a sequential dispatch: the sub-agent writes real code, runs TDD cycles, and reports findings. The orchestrator audits, commits, and revises the implementation plan before proceeding.

---

## Skill structure

```
.opencode/skills/feature-tdd/
  SKILL.md                 # Orchestration: planning, execution loop, audit
  layers/
    domain.md              # Sub-agent template: new domain types
    ports.md               # Sub-agent template: port traits + in-memory stubs
    core.md                # Sub-agent template: core service logic
    driven-adapter.md      # Sub-agent template: Magnum repos, Tapir HTTP clients
    driving-adapter.md     # Sub-agent template: HTTP controllers
```

`SKILL.md` contains only orchestration logic — no layer-specific TDD or test infrastructure detail. Each `layers/` file is a self-contained sub-agent prompt: it embeds the abbreviated Red-Green-Refactor cycle and the exact test infrastructure for that layer type.

Not every feature touches every layer. The planning phase identifies which layers are needed and skips the rest.

---

## Phase 1: Planning

On invocation the orchestrator produces an **implementation plan** before writing any code. The plan is explicitly labelled a hypothesis and contains:

- **Feature summary** — one paragraph restating the requirement in terms of the codebase (domain types needed, ports affected, adapters to build)
- **Layer list** — ordered inside-out (domain → ports → core → driven adapters → driving adapter), listing only layers this feature actually needs. Each entry includes: what to build, what tests will verify it, known unknowns
- **Dependency map** — what each layer needs from the one before (domain types, port signatures, in-memory stubs), so the orchestrator knows exactly what to pass to each sub-agent
- **Open questions** — things that can't be resolved until a TDD cycle runs

The plan is saved to `docs/superpowers/plans/YYYY-MM-DD-<feature>.md` and committed on the feature branch alongside the first layer's code, so it can be diffed against reality as layers complete. The orchestrator explicitly marks it: *"This is a hypothesis. Expect it to change."*

---

## Phase 2: Execution loop

### Before the first layer

Check whether a feature branch exists. If not, create one from `main` named `feature/<slug>`.

### Per-layer loop

For each layer in the plan, in inside-out order:

1. **Dispatch** — build the sub-agent prompt by combining:
   - The layer template from `layers/<type>.md`
   - Relevant code from all previous layers (port signatures, domain types, in-memory stubs)
   - The current plan hypothesis for this layer
   - The feature branch name

2. **Sub-agent executes** — runs TDD cycles inside the layer (RED → GREEN → REFACTOR, repeat). Reports back:
   - Tests written and what they verify
   - Implementation decisions and rationale
   - Deviations from the plan hypothesis
   - Proposed amendments to subsequent layers

3. **Orchestrator audits** — reviews the sub-agent's code and report for:
   - TDD compliance (tests written before code, tests watched to fail)
   - Correctness against the port contract
   - Naming conventions (`ports-and-adapters` skill: port names infra-free, adapter names describe the full stack)
   - Alignment with the plan hypothesis

   If the audit fails — TDD violated, naming wrong, contract broken — the orchestrator re-dispatches the same layer with the issue noted. It does not proceed to the next layer.

4. **Commit** — once audit passes, commit on the feature branch with a message describing the layer and iteration, e.g.:
   `feat(github-gateway): implement MagnumOrgRepository with integration tests [layer 3/5]`

5. **Plan revision** — update the hypothesis with any changes discovered, show the diff to the user, then proceed to the next layer.

---

## Layer template structure

Each file in `layers/` follows this shape:

```
## Context you've been given
[Slots: feature summary, previous layers' code, plan hypothesis for this layer]

## What to build
[Layer-specific: e.g. "a port trait in core/ports/ using only domain types and zio.Task"]

## Test infrastructure
[Exact setup for this layer type — TestDatabase.suiteLayer + Testcontainers for
driven-adapter.md; ZIO Test + Ref-backed in-memory stubs for core.md; etc.]

## TDD cycle (Iron Law — no exceptions)
RED   — write one failing test. Run it. Confirm it fails for the right reason.
GREEN — write minimal code to pass. Nothing more.
REFACTOR — clean up. Stay green. No new behaviour.
Repeat until the layer is complete.

## Naming rules
[Ports: infra-free names. Adapters: full tech stack in the name.
See ports-and-adapters skill for examples.]

## Report back
[Structured format: tests written, decisions made, plan deviations, proposed amendments]
```

The TDD section is intentionally abbreviated — the Iron Law and the three steps, nothing more. Sub-agents start cold so it must be present, but the full rationalization tables from `superpowers:test-driven-development` don't need to be repeated in every template.

The **test infrastructure section is the highest-value part** of each template. For `driven-adapter.md` this means: exact `TestDatabase.suiteLayer` wiring, `ZTestFramework` setting, `db-test-support` + `db-migrations` moduleDeps. For `core.md`: ZIO Test + in-memory adapter wiring. Things a cold sub-agent cannot infer from scratch.

---

## Implementation order

Inside-out, sequential. Each layer receives the actual code produced by previous layers:

```
domain.md → ports.md → core.md → driven-adapter.md → driving-adapter.md
```

Parallelism is not used — later layers depend on the concrete output of earlier ones, not just their interfaces.

---

## Key design decisions

| Decision | Choice | Rationale |
|---|---|---|
| Implementation order | Inside-out | Core logic tested independently before infrastructure is introduced |
| TDD approach | Embedded (abbreviated) in each layer template | Sub-agents start cold — TDD must be present in the prompt |
| Sub-agent write access | Full write access | Orchestrator audits output rather than applying it |
| Plan mutability | Hypothesis, revised after each layer | TDD cycles reveal design decisions that can't be known upfront |
| Commit cadence | After each passing layer audit | Creates stable, recoverable checkpoints |
| Layer templates | Separate files per layer type | Test infrastructure changes per layer independently of orchestration logic |

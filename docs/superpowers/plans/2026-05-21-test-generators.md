# Test-Data Generators — Implementation Plan (Hypothesis)

> This is a hypothesis. It will be updated as TDD cycles reveal design decisions.

## Goal

Make ZIO Test generators a first-class, always-available part of the dev lifecycle:
every domain model (public **and** private) gets a generator, generators live in
their own modules on the **test classpath only**, and a CI guard makes it impossible
for a deploy to bundle them. The end state: writing a property-based test is trivial
because a generator for every model already exists.

## Decisions (confirmed with user)

1. **Layout** — per-domain-module `generators` sub-modules (not one central module).
2. **Enforcement** — convention (`test.moduleDeps` only) **+ a CI guard** that fails
   if any server's runtime classpath contains a generators module or `zio-test`.
3. **Cross-compile** — public + pagination generators cross-compile (jvm + js);
   private generators are JVM-only (private domain is JVM-only).
4. **Style** — ZIO Test Magnolia `DeriveGen`: hand-write `DeriveGen.instance` for leaf
   neotypes; case classes / enums derive via `DeriveGen.gen[T]`.

## DeriveGen API (confirmed from sources, zio-test-magnolia 2.1.24)

- `DeriveGen[A]` — type class; `DeriveGen[A]` (apply) summons `Gen[Any, A]`.
- `DeriveGen.instance(gen)` — wrap a `Gen` as a `DeriveGen` (for leaf neotypes).
- `DeriveGen.gen[T](using Mirror.Of[T])` — derive for case class / enum.
- Built-in givens include `Int, Long, String, Boolean, Instant, UUID, Option, List,
  Seq, Set, Map, Either, tuples`. **Not** included: `zio.Duration` → provide one.
- `import zio.test.magnolia.*` brings these into scope.

## Modules to create (build.mill)

New shared dep vals:
```scala
val genDeps   = Seq(mvn"dev.zio::zio-test:2.1.24", mvn"dev.zio::zio-test-magnolia:2.1.24")
val genDepsJs = Seq(mvn"dev.zio:zio-test_sjs1_3:2.1.24", mvn"dev.zio:zio-test-magnolia_sjs1_3:2.1.24")
```

| Module | Platform | moduleDeps | Exposes generators for |
|---|---|---|---|
| `common.pagination.generators` | jvm + js | `pagination` | `PageRequest`, `Page[A]` (generic) |
| `…domainPublic.generators` | jvm + js | `domainPublic` | 14 neotypes, `AccountType`, `InstallationStatus`, `Installation`, `LinkedRepo`, `LinkInitiation`, `ReconcileSummary` |
| `…domainPrivate.generators` | jvm only | `domainPrivate`, `domainPublic.generators.jvm` | `AppJwt`, `InstallationAccessToken`, `Duration`, `WebhookHeaders`, `PendingLinkFlow`, `WebhookDelivery`, `WebhookOutcome`, `GithubWebhookEvent`, `GitHubGatewayConfig`, `LinkError`, `UnlinkError`, `ReconcileError`, `WebhookError` |

Generator objects (same package as the types they generate):
- `ubc.common.pagination.PaginationGenerators`
- `ubc.githubgateway.domain.DomainGenerators`
- `ubc.githubgateway.domain.internal.InternalDomainGenerators`

Each object provides a `given DeriveGen[T]` for **every** type in its companion module
(leaves via `instance`, products/sums via `gen[T]`). Private generators import the
public ones so derivations that reference public newtypes resolve.

Each generators module gets a JVM `test` sub-module whose spec `check`s every generator
(proves it compiles + samples). This is the TDD vehicle.

## CI guard

`bin/verify-no-test-deps-in-deploy.sh` (+ `make verify-deploy-deps`):
for every `*.server` module, run `./mill show <server>.runClasspath` and fail if it
contains `zio-test`, `zio-test-magnolia`, or a `/generators/` output path.

## Skill changes

1. `.claude/skills/feature-tdd/layers/domain.md` — add a mandatory step: every new
   domain type MUST get a `DeriveGen` in the module's `generators` companion; extend the
   TDD cycle and "Report back" accordingly.
2. New skill `.claude/skills/test-generators/SKILL.md` — the canonical reference: module
   layout, DeriveGen patterns (leaf vs derived), the MUST rule, sensitive-type generators
   (construct via `.sensitive` smart constructor), the deploy guard, and how to use a
   generator in any layer's test via `check`.
3. `.claude/skills/sensitive/SKILL.md` — note that a `Sensitive` newtype's generator must
   build through its smart constructor so the produced value carries the marker.

## TDD execution order

1. Add `genDeps`/`genDepsJs` + `pagination.generators` module skeleton.
2. `pagination.generators` (jvm first): RED spec → GREEN `PaginationGenerators` → js compiles.
3. `domainPublic.generators`: leaf neotypes first, then case classes/enums (RED→GREEN each).
4. `domainPrivate.generators`: `Duration` + token leaves, then products/sums.
5. `./mill __.compile` + `./mill __.test` clean (incl. js link of cross modules).
6. Guard script + make target; run it green; confirm it would catch a violation.
7. Skill updates.
8. Quality review (3 reviewer agents).

## Outcome (2026-05-21)

Delivered as planned. Deviations:
- **Generator value ranges:** kept full-range `Gen.long` / `Gen.string` (user chose max edge-case
  coverage over realistic defaults).
- **Sensitive retrofit (added scope):** `AppJwt` and `InstallationAccessToken` are now
  `& Sensitive` (intersection alias + `.sensitive` smart constructor). Call sites updated to
  `.sensitive`; the `& Sensitive` intersection breaks neotype's `.unwrap` extension, so boundary
  code now uses `AppJwt.unwrap(jwt)` (companion method). `domainPrivate` gained a `common.sensitive.jvm`
  dep. Proof: `summon[AppJwt <:< Sensitive]` in `TokensSpec`.
- `DeriveGen.gen` handled the parameterised enum `GithubWebhookEvent` and generic `Page[A]`
  without issue; `GitHubGatewayConfig` is publicly constructible (no hand-written generator needed).
- All 3 generator modules + affected adapter/domain tests pass; `./mill __.compile` clean (no
  warnings); `make verify-deploy-deps` green and verified to fail on a deliberate violation.

## Open questions / risks

- `DeriveGen.gen` for Scala 3 `enum`s with parameterised cases (`GithubWebhookEvent`)
  and generic `Page[A]` — expected to work (Mirror-based); first compile confirms.
- `GitHubGatewayConfig` must be publicly constructible for `gen` to derive; if it has a
  private constructor, hand-write the generator instead.
- Scala.js link of `zio-test-magnolia_sjs1_3` in a cross module — artifact resolves;
  fastLinkJS of the generators js target will confirm it links.
```
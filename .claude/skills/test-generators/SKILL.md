---
name: test-generators
description: Use when adding or changing any domain model (case class, enum, or neotype newtype), writing a property-based test, or wiring a generators module in build.mill. Covers the MUST-have-a-generator rule, the DeriveGen patterns, the test-classpath-only module layout, and the deploy guard.
---

# Test-Data Generators

Every domain model — public **and** private — has a ZIO Test generator. Generators live in
dedicated `generators` modules that exist **only on the test classpath**, so writing a
property-based test for any model is trivial: a generator already exists.

## The rule (non-negotiable)

**Every type in a domain module MUST have a corresponding generator in that module's
`generators` companion module.** This includes case classes, enums, and neotype newtypes.
Adding a domain type without adding its generator is an incomplete change.

A generator is "present" when the module's `*Generators` object exposes a named
`Gen[Any, T]` `val` (or `def` for generics) for the type. Leaf newtypes additionally export
a `given DeriveGen[T]` so generators for containing types derive automatically.

## Where generators live

Each domain module gets a sibling `generators` sub-module, named `generators`, mirroring the
platform of the module it covers:

```
common/pagination/generators/                    # cross (jvm + js)
  src/ubc/common/pagination/PaginationGenerators.scala
github-gateway/domain/domainPublic/generators/   # cross (jvm + js)
  src/ubc/githubgateway/domain/DomainGenerators.scala
github-gateway/domain/domainPrivate/generators/  # jvm only (private domain is jvm only)
  src/ubc/githubgateway/domain/internal/InternalDomainGenerators.scala
```

The generator object lives in the **same package** as the types it covers, and is named
`<Module>Generators`.

### Why a dedicated `generators` module (not a `test/` source dir)?

A fair question: generators could live in a module's existing `test/src` and be shared by
adding that `test` submodule to another module's `test.moduleDeps` — Mill *does* let you depend
on a test module. We deliberately use a separate `generators` module anyway:

- **It avoids dragging specs along.** Depending on `domainPrivate.test` to borrow its generators
  also pulls that module's actual spec classes (`TokensSpec`, …) and all its test-only deps onto
  the consumer's classpath — coupling every consumer to the producer's whole test suite just to
  get a `Gen`. A dedicated module exposes *only* generators. (This is classpath coupling, not
  double-execution — Mill discovers suites from each module's own compiled output, not from
  transitive deps.)
- **JS cross-compilation requires it for the cross-compiled domains.** `domainPublic` and
  `pagination` generators must be usable from the Scala.js frontend tests. A
  `domainPublic.jvm.test` module is JVM-only and a Scala.js test cannot depend on it; only a
  cross-compiled (`jvm` + `js`) `generators` module can serve both. This reason does **not**
  apply to JVM-only domains like `domainPrivate`, but we keep those as `generators` modules too
  for uniformity.
- **Uniformity + the guard.** "Generators always live in a `generators` module" is one rule, and
  `verify-no-test-deps-in-deploy` keys off the `/generators/` path segment. A scattered
  test-dir approach is harder to state and to enforce.

There is no precedent in this repo for test→test `moduleDeps`; existing `test.moduleDeps` only
point at *production* in-memory adapters.

## How to write generators — `DeriveGen`

Generators use `zio-test-magnolia`'s `DeriveGen` (`import zio.test.magnolia.DeriveGen`).

### Leaf newtypes — explicit `instance` + a `given` + a named `val`

neotype `opaque type`s have no `Mirror`, so they cannot derive. Provide the `Gen` by hand,
expose it as a `given` (so containing types resolve it during derivation) and as a named val:

```scala
given DeriveGen[InstallationId] = DeriveGen.instance(Gen.long.map(InstallationId(_)))
val installationId: Gen[Any, InstallationId] = DeriveGen[InstallationId]
```

`DeriveGen.instance(gen)` wraps a plain `Gen`; `DeriveGen[T]` (apply) summons it back as a `Gen`.

### Case classes and enums — derive with `DeriveGen.gen`

Once the leaf `given`s are in scope, case classes and enums (including parameterised enum
cases, generic case classes, `List`/`Option`/tuple fields) derive automatically:

```scala
val installation: Gen[Any, Installation] = DeriveGen.gen[Installation].derive
val accountType: Gen[Any, AccountType]   = DeriveGen.gen[AccountType].derive
```

Built-in `DeriveGen` instances exist for `Int, Long, String, Boolean, Instant, UUID,
LocalDate(Time), Option, List, Seq, Set, Map, Either, tuples`. **Not** included: `zio.Duration`
— provide `given DeriveGen[Duration] = DeriveGen.instance(Gen.finiteDuration)` where needed.

### Generic types — take the element generator as a `using`

```scala
def page[A](using DeriveGen[A]): Gen[Any, Page[A]] = DeriveGen.gen[Page[A]].derive
```

### Cross-module derivation

When a private type references public newtypes, import the public leaf `given`s so derivation
resolves them:

```scala
import ubc.githubgateway.domain.DomainGenerators.given
```

The private `generators` module must list the public `generators` module in `moduleDeps`.

## Sensitive types

A `Sensitive` newtype (intersection alias + `.sensitive` smart constructor — see the
`sensitive` skill) must be generated **through its smart constructor** so the produced value
carries the marker:

```scala
// type AppToken = AppToken.Type & Sensitive ; AppToken.sensitive(s) tags it
given DeriveGen[AppToken] = DeriveGen.instance(Gen.string.map(AppToken.sensitive))
val appToken: Gen[Any, AppToken] = DeriveGen[AppToken]
```

Never generate a `Sensitive` value via the plain `apply` — it would drop the marker and the
value could leak through `ActivityEncoder`.

## Using a generator in a test

Add the relevant `generators` module to your **`test.moduleDeps`** (never production
`moduleDeps`), then `check`:

```scala
import ubc.githubgateway.domain.DomainGenerators
import zio.test.*

test("every installation round-trips through the repository") {
  check(DomainGenerators.installation) { installation =>
    for
      _     <- repo.upsert(installation)
      found <- repo.find(installation.id)
    yield assertTrue(found.contains(installation))
  }
}
```

`check` accepts multiple generators: `check(gen1, gen2, gen3) { (a, b, c) => ... }`.

## build.mill wiring

Generators modules depend on `genDeps` (jvm) / `genDepsJs` (js) — `zio-test` + the Magnolia
derivation. That puts `zio-test` on the module's **main** classpath, which is exactly what
makes the module test-only: it must never appear in a production `moduleDeps` chain.

```scala
// Cross-compiled (public domain / pagination)
object generators extends Module {
  trait Shared extends UbcScalaModule {
    def scalaVersion = scalaVer
    override def sources = Task.Sources(moduleDir / os.up / "src")
  }
  object jvm extends Shared {
    override def moduleDeps = Seq(domainPublic.jvm)
    override def mvnDeps = genDeps ++ neotypeDeps
    object test extends ScalaTests {
      def testFramework = "zio.test.sbt.ZTestFramework"
      override def mvnDeps = super.mvnDeps() ++ zioTestDeps
    }
  }
  object js extends Shared with UbcScalaJSModule {
    def scalaJSVersion = scalaJSVer
    override def moduleDeps = Seq(domainPublic.js)
    override def mvnDeps = genDepsJs ++ Seq(mvn"io.github.kitlangton:neotype_sjs1_3:0.4.10")
  }
}

// JVM-only (private domain). Depends on the public generators for shared leaf givens.
object generators extends UbcScalaModule {
  def scalaVersion = scalaVer
  override def moduleDeps = Seq(domainPrivate, domainPublic.generators.jvm)
  override def mvnDeps = genDeps ++ neotypeDeps ++ zioDeps
  object test extends ScalaTests {
    def testFramework = "zio.test.sbt.ZTestFramework"
    override def mvnDeps = super.mvnDeps() ++ zioTestDeps
  }
}
```

## The deploy guard

`bin/verify-no-test-deps-in-deploy.sh` (run via `make verify-deploy-deps`) inspects every
`*.server` module's runtime classpath and **fails** if it contains a `generators` module or
any `zio-test` artifact. This turns "only reference generators from `test.moduleDeps`" from a
convention into an enforced rule. Run it in CI; run it locally after touching `moduleDeps`.

## Checklist when adding a domain type

- [ ] Type defined in `domainPublic` / `domainPrivate`
- [ ] Generator added to the module's `generators` companion (leaf → `instance` + `given` + `val`; product/sum → `DeriveGen.gen[T].derive` val)
- [ ] If the type is `Sensitive`, the generator builds via the smart constructor
- [ ] `./mill <module>.generators.<platform>.test` passes
- [ ] `make verify-deploy-deps` still green
```
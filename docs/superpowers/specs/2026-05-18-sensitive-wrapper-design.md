# Sensitive-value redaction in ActivityLog JSON — wrapper design

**Date:** 2026-05-18
**Status:** Implementation complete, opened as an alternative to PR #19
**Companion:** `docs/superpowers/specs/2026-05-17-sensitive-redaction-design.md` (marker-trait design, PR #19)

## Problem

`ActivityLog` events are case classes serialised to JSON and written to durable
log storage. Today, "secrets MUST NOT appear in any field" is a comment in
`Activity.scala` — convention enforced by humans. The goal is a runtime safety
net: if a developer accidentally puts a secret-bearing value in an activity
event, the codec replaces it with `"[**Redacted**]"` rather than writing the
secret to logs.

## Goal

A field whose **declared type is `Sensitive[A]`** is redacted in the JSON. The
opt-in happens at the field-declaration site (`token: Sensitive[AppJwt]`) and
at the construction site (`Sensitive(token)`). The inner type `A` can be
anything — primitive, case class, opaque newtype, neotype — with no casts,
intersection types, or marker-trait conformance.

Out of scope: `toString`, OTel span attributes (forward-looking only), HTTP
response bodies, and any other zio-json encode path outside `toActivityJson`.
(The wrapper *can* be reused there later — its `JsonEncoder` is global and
unconditional — but no call sites exist today.)

## Design

### The wrapper

`common/sensitive/` is a cross-compiled (jvm + js) module containing:

```scala
final case class Sensitive[A](inner: A)

object Sensitive:
  val RedactedLiteral: String = "[**Redacted**]"

  given [A]: JsonEncoder[Sensitive[A]] =
    JsonEncoder.string.contramap(_ => RedactedLiteral)

  given [A]: JsonDecoder[Sensitive[A]] =
    JsonDecoder.string.mapOrFail(_ => Left("...write-only..."))
```

Three properties drive the design:

1. **The encoder wins by lookup, not priority.** zio-json's derivation resolves
   `JsonEncoder[T]` by the field's *declared* type. A field declared
   `token: Sensitive[AppJwt]` looks up `JsonEncoder[Sensitive[AppJwt]]` and
   finds the global given. The inner `JsonEncoder[AppJwt]` is never consulted
   — no `summonFrom` priority list, no Mirror, no inline machinery.
2. **`A` is unconstrained.** Any type can be wrapped: `Sensitive[String]`,
   `Sensitive[AppJwt]` (neotype), `Sensitive[OAuthGrant]` (case class). No
   marker trait to opt into, no smart-constructor cast needed for opaque
   types — the wrapper *is* the opt-in.
3. **Encode-only by design.** `Sensitive[A]` is a write-once type; the encoded
   form (`"[**Redacted**]"`) carries no recoverable information. An
   intentionally-failing `JsonDecoder` is provided solely so event case classes
   that use `derives JsonCodec` continue to compile — the decoder is never
   meant to fire in practice. The docstring directs callers who need real
   round-tripping to define a call-site-local decoder.

### Activity-log integration

`ActivityLog.scala` is unchanged except for a documentation update — events
continue to use `derives JsonCodec`. The wrapper plugs into the standard
zio-json derivation pipeline with no custom typeclass.

```scala
// Before
case class TokenMinted(id: GhInstallationId, token: AppJwt)
    extends InfoLog derives JsonCodec

// After — opt the field in:
case class TokenMinted(id: GhInstallationId, token: Sensitive[AppJwt])
    extends InfoLog derives JsonCodec

// Call site
ZIO.logActivity(TokenMinted(id, Sensitive(token)))

// Unwrapping (e.g. for an outbound HTTP call)
val raw: AppJwt = event.token.inner
```

`.inner` is the only unwrap path. Search for `.inner` to audit every place a
sensitive value is unwrapped; search for `Sensitive(` to audit every place one
is constructed.

### `Option[Sensitive[A]]`

zio-json's derived `Option[X]` encoder omits `None` fields from the object and
emits `Some(x)` as the encoder of `X` would emit `x`. So `Option[Sensitive[A]]
= Some(_)` encodes as `"[**Redacted**]"`; `= None` is omitted from the JSON
object. No special-case code in our module — it falls out of standard
derivation. Other containers (`Seq`, `Map`, `Either`) work the same way: a
`Seq[Sensitive[A]]` would emit an array of redaction sentinels.

### Why `case class` and not `AnyVal`

`final case class Sensitive[A](inner: A) extends AnyVal` is *almost* possible
in Scala 3 — but the resulting type would be boxed in every position the
compiler cannot statically resolve as a direct method receiver, which includes
generic type arguments (so `Option[Sensitive[A]]`, the type-class lookup for
`JsonEncoder[Sensitive[A]]`, every `derives JsonCodec` mirror, …). In practice
the wrapper would be boxed at every site we care about. The marginal stack
savings aren't worth the AnyVal restrictions (no parameterized companion
methods that capture the wrapper, no traits/interfaces extending it cleanly).
A plain `final case class` is simpler, debuggable, and erases identically in
the boxed positions that dominate.

### Module layout

```
common/sensitive/
  src/ubc/common/sensitive/Sensitive.scala   # shared source
  (jvm)                                      # JVM module
  (js)                                       # Scala.js module (ScalablyTyped-compatible)
```

Pattern matches `common/pagination/` and `common/sensitive/` in PR #19 — the
standard cross-compile shape in this repo. Depends on zio-json (both
platforms) for the encoder/decoder givens; no other deps.

## Tests

`common.activity-logging.test` exercises nine cases that parallel PR #19's
test surface:

| # | Case                                                                         |
|---|------------------------------------------------------------------------------|
| 1 | `FlatEvent(name, Sensitive(secret))` redacts `token`                         |
| 2 | Same event preserves `name`                                                  |
| 3 | Same event includes `"_ActivityLog":"FlatEvent"`                             |
| 4 | `NoSecretEvent` encodes normally — no wrapper-derived behaviour              |
| 5 | `Option[String] = None` is omitted from the object (zio-json default)        |
| 6 | `Sensitive[neotype]` redacts; non-wrapped neotype on same event passes through |
| 7 | `Option[Sensitive[_]] = Some` redacts                                        |
| 8 | `Option[Sensitive[_]] = None` is omitted                                     |
| 9 | `ZIO.logActivity` routes `InfoLog` events to `LogLevel.Info`                 |

Case 6 is the key comparison-point with PR #19: the marker-trait design
required intersection typing (`type AppJwt = AppJwt.Type & Sensitive`) and a
smart-constructor cast (`asInstanceOf[AppJwt]`) for opaque-type opt-in. The
wrapper design opts in by wrapping at the field site instead, so the neotype
stays unchanged (`type FakeNeoSecret = FakeNeoSecret.Type`) — no cast, no
intersection.

Case 6 also exercises the secondary property: the underlying neotype's
`JsonEncoder` (from `neotype-zio-json`) is *not* consulted for the wrapped
field, because the field's declared type is `Sensitive[FakeNeoSecret]`. We
verify this by including a non-wrapped neotype on the same event class —
that field DOES use the neotype encoder and emits the underlying string.

## Comparison to PR #19 (marker-trait design)

|                                  | Marker trait (PR #19)                                                                                                  | Wrapper (this PR)                                                                                                |
|----------------------------------|------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------|
| **Opt-in mechanism**             | Type extends `Sensitive`                                                                                                | Field wrapped in `Sensitive[A]`                                                                                  |
| **Call-site burden after opt-in**| Zero — type carries the marker                                                                                          | Wrap at construction: `Sensitive(token)`; unwrap with `.inner`                                                  |
| **Opaque-type / neotype opt-in** | Needs `type T = T.Type & Sensitive` intersection alias *and* a smart-constructor `asInstanceOf[T]` cast                | Just `Sensitive[T]` — no cast, no intersection, no change to the neotype                                         |
| **Codec mechanics**              | Custom `ActivityEncoder` typeclass + inline Mirror walk + `summonFrom { case _: (T <:< Sensitive) => ... }`            | Stock `derives JsonCodec`; redaction is a stock global `given JsonEncoder[Sensitive[A]]`                         |
| **Bytecode footprint**           | One synthesized `ActivityEncoder` per event class via inline derivation                                                 | One stock zio-json `JsonCodec` per event class; one global Sensitive encoder shared across the JVM               |
| **Container support**            | `<: Sensitive` + special-cased `Option[Sensitive]`; other containers (`Seq`, `Map`) NOT redacted                       | Any container of `Sensitive[A]` redacts via stock derivation; `Option`, `Seq`, `Either`, `Map[K, Sensitive[V]]`, nested products all work |
| **Greppability of unwrap sites** | None — value is just the underlying type                                                                                | `.inner` is the unwrap call; `Sensitive(` is the wrap call. Both greppable.                                      |
| **Spread of mechanism**          | Only redacts inside `toActivityJson`; nested products with `<: Sensitive` fields do NOT redact (one level deep)        | Redacts anywhere `Sensitive[A]` appears in any zio-json encode path, including nested products                   |
| **Risk of accidental leak**      | If someone writes `field: AppJwt` (without intersection-typing AppJwt as `<: Sensitive`), the value leaks               | If someone writes `field: AppJwt` (forgetting `Sensitive[_]`), the value leaks — identical risk class            |
| **toString safety**              | Same — neither design alters `toString`                                                                                 | Same — neither design alters `toString`                                                                          |

### When to prefer which

- **Wrapper** scales better when sensitivity is a property of a *use* of a
  value, not the value's identity. `String` is the most obvious case: lots of
  strings, some of them are secrets, you don't want to define `SecretString`
  as a newtype just to redact it. Same for primitive byte arrays, JWT
  libraries' opaque token types, etc.
- **Marker trait** scales better when sensitivity is a property of the *type*
  — `AppJwt` is *always* sensitive everywhere it appears, never logged in any
  form. Forgetting the wrapper at one call site is a real failure mode for
  Sensitive-as-type-property, but not for Sensitive-as-field-property.
- **Both** require the developer to remember to do the opt-in at definition
  time. Neither is a substitute for code review on new ActivityLog fields.

### Recommendation: replace the marker trait

This PR removes the marker trait entirely rather than keeping both. Reasons:

1. **Two ways to redact is worse than one.** Either every secret type is a
   marker-trait subtype, OR every secret field is wrapped — mixing leaves
   reviewers asking "is this redacted?" on every new field.
2. **The wrapper's container story is strictly more permissive.** Anything
   the marker trait redacts in `toActivityJson`, the wrapper can also redact
   — just by wrapping the field. The wrapper additionally redacts in any
   other zio-json encode path (HTTP response bodies, span attribute JSON
   strings, error message embeddings, …) — a future-facing win.
3. **Stock `derives JsonCodec` is one less novel mechanism to maintain.** The
   Mirror-based `ActivityEncoder` typeclass in PR #19 is small but is its own
   thing reviewers must learn. Stock derivation isn't.
4. **Greppability of `.inner` is a meaningful audit aid.** When debugging a
   suspected leak, `grep -rn '\.inner' src/` immediately surfaces every
   sensitive-value unwrap. The marker-trait approach has no equivalent — the
   secret value is just the type, indistinguishable from any other access.

## Migration steps

Sequenced so the tree stays compiling at every step:

1. **Add `common/sensitive/` module** (jvm + js) with `Sensitive[A]` + the
   encoder/decoder givens. Wire into `build.mill` next to the existing
   `activity-logging` module.
2. **Add `common.activity-logging.test` module.** Mill 1.x conventions:
   `ScalaTests` (not `Tests`), `moduleDeps` without `()`, `mvnDeps` with `()`.
3. **Verify** `./mill common.__.compile && ./mill common.activity-logging.test`,
   then `./mill provider-gateways.__.test`.

No existing code changes. No `derives JsonCodec` lines move. No event class
gets a Sensitive field in this PR — per PR #19's analysis, no current event
holds a secret-bearing value. Future PRs that introduce secret-bearing fields
drive the wrap-at-the-field-site change.

## Forward-looking: OTel and other encode paths

When someone adds a `Telemetry.setSpanAttribute[A](key: String, value: A)`
helper, it can serialise `value` through the same `JsonEncoder[A]` lookup —
which means a `Sensitive[A]` attribute is redacted in span attributes too,
with zero additional code. The marker-trait design would require a parallel
`summonFrom { case _: (A <:< Sensitive) => ... }` check at every such helper.
This is the strongest argument for the wrapper: redaction becomes a property
of the type, threaded through any encode path zio-json reaches.

## Open questions

- **Should we ship a `JsonCodec[Sensitive[A]]` rather than separate encoder +
  decoder?** Mechanically equivalent. Separate givens (current state) keep
  the always-failing decoder visible as a distinct decision in the source —
  reviewers see "encoder + intentionally-failing decoder" instead of "an
  opaque JsonCodec." Lean toward keeping them separate.
- **Should the encoder emit a JSON object (`{"redacted": true}`) rather than
  a bare string?** A bare string matches PR #19 and is easier for log-search
  consumers to grep for. Stick with the string sentinel unless a downstream
  schema gives us reason to switch.

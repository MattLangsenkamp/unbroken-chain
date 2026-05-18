# Sensitive-value redaction in ActivityLog JSON

**Date:** 2026-05-17
**Status:** Approved, implementation in progress

## Problem

`ActivityLog` events are case classes serialised to JSON and written to durable
log storage. Today, "secrets MUST NOT appear in any field" is a comment in
`Activity.scala` — convention enforced by humans. The goal is a runtime safety
net: if a developer accidentally puts a secret-bearing value in an activity
event, the codec replaces it with `"[**Redacted**]"` rather than writing the
secret to logs.

A previous attempt added a `Sensitive` marker trait and a global
`given JsonEncoder[Sensitive]` that emits the redacted string. That approach
does not work: zio-json derivation looks up encoders by declared field type, so
`JsonEncoder[Sensitive]` only fires for fields declared as the literal
`Sensitive` type, never for `AppJwt extends ... with Sensitive` whose
neotype-derived `JsonEncoder[AppJwt]` wins implicit search.

## Goal

A field whose **declared type at the top-level activity-log class is a subtype
of `Sensitive`** is redacted in the JSON. No call-site burden beyond
`extends Sensitive` (or intersection-typing) on the sensitive type itself.

Out of scope: `toString`, OTel span attributes (forward-looking only), HTTP
response bodies, and any other zio-json encode path outside `toActivityJson`.

## Design

### Marker trait

`common/sensitive/` is a cross-compiled (jvm + js) module containing only:

```scala
package ubc.common.sensitive
trait Sensitive
```

No methods. Pure type-level signal. Cross-compiled so frontend code can use it
too if/when needed.

### Encoder mechanics

Replace `derives JsonCodec` on activity-log events with an inline,
Mirror-based encoder defined in `common.activity-logging`:

```scala
inline def toActivityJson[A <: ActivityLog](a: A)(using m: Mirror.ProductOf[A]): String

extension (zio: ZIO.type)
  inline def logActivity[A <: ActivityLog](a: A)(using m: Mirror.ProductOf[A]): UIO[Unit]
```

`toActivityJson` walks `m.MirroredElemLabels` and `m.MirroredElemTypes` at
compile time. For each field with declared type `T`:

```scala
inline def fieldJson[T](t: T): Json = summonFrom {
  case _: (T <:< Sensitive) => Json.Str("[**Redacted**]")
  case enc: JsonEncoder[T]  => enc.toJsonAST(t).getOrElse(Json.Null)
}
```

Then prepend `("_ActivityLog", Json.Str(a.productPrefix))` and emit
`.toJson` — same final shape as today.

Three properties make this defensible:

1. **`summonFrom` evaluates cases in order.** The `<:<` case is first, so it
   wins over an in-scope `JsonEncoder[T]` (including neotype-derived ones).
2. **Top-level only.** Redaction fires only for fields whose declared type at
   the *top-level* activity-log class is `<: Sensitive`. Nested case classes
   use standard zio-json derivation and will not redact their own sensitive
   fields. Documented limitation; activity events are flat by convention.
3. **No decode path.** `toActivityJson` is encode-only; log storage is the
   sink. Dropping `JsonCodec` derivation in favor of an encode-only function
   is correct, not a regression.

### Removed code

- `given JsonEncoder[Sensitive]` in `ActivityLog.scala` (unused under the new
  lookup strategy).
- `import zio.json.internal.Write` (only needed for the deleted given).
- `derives JsonCodec` on every `ActivityLog` subtype.
- The broken `JsonCodec[A].value` expression on line 33 disappears with the
  rewrite.

### Call-site changes

In `provider-gateways/github-gateway/core/core-impl/.../Activity.scala` and
any other file defining `ActivityLog` subtypes:

```diff
- final case class LinkInitiated(state: LinkState, expiresAt: Instant)
-     extends InfoLog derives JsonCodec
+ final case class LinkInitiated(state: LinkState, expiresAt: Instant)
+     extends InfoLog
```

`PublicJsonCodecs.given` imports stay — they provide the `JsonEncoder[…]`
instances the inline encoder summons for non-sensitive fields.

`ZIO.logActivity(LinkInitiated(...))` is unchanged at the call site; the
extension method now requires `Mirror.ProductOf[A]` instead of `JsonCodec[A]`,
and the compiler synthesises the Mirror automatically.

### Sensitive opt-in for newtypes

The first time someone adds a token-bearing field to an event, opt the
corresponding newtype in via intersection-typing **plus a smart constructor**:

```scala
object AppJwt extends Newtype[String]
type AppJwt = AppJwt.Type & Sensitive
def appJwt(s: String): AppJwt = AppJwt(s).asInstanceOf[AppJwt]
```

The smart constructor is required because neotype's `apply` returns the
opaque `Type` (aliased to `String`), which is **not** automatically
`<: Sensitive` — opaque types only conform to their declared bounds, and
their underlying type cannot be widened structurally. The cast is
type-system-only: the encoder's `<:< Sensitive` check is compile-time, so
no `Sensitive` interface is needed at runtime, and the bytes are unchanged.

The cast appears once, in the smart constructor next to the type definition.
Call sites use `appJwt("...")` and never touch `asInstanceOf`. Verified by
the spec's neotype test, which also confirms a non-Sensitive neotype on the
same event class encodes normally via neotype-zio-json's `JsonEncoder`.

**Not done in this PR** — no current event has a secret-bearing field, so
opting in would change no observable behaviour. Future PRs that add
secret-bearing fields will drive the opt-in.

## Tests

New test module `common.activity-logging.test`. ZIO test style, matching the
rest of the repo. Test fixtures live in the test file, not production code:

```scala
object FakeSecret extends Newtype[String]
type FakeSecret = FakeSecret.Type & Sensitive

final case class FlatEvent(name: String, token: FakeSecret) extends InfoLog
final case class NoSecretEvent(name: String, count: Int)    extends InfoLog
final case class OptSecretEvent(name: String, token: Option[FakeSecret])
    extends InfoLog
```

Cases:

1. **Sensitive field redacts.** `toActivityJson(FlatEvent("hi", FakeSecret("hunter2")))`
   → JSON contains `"token":"[**Redacted**]"` and does **not** contain
   `"hunter2"`.
2. **Non-sensitive fields untouched.** Same call → `"name":"hi"` appears
   as-is.
3. **`_ActivityLog` discriminator preserved.** Same call → contains
   `"_ActivityLog":"FlatEvent"`.
4. **Pure-non-sensitive event unchanged.** `toActivityJson(NoSecretEvent("hi", 3))`
   parses to the same JSON the old `derives JsonCodec` path would have
   produced — guards against accidental shape change during the rewrite.
5. **Documented limitation: `Option[Sensitive]` does NOT auto-redact.**
   `OptSecretEvent("hi", Some(FakeSecret("hunter2")))` → the inner secret
   appears unredacted. Test pins the limitation so future work that closes
   it has a failing test to flip.
6. **Log level routing.** `ZIO.logActivity(FlatEvent(...))` routes to
   `LogLevel.Info` (use a ZIO test logger sink). One representative test —
   the level `match` is trivial.
7. **Compile-time: `derives JsonCodec` is not required.** Implicit — the
   fixtures compile without it. No separate test needed.
8. **Neotype opt-in.** Define `object FakeNeoSecret extends Newtype[String]`,
   `type FakeNeoSecret = FakeNeoSecret.Type & Sensitive`, and a smart
   constructor that casts. An event class with both a Sensitive neotype
   field and a non-Sensitive neotype field — verify the Sensitive one is
   redacted while the non-Sensitive one passes through via the
   neotype-zio-json `JsonEncoder`. This is the exercise of both branches
   of `summonFrom` in the same product.

Non-goals for tests: every neotype variant, OTel, concurrent logging.

## Migration steps

Sequenced so the tree stays compiling at every step:

1. **Rewrite `ActivityLog.scala`** with the inline encoder. New imports:
   `scala.deriving.Mirror`, `scala.compiletime.summonFrom`, `zio.json.ast.Json`,
   `zio.json.JsonEncoder`. Drop `zio.json.internal.Write` import and the
   `JsonEncoder[Sensitive]` given. The broken `JsonCodec[A].value` line is
   replaced wholesale.
2. **Confirm `common.sensitive` cross-module builds.** Already scaffolded in
   `build.mill`; Mill 1.x cross-module pattern from
   `feedback_tyrian_scalajs_mill` memory matches.
3. **Add the test module.** `common.activity-logging.test` following
   `feedback_mill_test_module` (use `ScalaTests` not `Tests`; `moduleDeps`
   without `()`; `mvnDeps` with `()`). Implement the seven cases.
4. **Drop `derives JsonCodec`** from every `ActivityLog` subtype.
   Currently `Activity.scala` in github-gateway core-impl, plus any events
   in `common/server-utils/ServerLayers.scala`.
5. **Verify:** `./mill common.__.compile && ./mill common.activity-logging.test`,
   then `./mill provider-gateways.github-gateway.__.compile`.

## Forward-looking: OTel

When someone adds a `Telemetry.setSpanAttribute[A](key: String, value: A)`
helper, it performs the same `summonFrom { case _: (A <:< Sensitive) => "[**Redacted**]"; ... }`
check. Not built now — no call sites — but the marker trait and the redacted
string become shared.

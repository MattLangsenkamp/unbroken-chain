---
name: sensitive
description: Use when adding a new secret/token/key value type, adding a field to an ActivityLog event that could carry a secret, wiring an auth/OAuth/JWT flow, or reviewing whether a value might leak into logs. Covers how to mark types so the activity-logging encoder redacts them in JSON output.
---

# Sensitive Values

Marks types that hold secrets (API tokens, refresh tokens, encryption keys, JWTs, …) so the `ActivityEncoder` in `common/activity-logging` redacts them to `"[**Redacted**]"` when serialising activity-log events to JSON.

**Scope of protection:** ONLY `toActivityJson` / `ZIO.logActivity` paths. Stock zio-json encoding (HTTP request/response bodies, persisted JSON, Tapir flows) is **not** affected — outbound API calls that need to ship a secret in a JSON body still send the real value. `toString`/string interpolation is also unaffected. The marker is a logging safety net, not a universal "this value is secret".

## Where it lives

`common/sensitive` (cross-compiled jvm + js):

- `trait Sensitive` — nominal marker, no methods.
- `Sensitive.tag[A](a: A): A & Sensitive` — `inline def` that type-tags an opaque value. Only needed for the neotype opt-in pattern below.

## Opting a type into Sensitive

### Plain case classes — `extends Sensitive`

```scala
final case class RefreshToken(value: String) extends Sensitive
```

The marker is nominal and visible at the type definition. No helpers needed.

### Neotype-backed newtypes — intersection alias + companion smart constructor

`Newtype[A]` defines `opaque type Type = A`. The opaque `Type`'s external subtype bound is fixed at `Any`. `extends Sensitive` on the **companion** refines the companion's type, NOT `Type`'s. So this **does not work**:

```scala
// BROKEN — `Type` is not Sensitive; only the companion object is.
object AppJwt extends Newtype[String] with Sensitive
type AppJwt = AppJwt.Type
```

The correct pattern: widen the **type alias** with an intersection, and provide a smart constructor inside the companion that uses `Sensitive.tag`:

```scala
import neotype.*
import ubc.common.sensitive.Sensitive

object AppJwt extends Newtype[String]:
  def sensitive(s: String): AppJwt = Sensitive.tag(AppJwt(s))
type AppJwt = AppJwt.Type & Sensitive
```

Call sites construct via `AppJwt.sensitive("…")`. The `asInstanceOf` lives exactly once, inside `Sensitive.tag`'s inline body — never at call sites.

**Unwrapping a Sensitive newtype:** the `& Sensitive` intersection breaks neotype's `value.unwrap`
extension (it resolves a `Newtype.WithType[A, B]` keyed on the exact receiver type, which the
intersection no longer matches). Use the companion method instead — `AppJwt.unwrap(jwt)` — which
accepts any subtype of `Type`. Do this only at real boundaries that must ship the secret
(e.g. building an `Authorization` header).

**Don't name the smart constructor `make`** — `Newtype` already has `final def make`. `sensitive`, `secret`, or `from` all work.

### Generator for a Sensitive newtype

Every domain type needs a test generator (`test-generators` skill). A `Sensitive` newtype
must be generated **through its smart constructor**, so the produced value keeps the marker —
generating via plain `apply` would drop `& Sensitive` and the value could leak through
`ActivityEncoder`:

```scala
given DeriveGen[AppJwt] = DeriveGen.instance(Gen.string.map(AppJwt.sensitive))
val appJwt: Gen[Any, AppJwt] = DeriveGen[AppJwt]
```

## Using a Sensitive value in an event

Drop the type into any field of an `ActivityLog` subtype derived with `ActivityEncoder`. No wrapping at the field site:

```scala
final case class TokenMinted(installationId: GhInstallationId, jwt: AppJwt)
    extends InfoLog derives ActivityEncoder
```

Logging it: `ZIO.logActivity(TokenMinted(id, jwt))` → `{"installationId":"…","jwt":"[**Redacted**]","_ActivityLog":"TokenMinted"}`.

Common containers are also covered:
- `Option[Sensitive]` — `Some(secret)` redacts; `None` is omitted.
- `Seq[Sensitive]` (and subtypes: `List`, `Vector`, `Chunk`, …) — each element redacts, array length preserved.
- `Either[_, Sensitive]` — the `Right` value redacts; the `Left` encodes normally. The `{"Left":..}`/`{"Right":..}` shape is preserved.

## What is NOT covered

- **Map values and nested products.** `Map[K, Sensitive]` and a non-Sensitive case class with a `Sensitive` field inside go through stock zio-json derivation and will **leak**. Make the whole value `<: Sensitive` if it needs redaction.
- **`Either` with a Sensitive `Left`.** Only the `Right` channel is checked; `Either[Sensitive, _]` leaks.
- **Non-logging encode paths.** HTTP bodies, persisted JSON, OTel attributes (no helper exists yet). For those, unwrap with `.value` / `.inner` at the boundary.
- **`toString`.** Not overridden. Don't interpolate sensitive values into strings.

## Quick reference

| Situation | What to write |
|---|---|
| New plain case-class secret | `final case class Foo(value: String) extends Sensitive` |
| New neotype secret | `object Foo extends Newtype[String]: def sensitive(s: String): Foo = Sensitive.tag(Foo(s))` + `type Foo = Foo.Type & Sensitive` |
| Use in event | Plain field declaration; `derives ActivityEncoder` on the event |
| Use in HTTP body | Unwrap with `.value` / `.inner` — wrapper does not auto-redact here, but this is intentional (outbound calls need the real value) |

## Design notes

The marker-trait + `summonFrom` approach was chosen specifically so redaction fires **only** inside `ActivityEncoder`. A wrapper type with a global `JsonEncoder` was rejected because it would also redact outbound HTTP JSON bodies, breaking legitimate API calls that need to ship a token.

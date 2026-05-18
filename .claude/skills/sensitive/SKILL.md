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

**Don't name the smart constructor `make`** — `Newtype` already has `final def make`. `sensitive`, `secret`, or `from` all work.

## Using a Sensitive value in an event

Drop the type into any field of an `ActivityLog` subtype derived with `ActivityEncoder`. No wrapping at the field site:

```scala
final case class TokenMinted(installationId: GhInstallationId, jwt: AppJwt)
    extends InfoLog derives ActivityEncoder
```

Logging it: `ZIO.logActivity(TokenMinted(id, jwt))` → `{"installationId":"…","jwt":"[**Redacted**]","_ActivityLog":"TokenMinted"}`.

`Option[Sensitive]` is also covered — `Some(secret)` redacts; `None` is omitted.

## What is NOT covered

- **Other containers.** `Seq[Sensitive]`, `Map[K, Sensitive]`, `Either[E, Sensitive]`, nested case classes with a `Sensitive` field inside — these go through stock zio-json derivation and will **leak**. Activity events are flat by convention; if you need to put a secret inside a container, make the whole container type `<: Sensitive`.
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

The marker-trait + `summonFrom` approach was chosen specifically so redaction fires **only** inside `ActivityEncoder`. A wrapper type with a global `JsonEncoder` was rejected because it would also redact outbound HTTP JSON bodies, breaking legitimate API calls that need to ship a token. See `docs/superpowers/specs/2026-05-17-sensitive-redaction-design.md` for the full rationale.

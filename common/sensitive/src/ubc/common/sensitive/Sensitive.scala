package ubc.common.sensitive

import zio.json.{JsonDecoder, JsonEncoder}

/** Opt-in wrapper for values whose contents must never appear in logs, traces, or other
  * observability output (e.g. API tokens, refresh tokens, encryption keys).
  *
  * Wrap a value at the construction site:
  * {{{
  *   final case class TokenMinted(installationId: GhInstallationId, token: Sensitive[AppJwt])
  *       extends InfoLog derives JsonCodec
  *
  *   ZIO.logActivity(TokenMinted(id, Sensitive(token)))
  * }}}
  *
  * The wrapper is the carrier of redaction semantics: anywhere a `Sensitive[A]` is encoded
  * to JSON, the underlying `JsonEncoder[A]` does **not** fire — the global given below
  * always emits `"[**Redacted**]"`. This works for any inner type `A`, including neotype-
  * style opaque newtypes, with no casts and no marker-trait opt-in.
  *
  * Unwrapping is explicit and greppable via `.inner`. Search the codebase for
  * `Sensitive(` (wrap sites) and `.inner` (unwrap sites) to audit the secret-handling
  * boundary.
  *
  * A `JsonDecoder[Sensitive[A]]` is provided but always fails: this type is intended
  * for write-only, encode-once-and-discard paths (activity logs, span attributes,
  * error context strings). The decoder exists only so event case classes can keep
  * `derives JsonCodec`; if round-trip support is ever genuinely needed, define a
  * call-site-local decoder that wraps a real `JsonDecoder[A]`.
  */
final case class Sensitive[A](inner: A)

object Sensitive:
  /** The literal string emitted in place of any `Sensitive[A]` value. Exposed so
    * callers that handle redaction in a non-JSON context (toString, span attributes)
    * can use the same sentinel and stay consistent with log output.
    */
  val RedactedLiteral: String = "[**Redacted**]"

  /** Always emits the redacted sentinel, regardless of `A`. Wins over any inner-type
    * encoder because zio-json looks up the encoder by the field's declared type — and
    * the declared type at the field site is `Sensitive[A]`, not `A`.
    */
  given [A]: JsonEncoder[Sensitive[A]] =
    JsonEncoder.string.contramap(_ => RedactedLiteral)

  /** Intentionally always-failing decoder. `Sensitive[A]` is write-only by design —
    * the encoded form (`"[**Redacted**]"`) carries no recoverable information about
    * the inner value, so any decode attempt is a programmer error. Exists solely so
    * `derives JsonCodec` on event classes still compiles.
    */
  given [A]: JsonDecoder[Sensitive[A]] =
    JsonDecoder.string.mapOrFail(_ =>
      Left("Sensitive[A] is write-only: define a call-site-local JsonDecoder if you really need to decode it.")
    )

package ubc.common.securerandom

import zio.UIO

/** Outbound port for cryptographically secure randomness, generic across services.
  *
  * Returns base64url-encoded (no padding) random strings of an arbitrary byte length.
  * Service-specific newtype wrappers (e.g. a `Nonce` or `CodeVerifier`) belong at the call
  * site — the port itself stays opaque.
  *
  * Returns [[UIO]]: generation is conceptually fallible (entropy exhaustion) but in practice
  * the JDK's `SecureRandom` does not surface that, and we don't model it.
  */
trait SecureRandom:

  /** Generate `byteCount` random bytes and return them base64url-encoded without padding.
    *
    * Length of the resulting string is `ceil(byteCount * 4 / 3)` characters drawn from the
    * URL-safe alphabet `[A-Za-z0-9_-]`. Callers pick `byteCount` based on the entropy they
    * need and any external length constraints (e.g. PKCE's RFC 7636 §4.1 requires a verifier
    * of 43-128 chars, satisfied by `byteCount = 32..96`).
    */
  def urlSafeRandomString(byteCount: Int): UIO[String]

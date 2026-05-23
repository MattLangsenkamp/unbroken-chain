package ubc.common.crypto

import zio.Task

/** Outbound port for symmetric encryption of values stored at rest.
  *
  * The port is intentionally generic: it operates on `String` (UTF-8 plaintext) and `String` (base64-encoded
  * ciphertext) so the trait stays reusable across services. Service-specific newtype wrappers (e.g. a `Ciphertext`
  * opaque type) belong at the call site, not here.
  *
  * Adapters are responsible for choosing the cipher + IV scheme. The encryption key is a constructor argument on each
  * adapter — keys never flow through port signatures.
  */
trait Crypto:

  /** Encrypt UTF-8 plaintext, returning a base64-encoded ciphertext. */
  def encrypt(plaintext: String): Task[String]

  /** Decrypt a base64-encoded ciphertext back to UTF-8 plaintext.
    *
    * Failure modes (key mismatch, malformed payload) are unmodelled at this layer; adapters raise the underlying
    * exception via [[Task]]. Callers convert at the service boundary.
    */
  def decrypt(ciphertext: String): Task[String]

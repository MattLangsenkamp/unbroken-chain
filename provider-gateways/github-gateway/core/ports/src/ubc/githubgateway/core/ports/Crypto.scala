package ubc.githubgateway.core.ports

import ubc.githubgateway.domain.internal.EncryptedBytes
import zio.Task

/** Outbound port for symmetric encryption of values stored at rest.
  *
  * Currently used only for the PKCE `code_verifier` on `pending_link_flow` rows. The
  * encryption key is a constructor argument on the adapter — keys never flow through port
  * signatures.
  */
trait Crypto:

  /** Encrypt UTF-8 plaintext, returning a base64-encoded ciphertext. */
  def encrypt(plaintext: String): Task[EncryptedBytes]

  /** Decrypt a base64-encoded ciphertext back to UTF-8 plaintext.
    * Failure modes (key mismatch, malformed payload) are unmodelled at this layer; adapters
    * raise the underlying exception via [[Task]]. Callers convert at the service boundary.
    */
  def decrypt(ciphertext: EncryptedBytes): Task[String]

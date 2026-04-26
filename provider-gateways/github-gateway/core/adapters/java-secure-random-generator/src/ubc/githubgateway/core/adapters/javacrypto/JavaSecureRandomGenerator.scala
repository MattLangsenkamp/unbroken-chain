package ubc.githubgateway.core.adapters.javacrypto

import ubc.githubgateway.core.ports.SecureRandom
import ubc.githubgateway.domain.LinkState
import ubc.githubgateway.domain.internal.CodeVerifier
import zio.*

/** Real [[SecureRandom]] adapter backed by `java.security.SecureRandom`.
  *
  * Both generators encode random bytes with base64url (without padding):
  *   - `newCodeVerifier`: 64 random bytes → 86 base64url chars. Within RFC 7636's 43-128
  *     range; comfortably above the 43-char minimum and well under the 128-char ceiling.
  *   - `newLinkState`: 32 random bytes → 43 base64url chars. Format-free, just needs to be
  *     unguessable and unique.
  */
final class JavaSecureRandomGenerator(rng: java.security.SecureRandom) extends SecureRandom:

  // RFC 7636 §4.1: code_verifier is 43-128 URL-safe characters drawn from [A-Z][a-z][0-9]-._~.
  // base64url uses [A-Za-z0-9_-] which is a strict subset of that allowed set.
  private val verifierBytes = 64

  // State nonce: 32 bytes => 43 chars base64url unpadded.
  private val stateBytes = 32

  def newCodeVerifier(): UIO[CodeVerifier] =
    ZIO.succeed {
      val bytes = new Array[Byte](verifierBytes)
      rng.nextBytes(bytes)
      CodeVerifier(java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(bytes))
    }

  def newLinkState(): UIO[LinkState] =
    ZIO.succeed {
      val bytes = new Array[Byte](stateBytes)
      rng.nextBytes(bytes)
      LinkState(java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(bytes))
    }

object JavaSecureRandomGenerator:

  /** Build a generator from the system's default `java.security.SecureRandom`. */
  val live: ULayer[SecureRandom] =
    ZLayer.succeed(new JavaSecureRandomGenerator(new java.security.SecureRandom()))

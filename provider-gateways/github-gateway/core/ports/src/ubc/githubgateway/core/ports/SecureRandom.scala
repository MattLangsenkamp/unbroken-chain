package ubc.githubgateway.core.ports

import ubc.githubgateway.domain.LinkState
import ubc.githubgateway.domain.internal.CodeVerifier
import zio.UIO

/** Outbound port for cryptographically secure randomness.
  *
  * Two distinct generators are exposed so adapters can use length / charset rules appropriate
  * to each (PKCE verifiers are URL-safe 43-128 chars per RFC 7636; state nonces have no
  * required format beyond being unguessable and unique).
  *
  * Returns [[UIO]] — generation is conceptually fallible (entropy exhaustion) but in practice
  * the JDK's `SecureRandom` does not surface that, and we don't model it.
  */
trait SecureRandom:

  /** Generate a fresh PKCE verifier.
    *
    * RFC 7636 §4.1: 43-128 URL-safe characters drawn from `[A-Z][a-z][0-9]-._~`.
    * Adapters MUST satisfy that range.
    */
  def newCodeVerifier(): UIO[CodeVerifier]

  /** Generate a fresh state nonce. Should be unguessable and unique per call. */
  def newLinkState(): UIO[LinkState]

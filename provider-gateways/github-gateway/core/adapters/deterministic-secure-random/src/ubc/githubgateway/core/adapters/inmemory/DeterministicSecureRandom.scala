package ubc.githubgateway.core.adapters.inmemory

import ubc.githubgateway.core.ports.SecureRandom
import ubc.githubgateway.domain.LinkState
import ubc.githubgateway.domain.internal.CodeVerifier
import zio.*

/** Counter-backed deterministic [[SecureRandom]] for tests.
  *
  * Verifiers and link states are derived from a monotonically increasing counter, so tests
  * that exercise the link flow can assert on exact values. Verifier strings are padded to 43
  * characters to satisfy the RFC 7636 minimum length contract that real adapters obey.
  *
  * NOT cryptographically secure — never wire this in production.
  */
final class DeterministicSecureRandom(counter: Ref[Long]) extends SecureRandom:

  def newCodeVerifier(): UIO[CodeVerifier] =
    counter.updateAndGet(_ + 1L).map { n =>
      val prefix = s"verifier-$n-"
      val padded = prefix + "A" * math.max(0, 43 - prefix.length)
      CodeVerifier(padded)
    }

  def newLinkState(): UIO[LinkState] =
    counter.updateAndGet(_ + 1L).map(n => LinkState(s"state-$n"))

object DeterministicSecureRandom:
  val layer: ULayer[SecureRandom] =
    ZLayer.fromZIO(Ref.make(0L).map(new DeterministicSecureRandom(_)))

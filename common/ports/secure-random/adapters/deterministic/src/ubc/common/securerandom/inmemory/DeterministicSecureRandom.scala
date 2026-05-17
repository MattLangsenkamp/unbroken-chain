package ubc.common.securerandom.inmemory

import ubc.common.securerandom.SecureRandom
import zio.*

/** Counter-backed deterministic [[SecureRandom]] for tests.
  *
  * Each call returns a string derived from a monotonically increasing counter, padded with
  * `'A'` (URL-safe per RFC 3986) to satisfy the requested length. Output is the exact string
  * length of `urlSafeRandomString(byteCount)` from a real adapter (`ceil(byteCount * 4 / 3)`)
  * so length-sensitive code paths see the same shape.
  *
  * NOT cryptographically secure — never wire this in production.
  */
final class DeterministicSecureRandom(counter: Ref[Long]) extends SecureRandom:

  def urlSafeRandomString(byteCount: Int): UIO[String] =
    counter.updateAndGet(_ + 1L).map { n =>
      val expectedLen = math.ceil(byteCount * 4.0 / 3.0).toInt
      val prefix      = s"rnd-$n-"
      prefix + "A" * math.max(0, expectedLen - prefix.length)
    }

object DeterministicSecureRandom:
  val layer: ULayer[SecureRandom] =
    ZLayer.fromZIO(Ref.make(0L).map(new DeterministicSecureRandom(_)))

package ubc.githubgateway.core.adapters.javacrypto

import neotype.*
import ubc.githubgateway.domain.internal.{EncryptedBytes, EncryptionKey}
import zio.*
import zio.test.*
import zio.test.Assertion.*

import javax.crypto.spec.SecretKeySpec

object AesGcmCryptoSpec extends ZIOSpecDefault:

  /** Deterministic 32-byte AES-256 test key. */
  private val testKeyBytes: Array[Byte] =
    (0 until 32).map(_.toByte).toArray
  private val testKey: SecretKeySpec = new SecretKeySpec(testKeyBytes, "AES")

  private def freshCrypto: AesGcmCrypto =
    new AesGcmCrypto(testKey, new java.security.SecureRandom())

  override def spec =
    suite("AesGcmCrypto")(
      test("encrypt then decrypt round-trips arbitrary plaintext") {
        val crypto = freshCrypto
        val input  = "the quick brown fox - üñîçödé and bytes  "
        for
          ct   <- crypto.encrypt(input)
          back <- crypto.decrypt(ct)
        yield assertTrue(back == input)
      },
      test("encrypting the same plaintext twice produces different ciphertext (random IV)") {
        val crypto = freshCrypto
        for
          a <- crypto.encrypt("hello")
          b <- crypto.encrypt("hello")
        yield assertTrue(a.unwrap != b.unwrap)
      },
      test("decryption fails on tampered ciphertext (AEAD tag mismatch)") {
        val crypto = freshCrypto
        for
          ct        <- crypto.encrypt("the quick brown fox")
          combined  = java.util.Base64.getDecoder.decode(ct.unwrap)
          // Flip a bit in the last byte (inside the GCM auth tag)
          _         = combined.update(combined.length - 1, (combined(combined.length - 1) ^ 0x01).toByte)
          tampered  = EncryptedBytes(java.util.Base64.getEncoder.encodeToString(combined))
          result    <- crypto.decrypt(tampered).exit
        yield assert(result)(fails(anything))
      },
      test("fromKey rejects a key that is not 32 bytes") {
        val sixteenBytes = new Array[Byte](16)
        val badKey       = EncryptionKey(java.util.Base64.getEncoder.encodeToString(sixteenBytes))
        for
          result <- AesGcmCrypto.fromKey(badKey).exit
        yield assert(result)(
          fails(hasMessage(containsString("32")))
        )
      },
      test("fromKey accepts a 32-byte base64 key and round-trips a string") {
        val rawKey = new Array[Byte](32)
        new java.security.SecureRandom().nextBytes(rawKey)
        val key    = EncryptionKey(java.util.Base64.getEncoder.encodeToString(rawKey))
        val input  = "round-trip via fromKey"
        for
          crypto <- AesGcmCrypto.fromKey(key)
          ct     <- crypto.encrypt(input)
          back   <- crypto.decrypt(ct)
        yield assertTrue(back == input)
      },
      test("fromKey-built crypto's ciphertext is base64 (decodable, non-empty)") {
        val rawKey = new Array[Byte](32)
        val key    = EncryptionKey(java.util.Base64.getEncoder.encodeToString(rawKey))
        for
          crypto <- AesGcmCrypto.fromKey(key)
          ct     <- crypto.encrypt("x")
          decoded = java.util.Base64.getDecoder.decode(ct.unwrap)
        yield assertTrue(decoded.length > 0)
      }
    )

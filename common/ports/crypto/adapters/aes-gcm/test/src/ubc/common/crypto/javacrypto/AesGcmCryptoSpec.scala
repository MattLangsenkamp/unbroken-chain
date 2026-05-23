package ubc.common.crypto.javacrypto

import zio.*
import zio.test.*
import zio.test.Assertion.*

import java.util.Base64
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
        yield assertTrue(a != b)
      },
      test("decryption fails on tampered ciphertext (AEAD tag mismatch)") {
        val crypto = freshCrypto
        for
          ct <- crypto.encrypt("the quick brown fox")
          combined = Base64.getDecoder.decode(ct)
          // Flip a bit in the last byte (inside the GCM auth tag)
          _        = combined.update(combined.length - 1, (combined(combined.length - 1) ^ 0x01).toByte)
          tampered = Base64.getEncoder.encodeToString(combined)
          result <- crypto.decrypt(tampered).exit
        yield assert(result)(fails(anything))
      },
      test("fromKey rejects a key that is not 32 bytes") {
        val sixteenBytes = new Array[Byte](16)
        val badKey       = Base64.getEncoder.encodeToString(sixteenBytes)
        for result <- AesGcmCrypto.fromKey(badKey).exit
        yield assert(result)(
          fails(hasMessage(containsString("32")))
        )
      },
      test("fromKey accepts a 32-byte base64 key and round-trips a string") {
        val rawKey = (0 until 32).map(_.toByte).toArray
        val key    = Base64.getEncoder.encodeToString(rawKey)
        val input  = "round-trip via fromKey"
        for
          crypto <- AesGcmCrypto.fromKey(key)
          ct     <- crypto.encrypt(input)
          back   <- crypto.decrypt(ct)
        yield assertTrue(back == input)
      },
      test("fromKey-built crypto's ciphertext is base64 (decodable, non-empty)") {
        val rawKey = (0 until 32).map(_.toByte).toArray
        val key    = Base64.getEncoder.encodeToString(rawKey)
        for
          crypto <- AesGcmCrypto.fromKey(key)
          ct     <- crypto.encrypt("x")
          decoded = Base64.getDecoder.decode(ct)
        yield assertTrue(decoded.length > 0)
      }
    )

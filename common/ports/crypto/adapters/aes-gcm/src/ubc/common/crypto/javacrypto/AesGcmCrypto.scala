package ubc.common.crypto.javacrypto

import ubc.common.crypto.Crypto
import zio.*

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.{GCMParameterSpec, SecretKeySpec}

/** Real [[Crypto]] adapter using AES-GCM (`AES/GCM/NoPadding`) from `javax.crypto`.
  *
  * AES-GCM is an AEAD construction: it produces an authentication tag along with the
  * ciphertext, so any tampering (or a wrong key) is detected on decrypt and surfaces as a
  * `Task` failure.
  *
  * '''Wire format''' (base64 of the concatenation):
  *   - 12-byte IV (random per call) || ciphertext || 16-byte GCM tag
  *
  * The IV is stored alongside the ciphertext because it is required for decryption and is
  * not secret. A fresh random IV per call is essential for AES-GCM security under the same
  * key.
  */
final class AesGcmCrypto(
    key: SecretKeySpec,
    secureRandom: SecureRandom
) extends Crypto:

  private val ivLengthBytes = 12   // AES-GCM standard IV length
  private val tagLengthBits = 128

  def encrypt(plaintext: String): Task[String] =
    ZIO.attempt {
      val iv = new Array[Byte](ivLengthBytes)
      secureRandom.nextBytes(iv)
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(tagLengthBits, iv))
      val ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8))
      // Output format: IV || ciphertext+tag, base64-encoded together.
      val combined = new Array[Byte](iv.length + ciphertext.length)
      java.lang.System.arraycopy(iv, 0, combined, 0, iv.length)
      java.lang.System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length)
      Base64.getEncoder.encodeToString(combined)
    }

  def decrypt(ciphertext: String): Task[String] =
    ZIO.attempt {
      val combined = Base64.getDecoder.decode(ciphertext)
      if combined.length < ivLengthBytes then
        throw new RuntimeException("Ciphertext too short — missing IV")
      val iv     = combined.slice(0, ivLengthBytes)
      val raw    = combined.slice(ivLengthBytes, combined.length)
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(tagLengthBits, iv))
      new String(cipher.doFinal(raw), StandardCharsets.UTF_8)
    }

object AesGcmCrypto:

  /** Construct a Crypto adapter from a base64-encoded 256-bit AES key (32 bytes raw).
    *
    * Fails with a clear error if the decoded key length is not 32 bytes. The caller is
    * responsible for sourcing the key from a Kubernetes secret and never logging it.
    *
    * No `.layer` is provided here intentionally: the key is sourced from each consuming
    * service's own config type, so the wiring layer lives next to that config rather than
    * in this common module.
    */
  def fromKey(base64Key: String): Task[AesGcmCrypto] =
    ZIO.attempt {
      val raw = Base64.getDecoder.decode(base64Key)
      if raw.length != 32 then
        throw new RuntimeException(s"Expected 256-bit AES key (32 bytes); got ${raw.length}")
      val key = new SecretKeySpec(raw, "AES")
      new AesGcmCrypto(key, new SecureRandom())
    }

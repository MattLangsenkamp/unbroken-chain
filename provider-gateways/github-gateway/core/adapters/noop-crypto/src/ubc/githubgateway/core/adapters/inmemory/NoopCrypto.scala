package ubc.githubgateway.core.adapters.inmemory

import neotype.*
import ubc.githubgateway.core.ports.Crypto
import ubc.githubgateway.domain.internal.EncryptedBytes
import zio.*

import java.nio.charset.StandardCharsets
import java.util.Base64

/** Identity [[Crypto]] for tests and local dev — base64 round-trips the bytes without
  * actually encrypting them. Satisfies the encrypt/decrypt contract while requiring no key.
  *
  * NOT for production. Wire the real key-backed adapter in the server module.
  */
final class NoopCrypto extends Crypto:

  def encrypt(plaintext: String): Task[EncryptedBytes] =
    ZIO.attempt {
      EncryptedBytes(Base64.getEncoder.encodeToString(plaintext.getBytes(StandardCharsets.UTF_8)))
    }

  def decrypt(ciphertext: EncryptedBytes): Task[String] =
    ZIO.attempt {
      new String(Base64.getDecoder.decode(ciphertext.unwrap), StandardCharsets.UTF_8)
    }

object NoopCrypto:
  val layer: ULayer[Crypto] = ZLayer.succeed(new NoopCrypto)

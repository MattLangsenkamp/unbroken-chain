package ubc.common.crypto.inmemory

import ubc.common.crypto.Crypto
import zio.*
import zio.test.*

object NoopCryptoSpec extends ZIOSpecDefault:

  override def spec =
    suite("NoopCrypto")(
      test("encrypt then decrypt round-trips arbitrary plaintext") {
        for
          crypto <- ZIO.service[Crypto]
          input  = "the quick brown fox - üñîçödé and bytes  "
          ct     <- crypto.encrypt(input)
          back   <- crypto.decrypt(ct)
        yield assertTrue(back == input)
      },
      test("encrypt of equal plaintext yields equal ciphertext (deterministic identity transform)") {
        for
          crypto <- ZIO.service[Crypto]
          a      <- crypto.encrypt("hello")
          b      <- crypto.encrypt("hello")
        yield assertTrue(a == b)
      }
    ).provide(NoopCrypto.layer)

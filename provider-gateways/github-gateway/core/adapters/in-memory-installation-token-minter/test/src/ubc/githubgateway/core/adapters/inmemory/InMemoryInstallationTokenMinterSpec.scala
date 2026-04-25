package ubc.githubgateway.core.adapters.inmemory

import ubc.githubgateway.core.ports.InstallationTokenMinter
import zio.*
import zio.test.*

object InMemoryInstallationTokenMinterSpec extends ZIOSpecDefault:

  override def spec =
    suite("InMemoryInstallationTokenMinter")(
      test("returns distinct JWTs across calls") {
        for
          minter <- ZIO.service[InstallationTokenMinter]
          a      <- minter.mintAppJwt()
          b      <- minter.mintAppJwt()
          c      <- minter.mintAppJwt()
        yield assertTrue(a != b, b != c, a != c)
      }
    ).provide(InMemoryInstallationTokenMinter.layer)

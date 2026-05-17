package ubc.githubgateway.domain.internal

import neotype.*
import zio.test.*

object TokensSpec extends ZIOSpecDefault:

  override def spec =
    suite("TokensSpec")(
      test("AppJwt wraps and unwraps a String") {
        val v: AppJwt = AppJwt("eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.x.y")
        assertTrue(v.unwrap == "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.x.y")
      },
      test("InstallationAccessToken wraps and unwraps a String") {
        val v: InstallationAccessToken = InstallationAccessToken("ghs_abcdef123456")
        assertTrue(v.unwrap == "ghs_abcdef123456")
      }
    )

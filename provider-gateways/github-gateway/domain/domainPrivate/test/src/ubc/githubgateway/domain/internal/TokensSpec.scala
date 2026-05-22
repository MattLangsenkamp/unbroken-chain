package ubc.githubgateway.domain.internal

import ubc.common.sensitive.Sensitive
import zio.test.*

object TokensSpec extends ZIOSpecDefault:

  override def spec =
    suite("TokensSpec")(
      test("AppJwt wraps and unwraps a String") {
        val v: AppJwt = AppJwt.sensitive("eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.x.y")
        assertTrue(AppJwt.unwrap(v) == "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.x.y")
      },
      test("InstallationAccessToken wraps and unwraps a String") {
        val v: InstallationAccessToken = InstallationAccessToken.sensitive("ghs_abcdef123456")
        assertTrue(InstallationAccessToken.unwrap(v) == "ghs_abcdef123456")
      },
      test("token types are marked Sensitive so ActivityEncoder redacts them") {
        summon[AppJwt <:< Sensitive]
        summon[InstallationAccessToken <:< Sensitive]
        assertCompletes
      }
    )

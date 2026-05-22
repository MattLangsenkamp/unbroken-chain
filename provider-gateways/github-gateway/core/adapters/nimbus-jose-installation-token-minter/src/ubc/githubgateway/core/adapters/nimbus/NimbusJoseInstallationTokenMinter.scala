package ubc.githubgateway.core.adapters.nimbus

import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.{JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import neotype.*
import ubc.githubgateway.core.ports.InstallationTokenMinter
import ubc.githubgateway.domain.AppId
import ubc.githubgateway.domain.internal.{AppJwt, GitHubGatewayConfig}
import zio.*

import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.{Base64, Date}

/** Real [[InstallationTokenMinter]] backed by `nimbus-jose-jwt` 9.x.
  *
  * Signs short-lived RS256 App JWTs using the configured RSA private key. The TTL is capped by GitHub at 10 minutes;
  * the default 9-minute TTL leaves comfortable headroom while keeping callers from constantly reminting.
  *
  * Per GitHub's guidance, `iat` is set to `now - 60s` so that minor clock skew between this service and GitHub doesn't
  * reject otherwise-valid JWTs.
  */
final class NimbusJoseInstallationTokenMinter(
    appId: AppId,
    privateKey: RSAPrivateKey,
    ttl: zio.Duration
) extends InstallationTokenMinter:

  private val signer = new RSASSASigner(privateKey)
  private val header = new JWSHeader(JWSAlgorithm.RS256)

  def mintAppJwt(): Task[AppJwt] =
    for
      now <- Clock.instant
      claims = new JWTClaimsSet.Builder()
        .issueTime(Date.from(now.minusSeconds(60)))
        .expirationTime(Date.from(now.plus(ttl)))
        .issuer(appId.unwrap.toString)
        .build()
      signedJwt = new SignedJWT(header, claims)
      _          <- ZIO.attempt(signedJwt.sign(signer))
      serialized <- ZIO.attempt(signedJwt.serialize())
    yield AppJwt.sensitive(serialized)

object NimbusJoseInstallationTokenMinter:

  /** Build a minter from a PEM-encoded private key string.
    *
    * The PEM must be PKCS#8 (`-----BEGIN PRIVATE KEY-----`). GitHub's default download format is PKCS#1; convert with:
    * {{{
    *   openssl pkcs8 -topk8 -nocrypt -in pkcs1.pem -out pkcs8.pem
    * }}}
    */
  def fromPem(appId: AppId, pem: String, ttl: zio.Duration): Task[NimbusJoseInstallationTokenMinter] =
    ZIO.attempt {
      val cleaned = pem
        .replace("-----BEGIN PRIVATE KEY-----", "")
        .replace("-----END PRIVATE KEY-----", "")
        .replaceAll("\\s", "")
      val der     = Base64.getDecoder.decode(cleaned)
      val keySpec = new PKCS8EncodedKeySpec(der)
      val factory = KeyFactory.getInstance("RSA")
      val key     = factory.generatePrivate(keySpec).asInstanceOf[RSAPrivateKey]
      new NimbusJoseInstallationTokenMinter(appId, key, ttl)
    }

  /** Production layer — reads the App id, PEM-encoded private key, and JWT TTL from the
    * shared [[GitHubGatewayConfig]]. Server bootstrap just lists this in `provide(...)`.
    */
  val layer: ZLayer[GitHubGatewayConfig, Throwable, InstallationTokenMinter] =
    ZLayer.fromZIO {
      ZIO.serviceWithZIO[GitHubGatewayConfig] { cfg =>
        fromPem(cfg.githubAppId, cfg.githubAppPrivateKeyPem, cfg.appJwtTtl)
      }
    }

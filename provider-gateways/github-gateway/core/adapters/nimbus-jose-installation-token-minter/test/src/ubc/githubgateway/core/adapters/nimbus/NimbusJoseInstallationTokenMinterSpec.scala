package ubc.githubgateway.core.adapters.nimbus

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jwt.SignedJWT
import neotype.*
import ubc.githubgateway.domain.AppId
import zio.*
import zio.test.*

import java.security.KeyPairGenerator
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}

object NimbusJoseInstallationTokenMinterSpec extends ZIOSpecDefault:

  /** Shared 2048-bit RSA keypair generated once for the whole spec — keypair generation is
    * expensive and we don't need a fresh one per test.
    */
  private val keyPair = {
    val gen = KeyPairGenerator.getInstance("RSA")
    gen.initialize(2048)
    gen.generateKeyPair()
  }
  private val privateKey: RSAPrivateKey = keyPair.getPrivate.asInstanceOf[RSAPrivateKey]
  private val publicKey: RSAPublicKey   = keyPair.getPublic.asInstanceOf[RSAPublicKey]

  private val testAppId: AppId = AppId(123456L)
  private val testTtl: Duration = 9.minutes

  /** Encode an RSAPrivateKey from KeyPairGenerator as PKCS#8 PEM.
    * `KeyPairGenerator.getInstance("RSA")` returns a key whose `getEncoded` is already
    * PKCS#8-encoded, so we just base64 it and wrap in the standard PEM headers.
    */
  private def pkcs8Pem(key: RSAPrivateKey): String =
    val pkcs8Bytes = key.getEncoded
    val b64        = java.util.Base64.getEncoder.encodeToString(pkcs8Bytes)
    val wrapped    = b64.grouped(64).mkString("\n")
    s"-----BEGIN PRIVATE KEY-----\n$wrapped\n-----END PRIVATE KEY-----\n"

  override def spec =
    suite("NimbusJoseInstallationTokenMinter")(
      test("mintAppJwt produces a parseable RS256 JWT") {
        val minter = new NimbusJoseInstallationTokenMinter(testAppId, privateKey, testTtl)
        for
          jwt    <- minter.mintAppJwt()
          parsed = SignedJWT.parse(jwt.unwrap)
        yield assertTrue(parsed.getHeader.getAlgorithm == JWSAlgorithm.RS256)
      },
      test("issuer claim equals appId") {
        val minter = new NimbusJoseInstallationTokenMinter(testAppId, privateKey, testTtl)
        for
          jwt    <- minter.mintAppJwt()
          parsed = SignedJWT.parse(jwt.unwrap)
        yield assertTrue(parsed.getJWTClaimsSet.getIssuer == testAppId.unwrap.toString)
      },
      test("expirationTime - issueTime stays within ttl plus skew window") {
        val minter = new NimbusJoseInstallationTokenMinter(testAppId, privateKey, testTtl)
        for
          jwt      <- minter.mintAppJwt()
          parsed   = SignedJWT.parse(jwt.unwrap)
          claims   = parsed.getJWTClaimsSet
          issued   = claims.getIssueTime.toInstant
          expires  = claims.getExpirationTime.toInstant
          deltaSec = expires.getEpochSecond - issued.getEpochSecond
          ttlSec   = testTtl.getSeconds + 60L // 60s clock-skew is rolled into issueTime
        yield assertTrue(
          deltaSec <= ttlSec + 10L,
          deltaSec >= ttlSec - 10L
        )
      },
      test("signature verifies under the matching public key") {
        val minter = new NimbusJoseInstallationTokenMinter(testAppId, privateKey, testTtl)
        for
          jwt    <- minter.mintAppJwt()
          parsed = SignedJWT.parse(jwt.unwrap)
          ok     <- ZIO.attempt(parsed.verify(new RSASSAVerifier(publicKey)))
        yield assertTrue(ok)
      },
      test("fromPem round-trips a PKCS#8 PEM-encoded key and produces a JWT verifiable under original public key") {
        val pem = pkcs8Pem(privateKey)
        for
          minter <- NimbusJoseInstallationTokenMinter.fromPem(testAppId, pem, testTtl)
          jwt    <- minter.mintAppJwt()
          parsed = SignedJWT.parse(jwt.unwrap)
          ok     <- ZIO.attempt(parsed.verify(new RSASSAVerifier(publicKey)))
        yield assertTrue(ok, parsed.getJWTClaimsSet.getIssuer == testAppId.unwrap.toString)
      },
      test("issueTime equals now - 60s under TestClock (60s clock-skew tolerance per GitHub guidance)") {
        val minter = new NimbusJoseInstallationTokenMinter(testAppId, privateKey, testTtl)
        val fixedNow = java.time.Instant.parse("2026-04-25T12:00:00Z")
        for
          _      <- TestClock.setTime(fixedNow)
          jwt    <- minter.mintAppJwt()
          parsed = SignedJWT.parse(jwt.unwrap)
          issued = parsed.getJWTClaimsSet.getIssueTime.toInstant
        yield assertTrue(issued == fixedNow.minusSeconds(60))
      }
    )

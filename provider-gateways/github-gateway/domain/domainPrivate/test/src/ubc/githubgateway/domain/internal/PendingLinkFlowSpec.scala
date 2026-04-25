package ubc.githubgateway.domain.internal

import neotype.*
import ubc.githubgateway.domain.{CodeChallenge, LinkState}
import zio.test.*

import java.time.Instant

object PendingLinkFlowSpec extends ZIOSpecDefault:

  override def spec =
    suite("PendingLinkFlowSpec")(
      test("CodeVerifier wraps and unwraps a 43+ char URL-safe String") {
        val v: CodeVerifier = CodeVerifier("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        assertTrue(v.unwrap == "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
      },
      test("EncryptedBytes wraps and unwraps a base64-encoded ciphertext String") {
        val payload: String   = "ZmFrZS1jaXBoZXJ0ZXh0LWJhc2U2NA=="
        val v: EncryptedBytes = EncryptedBytes(payload)
        assertTrue(v.unwrap == payload)
      },
      // Two EncryptedBytes wrapping equal content MUST compare equal.
      // We learned during TDD that Newtype[Array[Byte]] (and Newtype[IArray[Byte]]) inherit
      // Array reference equality at runtime, which broke this. Hence the underlying repr is
      // a base64 String, which has structural equality.
      test("EncryptedBytes with equal content compare equal") {
        val a       = EncryptedBytes("Zm9vYmFy")
        val b       = EncryptedBytes("Zm9vYmFy")
        val isEqual = a == b
        assertTrue(isEqual)
      },
      test("EncryptedBytes with different content compare unequal") {
        val a          = EncryptedBytes("Zm9vYmFy")
        val b          = EncryptedBytes("YmFyYmF6")
        val isUnequal  = a != b
        assertTrue(isUnequal)
      },
      test("PendingLinkFlow holds state, encrypted verifier, challenge, and timestamps") {
        val createdAt = Instant.parse("2026-04-25T12:00:00Z")
        val expiresAt = Instant.parse("2026-04-25T12:10:00Z")
        val ciphertext = EncryptedBytes("Zm9vYmFyYmF6cXV4")
        val flow = PendingLinkFlow(
          state             = LinkState("nonce-123"),
          encryptedVerifier = ciphertext,
          codeChallenge     = CodeChallenge("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"),
          createdAt         = createdAt,
          expiresAt         = expiresAt
        )
        assertTrue(
          flow.state == LinkState("nonce-123"),
          flow.encryptedVerifier == ciphertext,
          flow.codeChallenge == CodeChallenge("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"),
          flow.createdAt == createdAt,
          flow.expiresAt == expiresAt
        )
      }
    )

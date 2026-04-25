package ubc.githubgateway.domain.internal

import neotype.*
import ubc.githubgateway.domain.{CodeChallenge, LinkState}

import java.time.Instant

/** PKCE code verifier: a 43–128 char URL-safe random string. Hashed with SHA-256 to produce
  * the [[ubc.githubgateway.domain.CodeChallenge]] that GitHub stores; the verifier itself
  * stays private to this service.
  */
object CodeVerifier extends Newtype[String]
type CodeVerifier = CodeVerifier.Type

/** Opaque ciphertext blob — the encrypted [[CodeVerifier]] persisted on `pending_link_flow`.
  *
  * Backed by a base64-encoded `String` rather than `Array[Byte]` for two reasons:
  *   1. neotype wrapping `Array[Byte]`/`IArray[Byte]` inherits reference equality at runtime,
  *      so two ciphertext values with identical content would compare unequal — broken for
  *      domain modelling, tests, and pattern-matching.
  *   2. base64 is the natural interchange representation; the Magnum codec extension (added
  *      in a later layer) decodes to/from JDBC `BYTEA`.
  *
  * Always store the raw output of [[java.util.Base64.getEncoder.encodeToString]] (or
  * `getUrlEncoder` if URL-safety is needed) — never hex, never raw bytes.
  */
object EncryptedBytes extends Newtype[String]
type EncryptedBytes = EncryptedBytes.Type

/** Pending link flow row — created on `POST /links/initiate`, consumed on the GitHub
  * callback. Cleaned up by a periodic sweep once `expiresAt` passes.
  *
  * @param state
  *   server-generated nonce echoed via the GitHub callback's `state` parameter
  * @param encryptedVerifier
  *   ciphertext of the PKCE verifier — never logged, never returned
  * @param codeChallenge
  *   the SHA-256 challenge sent to GitHub (kept for audit/debugging)
  * @param createdAt
  *   row creation timestamp
  * @param expiresAt
  *   absolute expiry; a callback after this time MUST be rejected with
  *   [[ubc.githubgateway.domain.internal.LinkError.StateExpired]]
  */
final case class PendingLinkFlow(
    state: LinkState,
    encryptedVerifier: EncryptedBytes,
    codeChallenge: CodeChallenge,
    createdAt: Instant,
    expiresAt: Instant
)

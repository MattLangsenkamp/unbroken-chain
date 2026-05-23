package ubc.githubgateway.domain.internal

import ubc.githubgateway.domain.LinkState

import java.time.Instant

/** Pending link flow row — created on `POST /links/initiate`, consumed on the GitHub callback. Cleaned up by a periodic
  * sweep once `expiresAt` passes.
  *
  * @param state
  *   server-generated nonce echoed via the GitHub callback's `state` parameter
  * @param createdAt
  *   row creation timestamp
  * @param expiresAt
  *   absolute expiry; a callback after this time MUST be rejected with
  *   [[ubc.githubgateway.domain.internal.LinkError.StateExpired]]
  */
final case class PendingLinkFlow(
    state: LinkState,
    createdAt: Instant,
    expiresAt: Instant
)

package ubc.githubgateway.domain

import java.time.Instant

/** Result of `POST /links/initiate`: where the user should be redirected to install the app, and the server-generated
  * state nonce we will validate when GitHub calls our callback.
  *
  * @param installUrl
  *   the GitHub URL the caller should redirect the user to
  * @param state
  *   server-generated nonce echoed back via the callback's `state` parameter
  * @param expiresAt
  *   absolute time after which the corresponding pending flow row will be rejected
  */
final case class LinkInitiation(
    installUrl: InstallUrl,
    state: LinkState,
    expiresAt: Instant
)

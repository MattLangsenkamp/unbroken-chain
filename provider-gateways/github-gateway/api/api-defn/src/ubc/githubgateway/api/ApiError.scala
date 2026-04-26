package ubc.githubgateway.api

/** Public, transport-friendly error envelope for the GitHub Gateway API.
  *
  * The driving adapter maps internal `LinkError` / `UnlinkError` / `ReconcileError` /
  * `WebhookError` variants to one of these — domain-private error names never cross
  * the API boundary.
  *
  * @param code
  *   stable machine-readable error code (e.g. "STATE_NOT_FOUND"); safe to switch on
  * @param message
  *   human-readable message; not safe to switch on; safe to display
  */
final case class ApiError(code: String, message: String)

object ApiError:
  val StateNotFound: ApiError =
    ApiError("STATE_NOT_FOUND", "No pending link flow matched the supplied state.")

  val StateExpired: ApiError =
    ApiError("STATE_EXPIRED", "The pending link flow has expired.")

  val InstallationNotFound: ApiError =
    ApiError("INSTALLATION_NOT_FOUND", "No installation was found for the supplied id.")

  val InvalidSignature: ApiError =
    ApiError("INVALID_SIGNATURE", "Webhook signature did not match the request body.")

  def gitHubFailure(message: String): ApiError =
    ApiError("GITHUB_FAILURE", message)

  def malformedPayload(message: String): ApiError =
    ApiError("MALFORMED_PAYLOAD", message)

  def internal(message: String): ApiError =
    ApiError("INTERNAL_ERROR", message)

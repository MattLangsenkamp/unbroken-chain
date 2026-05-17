package ubc.githubgateway.api

/** Public, transport-friendly error envelope for the GitHub Gateway API.
  *
  * The driving adapter maps internal `LinkError` / `UnlinkError` / `ReconcileError` /
  * `WebhookError` variants to one of these — domain-private error names never cross
  * the API boundary.
  *
  * Wire shape is `{ "code": string, "message": string }`. `code` is the stable
  * machine-readable identifier (clients switch on it); `message` is human-readable
  * and safe to display.
  */
enum ApiError:
  case StateNotFound
  case StateExpired
  case InstallationNotFound
  case InvalidSignature
  case GitHubFailure(detail: String)
  case MalformedPayload(detail: String)
  case Internal(detail: String)

  def code: String = this match
    case StateNotFound        => "STATE_NOT_FOUND"
    case StateExpired         => "STATE_EXPIRED"
    case InstallationNotFound => "INSTALLATION_NOT_FOUND"
    case InvalidSignature     => "INVALID_SIGNATURE"
    case _: GitHubFailure     => "GITHUB_FAILURE"
    case _: MalformedPayload  => "MALFORMED_PAYLOAD"
    case _: Internal          => "INTERNAL_ERROR"

  def message: String = this match
    case StateNotFound        => "No pending link flow matched the supplied state."
    case StateExpired         => "The pending link flow has expired."
    case InstallationNotFound => "No installation was found for the supplied id."
    case InvalidSignature     => "Webhook signature did not match the request body."
    case GitHubFailure(m)     => m
    case MalformedPayload(m)  => m
    case Internal(m)          => m

object ApiError:
  // Smart constructors retained as the ergonomic call site for parametric variants.
  def gitHubFailure(message: String): ApiError    = GitHubFailure(message)
  def malformedPayload(message: String): ApiError = MalformedPayload(message)
  def internal(message: String): ApiError         = Internal(message)

  /** Inverse of the wire shape: turn `{code, message}` back into a typed [[ApiError]].
    *
    * For parameter-less variants the supplied `message` is ignored — those variants own
    * their canonical message. Only the parametric variants (`GITHUB_FAILURE`,
    * `MALFORMED_PAYLOAD`, `INTERNAL_ERROR`) carry the wire message into the value.
    */
  def fromWire(code: String, message: String): Either[String, ApiError] = code match
    case "STATE_NOT_FOUND"        => Right(StateNotFound)
    case "STATE_EXPIRED"          => Right(StateExpired)
    case "INSTALLATION_NOT_FOUND" => Right(InstallationNotFound)
    case "INVALID_SIGNATURE"      => Right(InvalidSignature)
    case "GITHUB_FAILURE"         => Right(GitHubFailure(message))
    case "MALFORMED_PAYLOAD"      => Right(MalformedPayload(message))
    case "INTERNAL_ERROR"         => Right(Internal(message))
    case other                    => Left(s"Unknown ApiError code: $other")

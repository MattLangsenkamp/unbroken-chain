package ubc.githubgateway.domain.internal

/** Errors raised while completing a link flow (initiate / callback). */
enum LinkError:
  /** No `pending_link_flow` row matched the supplied state nonce. */
  case StateNotFound

  /** Matching row found but its `expiresAt` has passed. */
  case StateExpired

  /** GitHub's API or webhook delivery failed in a way we cannot recover from inline. */
  case GitHubFailure(message: String)

/** Errors raised while removing an installation. */
enum UnlinkError:
  /** GitHub's API rejected the unlink (e.g. installation already revoked, network issue). */
  case GitHubFailure(message: String)

/** Errors raised while reconciling our mirror against GitHub's authoritative repo set. */
enum ReconcileError:
  /** No local installation row exists for the supplied id. */
  case InstallationNotFound

  /** GitHub's API call to list selected repositories failed. */
  case GitHubFailure(message: String)

/** Errors raised while accepting a webhook delivery. */
enum WebhookError:
  /** HMAC signature on `X-Hub-Signature-256` did not match the body. Reject with 401. */
  case InvalidSignature

  /** Body parsed as JSON but did not match the expected event shape. */
  case MalformedPayload(message: String)

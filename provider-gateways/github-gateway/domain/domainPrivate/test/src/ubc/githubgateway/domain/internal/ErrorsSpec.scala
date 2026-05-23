package ubc.githubgateway.domain.internal

import zio.test.*

object ErrorsSpec extends ZIOSpecDefault:

  override def spec =
    suite("ErrorsSpec")(
      test("LinkError covers StateNotFound, StateExpired, and GitHubFailure(message)") {
        def label(e: LinkError): String = e match
          case LinkError.StateNotFound          => "not-found"
          case LinkError.StateExpired           => "expired"
          case LinkError.GitHubFailure(message) => s"gh:$message"
        assertTrue(
          label(LinkError.StateNotFound) == "not-found",
          label(LinkError.StateExpired) == "expired",
          label(LinkError.GitHubFailure("rate limited")) == "gh:rate limited"
        )
      },
      test("UnlinkError covers GitHubFailure(message)") {
        def label(e: UnlinkError): String = e match
          case UnlinkError.GitHubFailure(message) => s"gh:$message"
        assertTrue(label(UnlinkError.GitHubFailure("403 forbidden")) == "gh:403 forbidden")
      },
      test("ReconcileError covers InstallationNotFound and GitHubFailure(message)") {
        def label(e: ReconcileError): String = e match
          case ReconcileError.InstallationNotFound   => "not-found"
          case ReconcileError.GitHubFailure(message) => s"gh:$message"
        assertTrue(
          label(ReconcileError.InstallationNotFound) == "not-found",
          label(ReconcileError.GitHubFailure("502 bad gateway")) == "gh:502 bad gateway"
        )
      },
      test("WebhookError covers InvalidSignature and MalformedPayload(message)") {
        def label(e: WebhookError): String = e match
          case WebhookError.InvalidSignature          => "bad-sig"
          case WebhookError.MalformedPayload(message) => s"malformed:$message"
        assertTrue(
          label(WebhookError.InvalidSignature) == "bad-sig",
          label(WebhookError.MalformedPayload("missing action field")) == "malformed:missing action field"
        )
      }
    )

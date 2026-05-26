package ubc.githubgateway.domain.adapters.json

import ubc.common.pagination.PaginationGenerators
import ubc.common.testutils.codec.json.JsonRoundTrip
import ubc.githubgateway.api.ApiError
import ubc.githubgateway.domain.DomainGenerators
import zio.test.{Gen, ZIOSpecDefault}

object PublicJsonCodecsSpec extends ZIOSpecDefault:

  import PublicJsonCodecs.given

  // ApiError lives outside the domain* modules so it has no DomainGenerators entry.
  // Two generators, with different equalities for the round-trip:
  //   * parameter-less variants — wire `message` is dropped on decode (only `code` is meaningful),
  //     so compare by `code`.
  //   * parametric variants — `detail` round-trips faithfully, so a full `==` catches payload
  //     corruption that a code-only equality would miss.
  private val parameterlessApiError: Gen[Any, ApiError] = Gen.oneOf(
    Gen.const(ApiError.StateNotFound),
    Gen.const(ApiError.StateExpired),
    Gen.const(ApiError.InstallationNotFound),
    Gen.const(ApiError.InvalidSignature)
  )

  private val parametricApiError: Gen[Any, ApiError] = Gen.oneOf(
    Gen.alphaNumericString.map(ApiError.GitHubFailure(_)),
    Gen.alphaNumericString.map(ApiError.MalformedPayload(_)),
    Gen.alphaNumericString.map(ApiError.Internal(_))
  )

  private val apiErrorByCode: (ApiError, ApiError) => Boolean = (a, b) => a.code == b.code

  override def spec = suite("PublicJsonCodecsSpec")(
    // --- Leaf newtypes ---
    JsonRoundTrip.spec("InstallationId", DomainGenerators.installationId),
    JsonRoundTrip.spec("GhInstallationId", DomainGenerators.ghInstallationId),
    JsonRoundTrip.spec("AccountLogin", DomainGenerators.accountLogin),
    JsonRoundTrip.spec("AccountId", DomainGenerators.accountId),
    JsonRoundTrip.spec("RepositoryId", DomainGenerators.repositoryId),
    JsonRoundTrip.spec("GhRepositoryId", DomainGenerators.ghRepositoryId),
    JsonRoundTrip.spec("RepoFullName", DomainGenerators.repoFullName),
    JsonRoundTrip.spec("LinkState", DomainGenerators.linkState),
    JsonRoundTrip.spec("DeliveryId", DomainGenerators.deliveryId),
    JsonRoundTrip.spec("EventType", DomainGenerators.eventType),
    JsonRoundTrip.spec("EventAction", DomainGenerators.eventAction),
    JsonRoundTrip.spec("InstallUrl", DomainGenerators.installUrl),
    JsonRoundTrip.spec("AppId", DomainGenerators.appId),
    JsonRoundTrip.spec("AppSlug", DomainGenerators.appSlug),
    // --- Enums ---
    JsonRoundTrip.spec("AccountType", DomainGenerators.accountType),
    JsonRoundTrip.spec("InstallationStatus", DomainGenerators.installationStatus),
    // --- Case classes ---
    JsonRoundTrip.spec("Installation", DomainGenerators.installation),
    JsonRoundTrip.spec("LinkedRepo", DomainGenerators.linkedRepo),
    JsonRoundTrip.spec("LinkInitiation", DomainGenerators.linkInitiation),
    JsonRoundTrip.spec("ReconcileSummary", DomainGenerators.reconcileSummary),
    // --- Pagination ---
    // The Page[A] codec is parametric over A — Page[Int] exercises the same generic path; the
    // domain element types (Installation, LinkedRepo, …) are round-tripped on their own above.
    JsonRoundTrip.spec("PageRequest", PaginationGenerators.pageRequest),
    JsonRoundTrip.spec("Page[Int]", PaginationGenerators.page[Int]),
    // --- ApiError envelope (transform via ApiErrorWire) ---
    JsonRoundTrip.spec("ApiError (parameter-less, code-only)", parameterlessApiError, apiErrorByCode),
    JsonRoundTrip.spec("ApiError (parametric, full ==)", parametricApiError)
  )

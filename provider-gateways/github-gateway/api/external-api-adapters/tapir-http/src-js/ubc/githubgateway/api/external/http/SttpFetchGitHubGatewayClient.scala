package ubc.githubgateway.api.external.http

import sttp.client3.impl.zio.FetchZioBackend
import sttp.model.Uri
import ubc.githubgateway.api.GitHubGatewayApi

/** Scala.js entry point for the typed client. Tyrian instantiates this synchronously at SPA init time — no ZLayer in
  * the JS world — supplying the browser Fetch API backend. The request/response logic is the shared
  * [[TapirGitHubGatewayClient]]; this only chooses the Scala.js transport.
  *
  * The name + package are kept stable so the presentation SPA's call site is unchanged.
  */
object SttpFetchGitHubGatewayClient:

  /** Build a client from a base URL string. */
  def apply(baseUrl: String): GitHubGatewayApi =
    new TapirGitHubGatewayClient(FetchZioBackend(), Uri.unsafeParse(baseUrl))

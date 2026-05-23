package ubc.common

import scala.collection.mutable

import io.opentelemetry.api.trace.{SpanKind, StatusCode}
import io.opentelemetry.semconv.{HttpAttributes, UrlAttributes, UserAgentAttributes}
import sttp.model.HeaderNames
import sttp.monad.MonadError
import sttp.tapir.AnyEndpoint
import sttp.tapir.model.ServerRequest
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.interceptor.*
import sttp.tapir.server.interpreter.BodyListener
import sttp.tapir.server.model.ServerResponse
import sttp.tapir.server.ziohttp.ZioHttpServerOptions
import zio.*
import zio.telemetry.opentelemetry.common.{Attribute, Attributes}
import zio.telemetry.opentelemetry.context.IncomingContextCarrier
import zio.telemetry.opentelemetry.tracing.{StatusMapper, Tracing}
import zio.telemetry.opentelemetry.tracing.propagation.TraceContextPropagator

object TapirTracingInterceptor:

  /** Returns ZioHttpServerOptions with a TracingInterceptor wired in. Pass the result to ZioHttpInterpreter(...) so
    * every Tapir endpoint gets an OTel server span that extracts the incoming W3C trace context.
    */
  def serverOptions(tracing: Tracing): ZioHttpServerOptions[Any] =
    ZioHttpServerOptions.customiseInterceptors
      .appendInterceptor(new TracingInterceptor(tracing, Seq.empty))
      .options

// Adapted from io.kinoplan:utils-zio-tapir-opentelemetry (Apache 2.0).
// Original source: https://github.com/kinoplan/utils/blob/main/zio/tapir/opentelemetry/src/main/scala/io/kinoplan/utils/zio/tapir/opentelemetry/TracingInterceptor.scala
// Inlined because the library is not published to Maven Central.
// Scala 2 kind-projector syntax (`F[A, *]`) rewritten as Scala 3 type lambdas (`[X] =>> F[A, X]`).
private class TracingInterceptor[R1](tracing: Tracing, ignoreEndpoints: Seq[AnyEndpoint])
    extends RequestInterceptor[[X] =>> ZIO[R1, Throwable, X]]:

  override def apply[R, B](
      responder: Responder[[X] =>> RIO[R1, X], B],
      requestHandler: EndpointInterceptor[[X] =>> RIO[R1, X]] => RequestHandler[[X] =>> RIO[R1, X], R, B]
  ): RequestHandler[[X] =>> RIO[R1, X], R, B] =
    new RequestHandler[[X] =>> RIO[R1, X], R, B]:

      override def apply(
          request: ServerRequest,
          endpoints: List[ServerEndpoint[R, [X] =>> RIO[R1, X]]]
      )(implicit monad: MonadError[[X] =>> RIO[R1, X]]): RIO[R1, RequestResult[B]] =
        val statusMapper = StatusMapper.failureThrowable(_ => StatusCode.ERROR)
        val attributes   = List(
          Attribute(HttpAttributes.HTTP_REQUEST_METHOD, request.method.method),
          Attribute(UrlAttributes.URL_FULL, request.uri.toString())
        ) ++ request.uri.scheme.map(Attribute(UrlAttributes.URL_SCHEME, _)).toList ++
          request
            .header(HeaderNames.UserAgent)
            .map(Attribute(UserAgentAttributes.USER_AGENT_ORIGINAL, _))
            .toList
        tracing.extractSpan(
          TraceContextPropagator.default,
          IncomingContextCarrier.default(
            mutable.Map(request.headers.map(h => h.name -> h.value)*)
          ),
          request.method.method,
          spanKind = SpanKind.SERVER,
          statusMapper = statusMapper,
          attributes = Attributes.fromList(attributes)
        )(
          requestHandler(new EndpointTracingInterceptor[R1](tracing))(
            request,
            endpoints.filterNot(se => ignoreEndpoints.contains(se.endpoint))
          )
        )

private class EndpointTracingInterceptor[R1](tracing: Tracing) extends EndpointInterceptor[[X] =>> RIO[R1, X]]:

  override def apply[B](
      responder: Responder[[X] =>> RIO[R1, X], B],
      endpointHandler: EndpointHandler[[X] =>> RIO[R1, X], B]
  ): EndpointHandler[[X] =>> RIO[R1, X], B] =
    new EndpointHandler[[X] =>> RIO[R1, X], B]:

      override def onDecodeSuccess[A, U, I](
          ctx: DecodeSuccessContext[[X] =>> RIO[R1, X], A, U, I]
      )(implicit
          monad: MonadError[[X] =>> RIO[R1, X]],
          bodyListener: BodyListener[[X] =>> RIO[R1, X], B]
      ): RIO[R1, ServerResponse[B]] =
        onEndpoint(ctx.endpoint) *> endpointHandler.onDecodeSuccess(ctx).tap(onResponse)

      override def onSecurityFailure[A](
          ctx: SecurityFailureContext[[X] =>> RIO[R1, X], A]
      )(implicit
          monad: MonadError[[X] =>> RIO[R1, X]],
          bodyListener: BodyListener[[X] =>> RIO[R1, X], B]
      ): RIO[R1, ServerResponse[B]] =
        onEndpoint(ctx.endpoint) *> endpointHandler.onSecurityFailure(ctx).tap(onResponse)

      override def onDecodeFailure(
          ctx: DecodeFailureContext
      )(implicit
          monad: MonadError[[X] =>> RIO[R1, X]],
          bodyListener: BodyListener[[X] =>> RIO[R1, X], B]
      ): RIO[R1, Option[ServerResponse[B]]] =
        onEndpoint(ctx.endpoint) *>
          endpointHandler.onDecodeFailure(ctx).tap(_.fold(ZIO.unit)(onResponse))

      private def onResponse(response: ServerResponse[B]): UIO[Unit] =
        tracing
          .setAttribute(HttpAttributes.HTTP_RESPONSE_STATUS_CODE.getKey, response.code.code.toLong)
          .unit

      private def onEndpoint(endpoint: AnyEndpoint): UIO[Unit] =
        val route  = endpoint.showPathTemplate(showQueryParam = None, includeAuth = false)
        val method = endpoint.method.fold("")(_.method)
        tracing
          .setAttribute(HttpAttributes.HTTP_ROUTE, route)
          .zipRight(tracing.getCurrentSpanUnsafe.map(_.updateName(s"$method $route")))
          .unit

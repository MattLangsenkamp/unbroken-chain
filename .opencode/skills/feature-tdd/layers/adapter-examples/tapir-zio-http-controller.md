# Tapir + ZIO HTTP Controller — Reference

Use this when your driving adapter is an HTTP controller using Tapir + ZIO HTTP.

## Location

`<service>/api/internal-api-adapters/http/src/ubc/<service>/api/internal/http/`

## Implementation

```scala
package ubc.<service>.api.internal.http

import ubc.<service>.core.<Feature>Service
import ubc.<service>.domain.*
import ubc.<service>.domain.adapters.json.PublicJsonCodecs.given
import ubc.common.TapirTracingInterceptor
import sttp.tapir.ztapir.*
import sttp.tapir.server.ziohttp.*
import sttp.tapir.json.zio.*
import zio.*
import zio.http.{Response, Routes}
import zio.telemetry.opentelemetry.tracing.Tracing

// Inbound HTTP adapter. Decodes requests, delegates to core. No business logic.
object <Feature>HttpController:

  val doThingEndpoint =
    endpoint.post
      .in("path" / "to" / "resource")
      .in(jsonBody[RequestType])
      .out(jsonBody[ResponseType])
      .errorOut(stringBody)

  def routes(service: <Feature>Service, tracing: Tracing): Routes[Any, Response] =
    val interpreter = ZioHttpInterpreter(TapirTracingInterceptor.serverOptions(tracing))
    val doThing = doThingEndpoint.zServerLogic[Any] { input =>
      service.doThing(input).mapError(_.getMessage)
    }
    interpreter.toHttp(doThing)

  val layer: ZLayer[<Feature>Service & Tracing, Nothing, Routes[Any, Response]] =
    ZLayer.fromFunction(routes)
```

Rules:
- Decode request → call service → encode response. Nothing else.
- Endpoint shapes must match `api/api-defn` — never invent new HTTP contracts here
- Depends on `api/api-defn` and `core/core-impl`. Never depends on port traits or adapter modules directly.

## build.mill

```scala
object http extends ScalaModule {
  def scalaVersion = scalaVer
  override def moduleDeps = Seq(
    `api-defn`.jvm,
    core.`core-impl`,
    domain.domainPublicAdapterExtensions.`zio-json`.jvm,
    common.`tapir-tracing-interceptor`
  )
  override def mvnDeps = zioDeps ++ tapirServerDeps ++ neotypeTapirDeps

  object test extends ScalaTests {
    def testFramework = "zio.test.sbt.ZTestFramework"
    override def mvnDeps = super.mvnDeps() ++ zioTestDeps ++ tapirClientDeps ++ Seq(
      mvn"com.softwaremill.sttp.tapir::tapir-stub-server:1.11.9"
    )
    override def moduleDeps = super.moduleDeps ++ Seq(
      core.adapters.`in-memory-<name>`   // inject in-memory core via this
    )
  }
}
```

## Tests — TapirStubInterpreter

Test HTTP routes without a running server. Inject an in-memory core service.

```scala
import sttp.client3.{basicRequest, UriContext}
import sttp.client3.testing.SttpBackendStub
import sttp.client3.impl.zio.RIOMonadAsyncError
import sttp.tapir.server.stub.TapirStubInterpreter
import sttp.tapir.ztapir.*

object <Feature>HttpControllerSpec extends ZIOSpecDefault:
  override def spec = suite("<Feature>HttpControllerSpec")(
    test("POST /path/to/resource returns 200 with expected response") {
      for
        svc <- ZIO.service[<Feature>Service]
        backend = TapirStubInterpreter(SttpBackendStub(new RIOMonadAsyncError[Any]))
          .whenServerEndpointRunLogic(
            <Feature>HttpController.doThingEndpoint.zServerLogic[Any] { input =>
              svc.doThing(input).mapError(_.getMessage)
            }
          )
          .backend()
        resp <- basicRequest
          .post(uri"http://test/path/to/resource")
          .body("""{"field":"value"}""")
          .send(backend)
      yield assertTrue(resp.code.isSuccess)
    }
  ).provide(InMemory<name>Repository.layer, <Feature>Service.layer)
```

Run: `./mill <service>.api.\`internal-api-adapters\`.http.test`

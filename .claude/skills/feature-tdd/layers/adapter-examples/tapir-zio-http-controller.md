# Tapir + ZIO HTTP Controller — Reference

Use this when your driving adapter is an HTTP controller using Tapir + ZIO HTTP.

## Location

`<service>/api/internal-api-adapters/http/src/ubc/<service>/api/internal/http/`

## Implementation

The endpoint shapes are **not** defined here — they live in the shared `api/shared-adapter-dependencies/tapir-endpoints` module (`<Feature>Endpoints`) so the server and every client share one wire contract. The controller imports them and attaches server logic.

```scala
package ubc.<service>.api.internal.http

import ubc.<service>.api.endpoints.<Feature>Endpoints.*  // shared endpoint vals
import ubc.<service>.core.<Feature>Service
import ubc.<service>.domain.*
import ubc.common.TapirTracingInterceptor
import sttp.tapir.ztapir.*
import sttp.tapir.server.ziohttp.*
import zio.*
import zio.http.{Response, Routes}
import zio.telemetry.opentelemetry.tracing.Tracing

// Inbound HTTP adapter. Decodes requests, delegates to core. No business logic.
object <Feature>HttpController:

  def routes(service: <Feature>Service, tracing: Tracing): Routes[Any, Response] =
    val interpreter = ZioHttpInterpreter(TapirTracingInterceptor.serverOptions(tracing))
    // doThingEndpoint comes from the shared tapir-endpoints module.
    val doThing = doThingEndpoint.zServerLogic[Any] { input =>
      service.doThing(input).mapError(toApiError)
    }
    interpreter.toHttp(doThing)

  val layer: ZLayer[<Feature>Service & Tracing, Nothing, Routes[Any, Response]] =
    ZLayer.fromFunction(routes)
```

Rules:
- Decode request → call service → encode response. Nothing else.
- Endpoint shapes live in `api/shared-adapter-dependencies/tapir-endpoints` — import them; never define a second copy here (that is the duplication this layout exists to prevent).
- Depends on `api/shared-adapter-dependencies/tapir-endpoints.jvm` (shapes) and `core/core-impl` (service). Never depends on port traits, the typed-client module, or other adapters directly.
- The Schema-derivation (`jsonBody`, `neotype.interop.tapir.given`, `-Xmax-inlines 64`) lives in `tapir-endpoints`, not here — this module only adds the ZIO HTTP server interpreter.

## build.mill

```scala
object http extends ScalaModule {
  def scalaVersion = scalaVer
  override def moduleDeps = Seq(
    `api-defn`.jvm,
    `shared-adapter-dependencies`.`tapir-endpoints`.jvm, // shared endpoint shapes (brings Schema derivation)
    core.`core-impl`,
    common.`tapir-tracing-interceptor`
  )
  // Endpoint Schema derivation lives in tapir-endpoints; this module only adds the
  // ZIO HTTP server interpreter (zio-json is only for any inbound webhook DTO parsing).
  override def mvnDeps = zioDeps ++ tapirServerDeps

  object test extends ScalaTests {
    def testFramework = "zio.test.sbt.ZTestFramework"
    override def mvnDeps = super.mvnDeps() ++ zioTestDeps ++ tapirClientDeps ++ Seq(
      mvn"com.softwaremill.sttp.tapir::tapir-sttp-stub-server:1.11.9"
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
import ubc.<service>.api.endpoints.<Feature>Endpoints.*

object <Feature>HttpControllerSpec extends ZIOSpecDefault:
  override def spec = suite("<Feature>HttpControllerSpec")(
    test("POST /path/to/resource returns 200 with expected response") {
      for
        svc <- ZIO.service[<Feature>Service]
        backend = TapirStubInterpreter(SttpBackendStub(new RIOMonadAsyncError[Any]))
          .whenServerEndpointRunLogic(
            // doThingEndpoint is the shared shape; prefer wiring the controller's own
            // published `*Logic` factory so the test exercises real server logic.
            doThingEndpoint.zServerLogic[Any] { input =>
              svc.doThing(input).mapError(toApiError)
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

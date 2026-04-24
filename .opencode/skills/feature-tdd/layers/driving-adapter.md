# Driving Adapter Layer — Sub-Agent Instructions

You are implementing the driving (inbound) adapter — the HTTP controller that receives external requests and delegates to the core service. No business logic lives here.

## What to build

Location: `<service>/api/internal-api-adapters/http/src/ubc/<service>/api/internal/http/`

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
- Endpoint shapes must be defined here to match `api/api-defn` — never invent new HTTP contracts
- Depends on `api/api-defn` and `core/core-impl`. Never depends on port traits or adapter modules directly.

## build.mill — add test module to internal-api-adapters/http if not present

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
    override def mvnDeps = super.mvnDeps() ++ zioTestDeps ++ tapirClientDeps
    override def moduleDeps = super.moduleDeps ++ Seq(
      core.adapters.`in-memory-<name>`   // inject in-memory core via this
    )
  }
}
```

## Test infrastructure — Tapir stub interpreter

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
  ).provide(InMemory<Feature>Service.layer)
```

Run: `./mill <service>.api.\`internal-api-adapters\`.http.test`

## TDD cycle (Iron Law — no exceptions)

**RED** — Write one failing test for one endpoint. Run it. Confirm it fails because the route does not exist.

**GREEN** — Implement the minimal route handler. Run the test.
Expected: PASS.

**REFACTOR** — Is there any business logic in the controller? Move it to core. Stay green.

Repeat for each endpoint.

## Naming rules
- Controller name includes the protocol: `<Feature>HttpController` not `<Feature>Controller`
- Module dir: `http` for Tapir/ZIO HTTP controllers

## Report back

When complete:
1. **Endpoints implemented** — HTTP method, path, request type, response type
2. **Tests written** — one line per test: what it verifies
3. **Deviations from plan** — anything that changed
4. **Server wiring needed** — which modules `server` must add to `moduleDeps` to wire this feature end-to-end

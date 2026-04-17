package ubc.common

import zio.*
import zio.json.*
import zio.http.{Response, Routes, Server as ZServer}
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.telemetry.opentelemetry.tracing.Tracing

/** Shared ZLayer utilities for all ZIO HTTP servers in this project.
  *
  * Eliminates the boilerplate that every server needs:
  *   - starting the ZIO HTTP server after telemetry is initialised
  *   - resolving service-specific Routes into the ambient telemetry environment
  *   - emitting a structured startup log event
  */
object ServerLayers:

  /** Starts the ZIO HTTP server after telemetry services are present in the environment.
    * Emits a ZServerStarting activity log on startup.
    */
  val serverAfterTelemetry: ZLayer[TelemetryEnv, Throwable, ZServer] =
    ZLayer.scoped:
      for
        _ <- ZIO.service[Tracing]
        _ <- ZIO.service[ContextStorage]
        _ <- ZIO.logActivity(ZServerStarting())
        server <- ZServer.default.build
      yield server.get[ZServer]

  /** Lifts Routes[Tracing, Response] into a ZLayer resolving Tracing from TelemetryEnv.
    * Use this when route handlers directly inject Tracing for per-request span creation.
    */
  def resolvedRoutesLayer(app: Routes[Tracing, Response]): URLayer[TelemetryEnv, Routes[Any, Response]] =
    ZLayer.fromZIO:
      for env <- ZIO.environment[TelemetryEnv]
      yield app.provideEnvironment(env)

  case class ZServerStarting() extends InfoLog derives JsonCodec

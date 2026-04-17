package ubc.common

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.`export`.BatchSpanProcessor
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.`export`.PeriodicMetricReader
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.`export`.BatchLogRecordProcessor
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter
import io.opentelemetry.semconv.ServiceAttributes
import zio.*
import zio.config.magnolia.*
import zio.telemetry.opentelemetry.OpenTelemetry as ZOpenTelemetry
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.metrics.Meter
import zio.telemetry.opentelemetry.context.ContextStorage

import java.time.Duration as JDuration

type TelemetryEnv = OpenTelemetry & Tracing & Meter & ContextStorage & Baggage

// Driven by OTLP_ENDPOINT env var (set ConfigProvider.envProvider.snakeCase in server bootstrap).
// Falls back to the in-cluster collector address when the env var is absent.
private final case class OtelConfig(otlpEndpoint: String) derives Config

object BaseTelemetry:

  private def createResource(serviceName: String): Resource =
    Resource.getDefault.toBuilder
      .put(ServiceAttributes.SERVICE_NAME, serviceName)
      .build()

  private def traceProvider(endpoint: String, serviceName: String): RIO[Scope, SdkTracerProvider] =
    for
      exporter   <- ZIO.fromAutoCloseable(ZIO.succeed(OtlpGrpcSpanExporter.builder().setEndpoint(endpoint).build()))
      processor  <- ZIO.fromAutoCloseable(ZIO.succeed(BatchSpanProcessor.builder(exporter).build()))
      provider   <- ZIO.fromAutoCloseable(
                      ZIO.succeed(
                        SdkTracerProvider.builder()
                          .setResource(createResource(serviceName))
                          .addSpanProcessor(processor)
                          .build()
                      )
                    )
    yield provider

  private def metricProvider(endpoint: String, serviceName: String): RIO[Scope, SdkMeterProvider] =
    for
      exporter  <- ZIO.fromAutoCloseable(ZIO.succeed(OtlpGrpcMetricExporter.builder().setEndpoint(endpoint).build()))
      reader    <- ZIO.fromAutoCloseable(
                     ZIO.succeed(
                       PeriodicMetricReader.builder(exporter).setInterval(JDuration.ofSeconds(30)).build()
                     )
                   )
      provider  <- ZIO.fromAutoCloseable(
                     ZIO.succeed(
                       SdkMeterProvider.builder()
                         .setResource(createResource(serviceName))
                         .registerMetricReader(reader)
                         .build()
                     )
                   )
    yield provider

  private def logProvider(endpoint: String, serviceName: String): RIO[Scope, SdkLoggerProvider] =
    for
      exporter   <- ZIO.fromAutoCloseable(ZIO.succeed(OtlpGrpcLogRecordExporter.builder().setEndpoint(endpoint).build()))
      processor  <- ZIO.fromAutoCloseable(ZIO.succeed(BatchLogRecordProcessor.builder(exporter).build()))
      provider   <- ZIO.fromAutoCloseable(
                      ZIO.succeed(
                        SdkLoggerProvider.builder()
                          .setResource(createResource(serviceName))
                          .addLogRecordProcessor(processor)
                          .build()
                      )
                    )
    yield provider

  // ZLayer.scoped self-manages the Scope for all AutoCloseable OTel SDK objects, so
  // sdkLayer is TaskLayer[OpenTelemetry] — callers never need to provide Scope.
  private def sdkLayer(serviceName: String): TaskLayer[OpenTelemetry] =
    ZLayer.scoped:
      for
        cfg    <- ZIO.config[OtelConfig].orElseSucceed(OtelConfig("http://otel-collector:4317"))
        tracer <- traceProvider(cfg.otlpEndpoint, serviceName)
        meter  <- metricProvider(cfg.otlpEndpoint, serviceName)
        logger <- logProvider(cfg.otlpEndpoint, serviceName)
        sdk    <- ZIO.fromAutoCloseable(
                    ZIO.succeed(
                      OpenTelemetrySdk.builder()
                        .setTracerProvider(tracer)
                        .setMeterProvider(meter)
                        .setLoggerProvider(logger)
                        .build()
                    )
                  )
      yield sdk

  def live(serviceName: String): TaskLayer[TelemetryEnv] =
    ZLayer.make[TelemetryEnv](
      sdkLayer(serviceName),
      ZOpenTelemetry.contextZIO,
      ZOpenTelemetry.baggage(),
      ZOpenTelemetry.tracing(serviceName),
      ZOpenTelemetry.metrics(serviceName),
      ZOpenTelemetry.logging(serviceName),
      ZOpenTelemetry.zioMetrics
    )

package ubc.common

import neotype.*
import neotype.interop.ziojson.given
import ubc.common.sensitive.Sensitive
import zio.*
import zio.json.*
import zio.test.*

object ActivityLogSpec extends ZIOSpecDefault:

  // Fixture types — defined locally to the spec, not exported. The wrapper-based
  // design carries redaction at the field-declaration site, so no `<: Sensitive`
  // marker is needed; ANY inner type works inside `Sensitive[_]`.
  final case class FakeSecret(value: String)
  object FakeSecret:
    given JsonEncoder[FakeSecret] = JsonEncoder[String].contramap(_.value)

  final case class FlatEvent(name: String, token: Sensitive[FakeSecret])
      extends InfoLog derives JsonCodec

  final case class NoSecretEvent(name: String, count: Int)
      extends InfoLog derives JsonCodec

  final case class OptSecretEvent(name: String, token: Option[Sensitive[FakeSecret]])
      extends InfoLog derives JsonCodec

  final case class OptStringEvent(name: String, note: Option[String])
      extends InfoLog derives JsonCodec

  // Wrapper around a plain neotype. Unlike the marker-trait approach, no
  // intersection-typing or smart-constructor cast is needed — `Sensitive[A]`
  // is the explicit opt-in, and `A` can be any opaque/newtype/primitive type.
  // The redaction encoder wins because the field's declared type is
  // `Sensitive[FakeNeoSecret]`, not `FakeNeoSecret` — zio-json never asks for
  // the inner encoder.
  object FakeNeoSecret extends Newtype[String]
  type FakeNeoSecret = FakeNeoSecret.Type

  // A plain (non-wrapped) neotype on the same event. Confirms the inner
  // encoder DOES fire when the field is NOT wrapped in `Sensitive[_]`.
  object FakeNeoId extends Newtype[String]
  type FakeNeoId = FakeNeoId.Type

  final case class NeoEvent(name: String, id: FakeNeoId, token: Sensitive[FakeNeoSecret])
      extends InfoLog derives JsonCodec

  // Captures emitted log records (level + rendered message) for the
  // level-routing test. Built once per test via a ZLayer.
  private final case class Captured(level: LogLevel, message: String)
  private val captureLogger: ZLayer[Any, Nothing, Ref[Chunk[Captured]]] =
    ZLayer.scoped {
      for
        ref <- Ref.make(Chunk.empty[Captured])
        logger = new ZLogger[String, Unit]:
                   def apply(
                       trace:    Trace,
                       fiberId:  FiberId,
                       logLevel: LogLevel,
                       message:  () => String,
                       cause:    Cause[Any],
                       context:  FiberRefs,
                       spans:    List[LogSpan],
                       annotations: Map[String, String]
                   ): Unit =
                     val unsafe = Unsafe.unsafe { implicit u =>
                       Runtime.default.unsafe.run(ref.update(_ :+ Captured(logLevel, message())))
                     }
                     val _ = unsafe
        _ <- ZIO.withLoggerScoped(logger)
      yield ref
    }

  override def spec =
    suite("ActivityLog")(
      test("redacts a field wrapped in Sensitive[_]") {
        val json = toActivityJson(FlatEvent("hi", Sensitive(FakeSecret("hunter2"))))
        assertTrue(
          json.contains("\"token\":\"[**Redacted**]\""),
          !json.contains("hunter2")
        )
      },
      test("non-sensitive fields are encoded as usual") {
        val json = toActivityJson(FlatEvent("hi", Sensitive(FakeSecret("hunter2"))))
        assertTrue(json.contains("\"name\":\"hi\""))
      },
      test("includes the `_ActivityLog` discriminator with the productPrefix") {
        val json = toActivityJson(FlatEvent("hi", Sensitive(FakeSecret("hunter2"))))
        assertTrue(json.contains("\"_ActivityLog\":\"FlatEvent\""))
      },
      test("event without sensitive fields encodes normally") {
        val json = toActivityJson(NoSecretEvent("hi", 3))
        assertTrue(
          json.contains("\"name\":\"hi\""),
          json.contains("\"count\":3"),
          json.contains("\"_ActivityLog\":\"NoSecretEvent\"")
        )
      },
      test("Option fields are omitted when None (zio-json default)") {
        val absent  = toActivityJson(OptStringEvent("hi", None))
        val present = toActivityJson(OptStringEvent("hi", Some("ok")))
        assertTrue(
          !absent.contains("\"note\""),
          present.contains("\"note\":\"ok\"")
        )
      },
      test("Sensitive[neotype] redacts; non-wrapped neotype on the same event passes through") {
        val json = toActivityJson(NeoEvent("hi", FakeNeoId("order-42"), Sensitive(FakeNeoSecret("hunter2"))))
        assertTrue(
          json.contains("\"token\":\"[**Redacted**]\""),
          !json.contains("hunter2"),
          json.contains("\"id\":\"order-42\""),
          json.contains("\"name\":\"hi\"")
        )
      },
      test("Option[Sensitive[_]] = Some redacts the value") {
        val json = toActivityJson(OptSecretEvent("hi", Some(Sensitive(FakeSecret("hunter2")))))
        assertTrue(
          json.contains("\"token\":\"[**Redacted**]\""),
          !json.contains("hunter2")
        )
      },
      test("Option[Sensitive[_]] = None is omitted from the JSON object") {
        val json = toActivityJson(OptSecretEvent("hi", None))
        assertTrue(
          !json.contains("\"token\""),
          json.contains("\"name\":\"hi\"")
        )
      },
      test("ZIO.logActivity routes to LogLevel.Info for InfoLog events") {
        for
          ref      <- ZIO.service[Ref[Chunk[Captured]]]
          _        <- ZIO.logActivity(FlatEvent("hi", Sensitive(FakeSecret("hunter2"))))
          captured <- ref.get
        yield assertTrue(
          captured.size == 1,
          captured.head.level == LogLevel.Info,
          captured.head.message.contains("\"token\":\"[**Redacted**]\""),
          captured.head.message.contains("\"_ActivityLog\":\"FlatEvent\"")
        )
      }.provideLayer(captureLogger)
    )

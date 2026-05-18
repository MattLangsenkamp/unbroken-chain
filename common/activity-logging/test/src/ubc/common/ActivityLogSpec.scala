package ubc.common

import neotype.*
import neotype.interop.ziojson.given
import ubc.common.sensitive.Sensitive
import zio.*
import zio.json.*
import zio.test.*

object ActivityLogSpec extends ZIOSpecDefault:

  // Fixture types — defined locally to the spec, not exported. A non-neotype
  // wrapper is sufficient: the encoder's `<:< Sensitive` check is what we're
  // exercising, not neotype-interop.
  final case class FakeSecret(value: String) extends Sensitive
  object FakeSecret:
    given JsonEncoder[FakeSecret] = JsonEncoder[String].contramap(_.value)

  final case class FlatEvent(name: String, token: FakeSecret) extends InfoLog derives ActivityEncoder

  final case class NoSecretEvent(name: String, count: Int) extends InfoLog derives ActivityEncoder

  final case class OptSecretEvent(name: String, token: Option[FakeSecret]) extends InfoLog derives ActivityEncoder

  final case class OptStringEvent(name: String, note: Option[String]) extends InfoLog derives ActivityEncoder

  // Mirrors the production pattern for opting a neotype-backed newtype into
  // Sensitive: intersection-type alias plus a smart-constructor `make` inside
  // the companion that uses `Sensitive.tag` to widen the opaque value.
  // neotype-zio-json supplies the underlying `JsonEncoder`, but the
  // `<:< Sensitive` check wins over it in `summonFrom`.
  object FakeNeoSecret extends Newtype[String]:
    def sensitive(s: String): FakeNeoSecret = Sensitive.tag(FakeNeoSecret(s))
  type FakeNeoSecret = FakeNeoSecret.Type & Sensitive

  // A plain neotype (no Sensitive). Confirms the non-redacting branch still
  // resolves the neotype-zio-json `JsonEncoder` and emits the underlying value.
  object FakeNeoId extends Newtype[String]
  type FakeNeoId = FakeNeoId.Type

  final case class NeoEvent(name: String, id: FakeNeoId, token: FakeNeoSecret) extends InfoLog derives ActivityEncoder

  // Captures emitted log records (level + rendered message) for the
  // level-routing test. Built once per test via a ZLayer.
  private final case class Captured(level: LogLevel, message: String)
  private val captureLogger: ZLayer[Any, Nothing, Ref[Chunk[Captured]]] =
    ZLayer.scoped {
      for
        ref <- Ref.make(Chunk.empty[Captured])
        logger = new ZLogger[String, Unit]:
          def apply(
              trace: Trace,
              fiberId: FiberId,
              logLevel: LogLevel,
              message: () => String,
              cause: Cause[Any],
              context: FiberRefs,
              spans: List[LogSpan],
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
      test("redacts a field whose declared type <: Sensitive") {
        val json = toActivityJson(FlatEvent("hi", FakeSecret("hunter2")))
        assertTrue(
          json.contains("\"token\":\"[**Redacted**]\""),
          !json.contains("hunter2")
        )
      },
      test("non-sensitive fields are encoded as usual") {
        val json = toActivityJson(FlatEvent("hi", FakeSecret("hunter2")))
        assertTrue(json.contains("\"name\":\"hi\""))
      },
      test("includes the `_ActivityLog` discriminator with the productPrefix") {
        val json = toActivityJson(FlatEvent("hi", FakeSecret("hunter2")))
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
      test("Option fields are omitted when None (matches old derives JsonCodec shape)") {
        val absent  = toActivityJson(OptStringEvent("hi", None))
        val present = toActivityJson(OptStringEvent("hi", Some("ok")))
        assertTrue(
          !absent.contains("\"note\""),
          present.contains("\"note\":\"ok\"")
        )
      },
      test("redacts a Sensitive neotype field while a non-Sensitive neotype passes through") {
        val json = toActivityJson(NeoEvent("hi", FakeNeoId("order-42"), FakeNeoSecret.sensitive("hunter2")))
        assertTrue(
          json.contains("\"token\":\"[**Redacted**]\""),
          !json.contains("hunter2"),
          json.contains("\"id\":\"order-42\""),
          json.contains("\"name\":\"hi\"")
        )
      },
      test("Option[Sensitive] = Some redacts the value") {
        val json = toActivityJson(OptSecretEvent("hi", Some(FakeSecret("hunter2"))))
        assertTrue(
          json.contains("\"token\":\"[**Redacted**]\""),
          !json.contains("hunter2")
        )
      },
      test("Option[Sensitive] = None is omitted from the JSON object") {
        val json = toActivityJson(OptSecretEvent("hi", None))
        assertTrue(
          !json.contains("\"token\""),
          json.contains("\"name\":\"hi\"")
        )
      },
      test("ZIO.logActivity routes to LogLevel.Info for InfoLog events") {
        for
          ref      <- ZIO.service[Ref[Chunk[Captured]]]
          _        <- ZIO.logActivity(FlatEvent("hi", FakeSecret("hunter2")))
          captured <- ref.get
        yield assertTrue(
          captured.size == 1,
          captured.head.level == LogLevel.Info,
          captured.head.message.contains("\"token\":\"[**Redacted**]\""),
          captured.head.message.contains("\"_ActivityLog\":\"FlatEvent\"")
        )
      }.provideLayer(captureLogger)
    )

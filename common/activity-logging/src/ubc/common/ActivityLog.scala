package ubc.common

import scala.compiletime.{constValue, erasedValue, summonFrom, summonInline}
import scala.deriving.Mirror

import zio.*
import zio.json.*
import zio.json.ast.Json

import ubc.common.sensitive.Sensitive

// Logs are data, not strings. Instead of ZIO.logInfo("Health endpoint hit"),
// callers model log events as typed case classes. This makes logs structured,
// searchable, and impossible to misformat at the call site.
//
// Usage:
//   case class HealthCheckHit(path: String) extends InfoLog derives ActivityEncoder
//   ZIO.logActivity(HealthCheckHit("/health"))
//
// Fields whose declared type <: Sensitive (e.g. tokens/secrets) are emitted as
// "[**Redacted**]" instead of their value, as are Option/Seq/Either-Right
// wrappers of Sensitive. The check fires only for top-level fields — nested
// products and Map values use their normal JsonEncoder and will NOT redact.
trait ActivityLog extends Product:
  def logLevel: LogLevel

trait TraceLog extends ActivityLog { override val logLevel: LogLevel = LogLevel.Trace   }
trait DebugLog extends ActivityLog { override val logLevel: LogLevel = LogLevel.Debug   }
trait InfoLog  extends ActivityLog { override val logLevel: LogLevel = LogLevel.Info    }
trait WarnLog  extends ActivityLog { override val logLevel: LogLevel = LogLevel.Warning }
trait ErrorLog extends ActivityLog { override val logLevel: LogLevel = LogLevel.Error   }
trait FatalLog extends ActivityLog { override val logLevel: LogLevel = LogLevel.Fatal   }

// Typeclass derived once per event type at its definition site, where the
// per-field `JsonEncoder` instances are already in scope (via the same imports
// that previously supported `derives JsonCodec`). Call sites of `ZIO.logActivity`
// then only need the derived `ActivityEncoder[A]` — no extra encoder imports.
trait ActivityEncoder[A]:
  def toJsonAst(a: A): Json

object ActivityEncoder:
  private val Redacted: Json = Json.Str("[**Redacted**]")

  // Standard library containers are covariant in the relevant position, so e.g.
  // `Option[X] <:< Option[Sensitive]` whenever `X <:< Sensitive`. Each container
  // case redacts the Sensitive element(s) while preserving the surrounding shape.
  // Map and nested products are NOT covered — make the whole value Sensitive if
  // it needs redaction.
  private inline def fieldJson[T](t: T): Json =
    summonFrom {
      case _: (T <:< Sensitive) => Redacted
      case _: (T <:< Option[Sensitive]) =>
        // Some(_) → redacted; None → Null (omitted by the null-stripping step
        // in encodeFields, matching the old derives JsonCodec shape).
        t.asInstanceOf[Option[?]] match
          case Some(_) => Redacted
          case None    => Json.Null
      case _: (T <:< Seq[Sensitive]) =>
        // Redact each element; the element count (array length) is preserved.
        Json.Arr(t.asInstanceOf[Seq[Any]].map(_ => Redacted)*)
      case _: (T <:< Either[l, Sensitive]) =>
        // Only the Right channel is Sensitive. Preserve zio-json's
        // {"Left":..}/{"Right":..} shape: Left encodes normally via its own
        // JsonEncoder (summoned at the derivation site); Right is redacted.
        t.asInstanceOf[Either[l, Any]] match
          case Left(l)  => Json.Obj("Left" -> summonInline[JsonEncoder[l]].toJsonAST(l).getOrElse(Json.Null))
          case Right(_) => Json.Obj("Right" -> Redacted)
      case enc: JsonEncoder[T]  => enc.toJsonAST(t).getOrElse(Json.Null)
    }

  private inline def encodeFields[Labels <: Tuple, Types <: Tuple](
      product: Product,
      index: Int,
      acc: Chunk[(String, Json)]
  ): Chunk[(String, Json)] =
    inline erasedValue[Labels] match
      case _: EmptyTuple => acc
      case _: (label *: labelTail) =>
        inline erasedValue[Types] match
          case _: (t *: typeTail) =>
            val name  = constValue[label & String]
            val value = product.productElement(index).asInstanceOf[t]
            val json  = fieldJson[t](value)
            // Match zio-json's derived behavior: omit fields whose encoded
            // value is JSON null (typically `Option[_] = None`). Keeps wire
            // shape unchanged for consumers that previously relied on absent
            // fields.
            val next  = if json == Json.Null then acc else acc :+ (name -> json)
            encodeFields[labelTail, typeTail](product, index + 1, next)

  inline def derived[A <: ActivityLog](using m: Mirror.ProductOf[A]): ActivityEncoder[A] =
    (a: A) =>
      val fields = encodeFields[m.MirroredElemLabels, m.MirroredElemTypes](a, 0, Chunk.empty)
      Json.Obj(fields :+ ("_ActivityLog" -> Json.Str(a.productPrefix)))

def toActivityJson[A <: ActivityLog](a: A)(using enc: ActivityEncoder[A]): String =
  enc.toJsonAst(a).toJson

extension (zio: ZIO.type)
  def logActivity[A <: ActivityLog](a: A)(using ActivityEncoder[A]): UIO[Unit] =
    val json = toActivityJson(a)
    a.logLevel match
      case LogLevel.Trace   => ZIO.logTrace(json)
      case LogLevel.Debug   => ZIO.logDebug(json)
      case LogLevel.Info    => ZIO.logInfo(json)
      case LogLevel.Warning => ZIO.logWarning(json)
      case LogLevel.Error   => ZIO.logError(json)
      case LogLevel.Fatal   => ZIO.logFatal(json)
      case _                => ZIO.logInfo(json)

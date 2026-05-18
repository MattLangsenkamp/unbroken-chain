package ubc.common

import zio.*
import zio.json.*
import zio.json.ast.Json

// Logs are data, not strings. Instead of ZIO.logInfo("Health endpoint hit"),
// callers model log events as typed case classes with a JsonCodec. This makes
// logs structured, searchable, and impossible to misformat at the call site.
//
// Usage:
//   case class HealthCheckHit(path: String) extends InfoLog derives JsonCodec
//   ZIO.logActivity(HealthCheckHit("/health"))
//
// Secret-bearing fields opt in to redaction by wrapping the value type at the
// declaration site:
//   case class TokenMinted(id: GhInstallationId, token: Sensitive[AppJwt])
//       extends InfoLog derives JsonCodec
//   ZIO.logActivity(TokenMinted(id, Sensitive(token)))
// The wrapper carries a `JsonEncoder[Sensitive[A]]` that always emits
// "[**Redacted**]", so the inner value never reaches the JSON payload.
trait ActivityLog extends Product:
  def logLevel: LogLevel

trait TraceLog extends ActivityLog { override val logLevel: LogLevel = LogLevel.Trace }
trait DebugLog extends ActivityLog { override val logLevel: LogLevel = LogLevel.Debug }
trait InfoLog  extends ActivityLog { override val logLevel: LogLevel = LogLevel.Info }
trait WarnLog  extends ActivityLog { override val logLevel: LogLevel = LogLevel.Warning }
trait ErrorLog extends ActivityLog { override val logLevel: LogLevel = LogLevel.Error }
trait FatalLog extends ActivityLog { override val logLevel: LogLevel = LogLevel.Fatal }

def toActivityJson[A <: ActivityLog: JsonCodec](a: A): String =
  a.toJsonAST match
    case Left(_)      => a.toJson
    case Right(value) =>
      value
        .merge(Json.apply(("_ActivityLog", Json.Str(a.productPrefix))))
        .toJson

extension (zio: ZIO.type)
  def logActivity[A <: ActivityLog: JsonCodec](a: A): UIO[Unit] =
    val json = toActivityJson(a)
    a.logLevel match
      case LogLevel.Trace   => ZIO.logTrace(json)
      case LogLevel.Debug   => ZIO.logDebug(json)
      case LogLevel.Info    => ZIO.logInfo(json)
      case LogLevel.Warning => ZIO.logWarning(json)
      case LogLevel.Error   => ZIO.logError(json)
      case LogLevel.Fatal   => ZIO.logFatal(json)
      case _                => ZIO.logInfo(json)

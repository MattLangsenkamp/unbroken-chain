package ubc.common.sensitive

/** Marker trait for values whose contents must never appear in logs, traces, or other
  * observability output (e.g. API tokens, refresh tokens, encryption keys).
  *
  * The trait itself has no runtime behaviour — it exists purely as a type-level signal.
  * Downstream codecs, loggers, and `toString` implementations are expected to honour the
  * marker by redacting the value.
  */
trait Sensitive

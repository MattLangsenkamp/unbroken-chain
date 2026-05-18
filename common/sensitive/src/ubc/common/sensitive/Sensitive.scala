package ubc.common.sensitive

/** Marker trait for values whose contents must never appear in logs, traces, or other
  * observability output (e.g. API tokens, refresh tokens, encryption keys).
  *
  * The trait itself has no runtime behaviour — it exists purely as a type-level signal.
  * Downstream codecs, loggers, and `toString` implementations are expected to honour the
  * marker by redacting the value.
  */
trait Sensitive

object Sensitive:
  /** Type-tags an opaque or otherwise non-Sensitive value as `Sensitive` for the purposes
    * of the `ActivityEncoder` redaction check. Use in smart constructors for
    * `neotype.Newtype`-backed sensitive types whose inner opaque `Type` cannot extend
    * `Sensitive` directly. Example:
    *
    * {{{
    * object AppJwt extends Newtype[String]
    * type AppJwt = AppJwt.Type & Sensitive
    * def appJwt(s: String): AppJwt = Sensitive.tag(AppJwt(s))
    * }}}
    *
    * The cast is type-system-only — the encoder's `<:< Sensitive` check is compile-time,
    * so no `Sensitive` interface needs to exist at runtime. The runtime bytes are unchanged.
    *
    * For plain case classes, prefer `extends Sensitive` directly over calling `tag` —
    * the marker is then nominal and reviewers see the relationship at the type definition.
    * `tag` exists only because Scala's opaque types and neotype's macros prevent that
    * approach from working through `Newtype.apply`.
    */
  inline def tag[A](a: A): A & Sensitive = a.asInstanceOf[A & Sensitive]

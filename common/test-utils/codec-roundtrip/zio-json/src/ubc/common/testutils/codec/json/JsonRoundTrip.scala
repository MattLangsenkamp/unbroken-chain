package ubc.common.testutils.codec.json

import zio.json.{DecoderOps, EncoderOps, JsonCodec}
import zio.test.{Gen, Spec, TestResult, assertTrue, check, test}

/** Round-trip helpers for zio-json `JsonCodec`s.
  *
  * Pass a generator and the codec resolves implicitly; the helper encodes each sampled value to JSON, decodes it back,
  * and asserts the result equals the input. Default equality is `==`; pass a custom `(T, T) => Boolean` for codecs that
  * lose information structurally equivalent to the original (e.g. `BigDecimal` scale, `Instant` precision).
  *
  * Test-classpath only: this module pulls `zio-test` onto its main classpath, so add it to `test.moduleDeps` of
  * consuming modules — never to production `moduleDeps`.
  */
object JsonRoundTrip:

  /** Assert that a single value JSON-round-trips structurally. */
  def value[T](v: T)(using JsonCodec[T]): TestResult =
    val encoded = v.toJson
    val decoded = encoded.fromJson[T]
    assertTrue(decoded == Right(v))

  /** Assert that a single value JSON-round-trips under a caller-supplied equality.
    *
    * `equals` is invoked as `equals(decoded, original)` — the decoded value first, the value that was fed into the
    * codec second. Matters only for asymmetric equalities (symmetric ones like `equalsIgnoreCase` don't care).
    */
  def value[T](v: T, equals: (T, T) => Boolean)(using JsonCodec[T]): TestResult =
    val encoded = v.toJson
    val decoded = encoded.fromJson[T]
    assertTrue(decoded.toOption.exists(equals(_, v)))

  /** Property-based round-trip spec — drop straight into a `suite(...)`. */
  def spec[T](name: String, gen: Gen[Any, T])(using JsonCodec[T]): Spec[Any, Nothing] =
    test(s"$name round-trips through JSON")(check(gen)(value[T](_)))

  /** Property-based round-trip spec with caller-supplied equality. */
  def spec[T](
      name: String,
      gen: Gen[Any, T],
      equals: (T, T) => Boolean
  )(using JsonCodec[T]): Spec[Any, Nothing] =
    test(s"$name round-trips through JSON")(check(gen)(value(_, equals)))

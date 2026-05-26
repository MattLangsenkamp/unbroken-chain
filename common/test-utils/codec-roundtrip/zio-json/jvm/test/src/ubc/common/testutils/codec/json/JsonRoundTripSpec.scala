package ubc.common.testutils.codec.json

import zio.json.{DeriveJsonCodec, JsonCodec, JsonDecoder, JsonEncoder}
import zio.test.{Gen, ZIOSpecDefault, assertCompletes, assertTrue, check}

object JsonRoundTripSpec extends ZIOSpecDefault:

  private final case class Sample(id: Int, label: String)
  private given JsonCodec[Sample] = DeriveJsonCodec.gen

  // Lossy codec: drops the case of the string on encode. Round-tripping with `==` fails for
  // values that aren't already lowercase; with a caller-supplied case-insensitive equality, it passes.
  private final case class CaseInsensitive(s: String)
  private given JsonCodec[CaseInsensitive] = JsonCodec(
    JsonEncoder[String].contramap[CaseInsensitive](_.s.toLowerCase),
    JsonDecoder[String].map(CaseInsensitive(_))
  )

  override def spec = suite("JsonRoundTripSpec")(
    test("value succeeds for an Int.toJson + fromJson[Int]") {
      JsonRoundTrip.value(42)
    },
    test("value succeeds for a derived case class") {
      JsonRoundTrip.value(Sample(7, "hello"))
    },
    test("value with custom equals succeeds for a lossy codec when equality matches") {
      val eq: (CaseInsensitive, CaseInsensitive) => Boolean =
        (a, b) => a.s.equalsIgnoreCase(b.s)
      JsonRoundTrip.value(CaseInsensitive("Hello"), eq)
    },
    test("value with default equals fails for a lossy codec") {
      val result = JsonRoundTrip.value(CaseInsensitive("Hello"))
      assertTrue(!result.isSuccess)
    },
    JsonRoundTrip.spec("Sample (gen)", Gen.int.zip(Gen.alphaNumericString).map(Sample.apply)),
    JsonRoundTrip
      .spec[CaseInsensitive](
        "CaseInsensitive (gen, custom eq)",
        Gen.alphaNumericString.map(CaseInsensitive(_)),
        (a, b) => a.s.equalsIgnoreCase(b.s)
      ),
    test("zio-test's check is exercised: gen samples are produced") {
      check(Gen.int)(_ => assertCompletes)
    }
  )

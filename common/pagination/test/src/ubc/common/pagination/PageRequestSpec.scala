package ubc.common.pagination

import zio.test.*

object PageRequestSpec extends ZIOSpecDefault:

  override def spec =
    suite("PageRequestSpec")(
      test("constructs a request with an explicit cursor and limit") {
        val req = PageRequest(cursor = Some("c1"), limit = 25)
        assertTrue(req.cursor.contains("c1"), req.limit == 25)
      },
      test("constructs a request without a cursor") {
        val req = PageRequest(cursor = None, limit = 10)
        assertTrue(req.cursor.isEmpty, req.limit == 10)
      },
      test("PageRequest.default uses the supplied limit and no cursor") {
        val req = PageRequest.default(50)
        assertTrue(req.cursor.isEmpty, req.limit == 50)
      },
      test("PageRequest.defaultLimit is 50") {
        assertTrue(PageRequest.defaultLimit == 50)
      }
    )

package ubc.common.pagination

import zio.test.*

object PaginationGeneratorsSpec extends ZIOSpecDefault:

  override def spec =
    suite("PaginationGeneratorsSpec")(
      test("pageRequest samples constructible PageRequest values without throwing") {
        check(PaginationGenerators.pageRequest)(_ => assertCompletes)
      },
      test("page samples Page values carrying the element type without throwing") {
        check(PaginationGenerators.page[Int])(_ => assertCompletes)
      }
    )

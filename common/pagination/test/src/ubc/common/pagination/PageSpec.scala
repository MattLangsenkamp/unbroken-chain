package ubc.common.pagination

import zio.test.*

object PageSpec extends ZIOSpecDefault:

  override def spec =
    suite("PageSpec")(
      test("constructs a non-empty page with items, total, and a next cursor") {
        val page = Page[String](
          items      = List("a", "b", "c"),
          total      = 42L,
          nextCursor = Some("opaque-cursor")
        )
        assertTrue(
          page.items == List("a", "b", "c"),
          page.total == 42L,
          page.nextCursor.contains("opaque-cursor")
        )
      },
      test("constructs an empty page with zero total and no cursor") {
        val page = Page[Int](items = Nil, total = 0L, nextCursor = None)
        assertTrue(
          page.items.isEmpty,
          page.total == 0L,
          page.nextCursor.isEmpty
        )
      },
      test("Page is generic over the item type") {
        case class Item(id: Long)
        val page = Page[Item](
          items      = List(Item(1L), Item(2L)),
          total      = 2L,
          nextCursor = None
        )
        assertTrue(page.items.size == 2, page.total == 2L)
      }
    )

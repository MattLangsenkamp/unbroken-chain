package ubc.common.pagination

/** A single page of results.
  *
  * @param items
  *   the items in this page
  * @param total
  *   the total number of items across all pages
  * @param nextCursor
  *   opaque cursor for fetching the next page; None when there are no more results
  */
final case class Page[A](
    items: List[A],
    total: Long,
    nextCursor: Option[String]
)

package ubc.common.pagination

/** A request for a page of results.
  *
  * @param cursor
  *   opaque cursor returned by a previous [[Page.nextCursor]]; None for the first page
  * @param limit
  *   maximum number of items to return
  */
final case class PageRequest(
    cursor: Option[String],
    limit: Int
)

object PageRequest:
  /** Default page size used when callers do not specify one. */
  val defaultLimit: Int = 50

  /** Construct a first-page request with the given limit and no cursor. */
  def default(limit: Int): PageRequest =
    PageRequest(cursor = None, limit = limit)

package ubc.common.magnum

import java.util.Base64

/** Opaque-cursor encoding for paginated reads in Magnum-backed repositories.
  *
  * Cursors are base64-encoded decimal offsets — the simplest representation that lets us round-trip the OFFSET we'll
  * use on the next page. Returning `None` when the next offset would be at or past the total signals "no more pages" to
  * the caller.
  */
object MagnumPagination:

  /** Decode an opaque cursor to an OFFSET. Treats `None` as offset 0 (first page). */
  def cursorToOffset(cursor: Option[String]): Long =
    cursor.fold(0L) { c =>
      val decoded = new String(Base64.getDecoder.decode(c))
      java.lang.Long.parseLong(decoded)
    }

  /** Compute the cursor for the next page, or `None` if no more rows remain.
    *
    * @param currentOffset
    *   OFFSET that produced the current page
    * @param returned
    *   number of rows actually returned in the current page
    * @param total
    *   total row count across all pages
    */
  def nextCursor(currentOffset: Long, returned: Int, total: Long): Option[String] =
    val newOffset = currentOffset + returned.toLong
    if newOffset >= total then None
    else Some(Base64.getEncoder.encodeToString(newOffset.toString.getBytes))

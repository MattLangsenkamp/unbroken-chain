package ubc.githubgateway.core.adapters.magnum

/** Opaque-cursor encoding for paginated reads. See the matching helper in
  * `magnum-installation-repository` — duplicated per module since each Magnum adapter is
  * a sibling module with no shared submodule.
  */
private[magnum] object MagnumPagination:

  def cursorToOffset(cursor: Option[String]): Long =
    cursor.fold(0L) { c =>
      val decoded = new String(java.util.Base64.getDecoder.decode(c))
      java.lang.Long.parseLong(decoded)
    }

  def nextCursor(currentOffset: Long, returned: Int, total: Long): Option[String] =
    val newOffset = currentOffset + returned.toLong
    if newOffset >= total then None
    else Some(java.util.Base64.getEncoder.encodeToString(newOffset.toString.getBytes))

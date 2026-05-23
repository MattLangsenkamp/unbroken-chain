package ubc.common.pagination

import zio.test.Gen
import zio.test.magnolia.DeriveGen

/** ZIO Test generators for pagination types. Test-classpath only.
  *
  * Case classes derive via `DeriveGen.gen`. `Page[A]` is generic, so it takes the element
  * generator as a `using DeriveGen[A]`.
  */
object PaginationGenerators:

  val pageRequest: Gen[Any, PageRequest] = DeriveGen.gen[PageRequest].derive

  def page[A](using DeriveGen[A]): Gen[Any, Page[A]] = DeriveGen.gen[Page[A]].derive

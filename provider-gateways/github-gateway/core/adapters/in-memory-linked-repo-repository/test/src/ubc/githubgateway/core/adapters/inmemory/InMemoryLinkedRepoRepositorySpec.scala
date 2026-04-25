package ubc.githubgateway.core.adapters.inmemory

import neotype.*
import ubc.common.pagination.PageRequest
import ubc.githubgateway.core.ports.LinkedRepoRepository
import ubc.githubgateway.domain.*
import zio.*
import zio.test.*

object InMemoryLinkedRepoRepositorySpec extends ZIOSpecDefault:

  private val instA = InstallationId(1L)
  private val instB = InstallationId(2L)

  override def spec =
    suite("InMemoryLinkedRepoRepository")(
      test("insert + listByInstallation round-trip") {
        for
          repo <- ZIO.service[LinkedRepoRepository]
          row  <- repo.insert(instA, GhRepositoryId(100L), RepoFullName("o/a"))
          page <- repo.listByInstallation(instA, PageRequest(None, 10))
        yield assertTrue(
          row.installationId == instA,
          row.ghRepositoryId == GhRepositoryId(100L),
          row.fullName == RepoFullName("o/a"),
          page.items.contains(row),
          page.total == 1L
        )
      },
      test("replaceSet from empty inserts the entire desired set") {
        for
          repo <- ZIO.service[LinkedRepoRepository]
          summary <- repo.replaceSet(
                       instA,
                       List(
                         (GhRepositoryId(200L), RepoFullName("o/x")),
                         (GhRepositoryId(201L), RepoFullName("o/y"))
                       )
                     )
          page <- repo.listByInstallation(instA, PageRequest(None, 10))
        yield assertTrue(
          summary == ReconcileSummary(added = 2, removed = 0, renamed = 0),
          page.items.size == 2
        )
      },
      test("replaceSet removes rows missing from the desired set, renames matching ids, leaves equal rows alone") {
        for
          repo <- ZIO.service[LinkedRepoRepository]
          _ <- repo.insert(instA, GhRepositoryId(300L), RepoFullName("o/keep"))
          _ <- repo.insert(instA, GhRepositoryId(301L), RepoFullName("o/old-name"))
          _ <- repo.insert(instA, GhRepositoryId(302L), RepoFullName("o/dropme"))
          summary <- repo.replaceSet(
                       instA,
                       List(
                         (GhRepositoryId(300L), RepoFullName("o/keep")),
                         (GhRepositoryId(301L), RepoFullName("o/new-name")),
                         (GhRepositoryId(303L), RepoFullName("o/added"))
                       )
                     )
          page <- repo.listByInstallation(instA, PageRequest(None, 10))
        yield assertTrue(
          summary == ReconcileSummary(added = 1, removed = 1, renamed = 1),
          page.items.map(_.ghRepositoryId).sortBy(_.unwrap) ==
            List(GhRepositoryId(300L), GhRepositoryId(301L), GhRepositoryId(303L)),
          page.items.find(_.ghRepositoryId == GhRepositoryId(301L)).exists(_.fullName == RepoFullName("o/new-name"))
        )
      },
      test("renameByGhRepositoryId updates fullName for the matching repo") {
        for
          repo <- ZIO.service[LinkedRepoRepository]
          _    <- repo.insert(instA, GhRepositoryId(400L), RepoFullName("o/before"))
          _    <- repo.renameByGhRepositoryId(GhRepositoryId(400L), RepoFullName("o/after"))
          page <- repo.listByInstallation(instA, PageRequest(None, 10))
        yield assertTrue(
          page.items.head.fullName == RepoFullName("o/after")
        )
      },
      test("insertMany adds multiple rows for an installation") {
        for
          repo <- ZIO.service[LinkedRepoRepository]
          _ <- repo.insertMany(
                 instA,
                 List(
                   (GhRepositoryId(500L), RepoFullName("o/m1")),
                   (GhRepositoryId(501L), RepoFullName("o/m2")),
                   (GhRepositoryId(502L), RepoFullName("o/m3"))
                 )
               )
          page <- repo.listByInstallation(instA, PageRequest(None, 10))
        yield assertTrue(page.total == 3L)
      },
      test("deleteByGhRepositoryIds removes only the named ids") {
        for
          repo <- ZIO.service[LinkedRepoRepository]
          _    <- repo.insert(instA, GhRepositoryId(600L), RepoFullName("o/k"))
          _    <- repo.insert(instA, GhRepositoryId(601L), RepoFullName("o/d1"))
          _    <- repo.insert(instA, GhRepositoryId(602L), RepoFullName("o/d2"))
          _    <- repo.deleteByGhRepositoryIds(instA, List(GhRepositoryId(601L), GhRepositoryId(602L)))
          page <- repo.listByInstallation(instA, PageRequest(None, 10))
        yield assertTrue(
          page.items.map(_.ghRepositoryId) == List(GhRepositoryId(600L))
        )
      },
      test("listAll paginates across installations and respects limit") {
        for
          repo <- ZIO.service[LinkedRepoRepository]
          _    <- repo.insert(instA, GhRepositoryId(700L), RepoFullName("o/a"))
          _    <- repo.insert(instB, GhRepositoryId(701L), RepoFullName("p/b"))
          _    <- repo.insert(instB, GhRepositoryId(702L), RepoFullName("p/c"))
          page <- repo.listAll(PageRequest(None, 2))
          full <- repo.listAll(PageRequest(None, 100))
        yield assertTrue(
          page.items.size == 2,
          full.total == 3L,
          full.items.map(_.installationId).toSet == Set(instA, instB)
        )
      }
    ).provide(InMemoryLinkedRepoRepository.layer)

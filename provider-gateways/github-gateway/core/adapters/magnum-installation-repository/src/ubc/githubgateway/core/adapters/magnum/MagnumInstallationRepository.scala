package ubc.githubgateway.core.adapters.magnum

import ubc.common.magnum.MagnumPagination
import ubc.common.pagination.{Page, PageRequest}
import ubc.githubgateway.core.ports.InstallationRepository
import ubc.githubgateway.domain.*
import ubc.githubgateway.domain.adapters.magnum.PublicMagnumCodecs.given
import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.*
import zio.*

import java.time.Instant

// Magnum derives DbCodec[Installation] by walking the case-class fields and looking up
// a DbCodec for each one. The newtype/enum codecs are imported above. SELECT column order
// must match the Installation case class field declaration order.
private given DbCodec[Installation] = DbCodec.derived[Installation]

class MagnumInstallationRepository(xa: TransactorZIO) extends InstallationRepository:

  def upsertByGhInstallationId(
      ghInstallationId: GhInstallationId,
      accountLogin: AccountLogin,
      accountId: AccountId,
      accountType: AccountType,
      status: InstallationStatus,
      installedAt: Instant
  ): Task[Installation] =
    xa.transact {
      sql"""
        INSERT INTO installation (gh_installation_id, account_login, account_id, account_type, status, installed_at)
        VALUES ($ghInstallationId, $accountLogin, $accountId, $accountType, $status, $installedAt)
        ON CONFLICT (gh_installation_id) DO UPDATE SET
          account_login = EXCLUDED.account_login,
          account_id    = EXCLUDED.account_id,
          account_type  = EXCLUDED.account_type,
          status        = EXCLUDED.status,
          installed_at  = EXCLUDED.installed_at
        RETURNING id, gh_installation_id, account_login, account_id, account_type, status, installed_at
      """.returning[Installation].run().head
    }

  def findByGhInstallationId(ghInstallationId: GhInstallationId): Task[Option[Installation]] =
    xa.connect {
      sql"""
        SELECT id, gh_installation_id, account_login, account_id, account_type, status, installed_at
        FROM installation
        WHERE gh_installation_id = $ghInstallationId
      """.query[Installation].run().headOption
    }

  def listAll(page: PageRequest): Task[Page[Installation]] =
    val offset = MagnumPagination.cursorToOffset(page.cursor)
    val limit  = page.limit
    xa.transact {
      val items = sql"""
        SELECT id, gh_installation_id, account_login, account_id, account_type, status, installed_at
        FROM installation
        ORDER BY id ASC
        LIMIT $limit OFFSET $offset
      """.query[Installation].run().toList
      val total = sql"SELECT COUNT(*) FROM installation".query[Long].run().head
      Page(
        items      = items,
        total      = total,
        nextCursor = MagnumPagination.nextCursor(offset, items.size, total)
      )
    }

  def deleteByGhInstallationId(ghInstallationId: GhInstallationId): Task[Boolean] =
    xa.transact {
      sql"DELETE FROM installation WHERE gh_installation_id = $ghInstallationId".update.run()
    }.map(_ > 0)

  def updateStatus(ghInstallationId: GhInstallationId, status: InstallationStatus): Task[Unit] =
    xa.transact {
      sql"UPDATE installation SET status = $status WHERE gh_installation_id = $ghInstallationId".update.run()
    }.unit

object MagnumInstallationRepository:
  val layer: ZLayer[TransactorZIO, Nothing, InstallationRepository] =
    ZLayer.fromFunction(new MagnumInstallationRepository(_))

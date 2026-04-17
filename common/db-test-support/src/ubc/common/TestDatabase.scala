package ubc.common

import com.augustnagro.magnum.DbCon
import com.augustnagro.magnum.magzio.TransactorZIO
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import org.flywaydb.core.Flyway
import org.testcontainers.containers.PostgreSQLContainer
import zio.*

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource
import scala.util.Try

/** Reusable database fixture for repository-layer tests.
 *
 * Typical wiring:
 * {{{
 * override def spec = suite("MyRepoSuite")(...).provideShared(
 *   TestDatabase.suiteLayer("filesystem:provider-gateways/my-service/db"),
 *   // testLayer must be per-test — use provide, not provideShared
 * ).provide(TestDatabase.testLayer)
 * }}}
 */
object TestDatabase:

  /** Starts a PostgreSQL 17 Testcontainers container and runs Flyway migrations once for the suite.
   *
   * @param migrationLocation Flyway location string, e.g. `"filesystem:provider-gateways/my-service/db"`
   *                          or `"classpath:db/migration"`. Passed directly to Flyway.
   */
  def suiteLayer(migrationLocation: String): ZLayer[Any, Throwable, PostgreSQLContainer[?]] =
    ZLayer.scoped {
      for
        container <- ZIO.acquireRelease(
          ZIO.attempt {
            val c = new PostgreSQLContainer("postgres:17")
            c.start()
            c
          }.mapError(e => RuntimeException(s"PostgreSQL container failed to start: ${e.getMessage}", e))
        )(c => ZIO.attempt(c.stop()).orDie)
        _ <- ZIO.attempt(
          Flyway
            .configure()
            .dataSource(container.getJdbcUrl, container.getUsername, container.getPassword)
            .locations(migrationLocation)
            .load()
            .migrate()
        ).mapError(e => RuntimeException(s"Flyway migration failed at '$migrationLocation': ${e.getMessage}", e))
      yield container
    }

  /** Borrows a single connection per test from a HikariCP pool, disables auto-commit, and rolls
   *  back unconditionally on teardown.
   *
   *  Use with ZIO Test's `provide` (not `provideShared`) to get a fresh connection per test:
   *  {{{
   *  suite(...).provideShared(TestDatabase.suiteLayer(...)).provide(TestDatabase.testLayer)
   *  }}}
   *
   *  The fiber bridge pattern is used here because `DbCon`'s constructor is package-private
   *  (`private[magnum]`). The only way to obtain a `DbCon` from outside the magnum package is
   *  via `TransactorZIO.connect`. A dedicated blocking fiber holds the connection open across
   *  the test and rolls back when the ZLayer scope exits.
   */
  val testLayer: ZLayer[PostgreSQLContainer[?], Throwable, DbCon] =
    ZLayer.scoped {
      for
        container <- ZIO.service[PostgreSQLContainer[?]]

        ds <- ZIO.acquireRelease(
          ZIO.attempt {
            val cfg = new HikariConfig()
            cfg.setJdbcUrl(container.getJdbcUrl())
            cfg.setUsername(container.getUsername())
            cfg.setPassword(container.getPassword())
            cfg.setMaximumPoolSize(1)
            new HikariDataSource(cfg)
          }.mapError(e => RuntimeException(s"HikariCP pool creation failed: ${e.getMessage}", e))
        )(ds => ZIO.attempt(ds.close()).orDie)

        xa <- ZIO.service[TransactorZIO]
                .provide(ZLayer.succeed[DataSource](ds), TransactorZIO.layer)

        // Java primitives are used here to communicate across the sync/async boundary.
        // ZIO primitives cannot be signalled from within the synchronous connect block.
        dbConRef   = new AtomicReference[DbCon](null)
        readyLatch = new CountDownLatch(1)
        doneLatch  = new CountDownLatch(1)

        // A daemon fiber holds the connection open for the duration of the test.
        // acquireRelease ensures cleanup (rollback + join) runs even on timeout or error.
        fiber <- ZIO.acquireRelease(
          xa.withConnectionConfig(_.setAutoCommit(false))
            .connect {
              val dbCon = summon[DbCon]
              dbConRef.set(dbCon)
              readyLatch.countDown()                // signal: DbCon is ready
              try doneLatch.await()                 // block until test teardown
              finally Try(dbCon.connection.rollback())  // always rollback
            }
            .tapError(_ =>
              // If connection acquisition fails before the block runs, unblock the waiter below.
              ZIO.attempt { if dbConRef.get() eq null then readyLatch.countDown() }.orDie
            )
            .forkDaemon
        )(fiber => ZIO.attempt(doneLatch.countDown()).orDie *> fiber.join.ignore)

        _ <- ZIO.blocking(
          ZIO.attempt(readyLatch.await(30, TimeUnit.SECONDS))
            .filterOrFail(identity)(
              RuntimeException("Timed out waiting for test database connection (30 seconds)")
            )
        )

        dbCon <- ZIO.attempt(
          Option(dbConRef.get()).getOrElse(
            throw RuntimeException("Test database connection could not be acquired")
          )
        )
      yield dbCon
    }

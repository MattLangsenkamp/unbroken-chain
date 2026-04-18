package ubc.githubgateway.core.adapters.magnum

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.*
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import ubc.common.{PostgresContainer, TestDatabase}
import ubc.githubgateway.core.ports.TokenRepository
import ubc.githubgateway.domain.*
import ubc.githubgateway.domain.internal.*
import ubc.githubgateway.domain.adapters.magnum.PublicMagnumCodecs.given
import ubc.githubgateway.domain.internal.adapters.magnum.PrivateMagnumCodecs.given
import zio.*
import zio.test.*
import javax.sql.DataSource
import java.time.Instant

// Integration tests for MagnumTokenRepository against a real PostgreSQL instance.
//
// Isolation strategy: one Testcontainers container + one HikariCP pool for the
// whole suite; github_tokens is TRUNCATED before every test so each test starts
// with an empty table. Tests run sequentially to avoid inter-test interference.
object MagnumTokenRepositorySpec extends ZIOSpecDefault:

  private val migrationLocation = "filesystem:provider-gateways/github-gateway/db"

  // Suite-scoped HikariCP pool wired from the Testcontainers container.
  // Separate from TestDatabase.testLayer — MagnumTokenRepository manages its own
  // connections via TransactorZIO, so we give it a normal pool rather than the
  // single-connection rollback fixture.
  private val transactorLayer: ZLayer[PostgresContainer, Throwable, TransactorZIO] =
    ZLayer.scoped {
      for
        container <- ZIO.service[PostgresContainer]
        ds        <- ZIO.acquireRelease(
          ZIO.attempt {
            val cfg = new HikariConfig()
            cfg.setJdbcUrl(container.getJdbcUrl)
            cfg.setUsername(container.getUsername)
            cfg.setPassword(container.getPassword)
            new HikariDataSource(cfg)
          }.mapError(e => RuntimeException(s"HikariCP pool creation failed: ${e.getMessage}", e))
        )(ds => ZIO.attempt(ds.close()).orDie)
        xa        <- ZIO.service[TransactorZIO]
                       .provide(ZLayer.succeed[DataSource](ds), TransactorZIO.layer)
      yield xa
    }

  // Wipes github_tokens before each test and resets the BIGSERIAL counter so
  // auto-assigned ids are predictable across tests.
  private val truncateTokens: URIO[TransactorZIO, Unit] =
    ZIO.serviceWithZIO[TransactorZIO](
      _.transact { sql"TRUNCATE TABLE github_tokens RESTART IDENTITY".update.run() }
    ).unit.orDie

  private val token1 = InternalToken(
    id        = TokenId(0L), // DB assigns the real id on INSERT
    userId    = UserId("user-alice"),
    token     = GitHubToken("ghp_alice_original"),
    scopes    = List(TokenScope("repo"), TokenScope("user:email")),
    createdAt = Instant.parse("2025-01-01T00:00:00Z"),
    expiresAt = None
  )

  override def spec =
    (suite("MagnumTokenRepositorySpec")(

      test("save persists a token and findByUserId returns it") {
        for
          repo      <- ZIO.service[TokenRepository]
          _         <- repo.save(token1)
          retrieved <- repo.findByUserId(token1.userId)
        yield assertTrue(
          retrieved.isDefined,
          retrieved.get.userId  == token1.userId,
          retrieved.get.token   == token1.token,
          retrieved.get.scopes  == token1.scopes,
          retrieved.get.expiresAt.isEmpty
        )
      },

      test("save is an upsert — second save for the same user replaces the token") {
        val updated = token1.copy(token = GitHubToken("ghp_alice_updated"))
        for
          repo      <- ZIO.service[TokenRepository]
          _         <- repo.save(token1)
          _         <- repo.save(updated)
          retrieved <- repo.findByUserId(token1.userId)
        yield assertTrue(
          retrieved.isDefined,
          retrieved.get.token == GitHubToken("ghp_alice_updated")
        )
      },

      test("findByUserId returns None for a user with no token") {
        for
          repo   <- ZIO.service[TokenRepository]
          result <- repo.findByUserId(UserId("user-nobody"))
        yield assertTrue(result.isEmpty)
      },

      test("delete removes the token so findByUserId returns None") {
        for
          repo     <- ZIO.service[TokenRepository]
          _        <- repo.save(token1)
          saved    <- repo.findByUserId(token1.userId)
          _        <- repo.delete(saved.get.id)
          afterDel <- repo.findByUserId(token1.userId)
        yield assertTrue(afterDel.isEmpty)
      },

      test("multiple users can each have their own token") {
        val token2 = token1.copy(
          userId = UserId("user-bob"),
          token  = GitHubToken("ghp_bob")
        )
        for
          repo    <- ZIO.service[TokenRepository]
          _       <- repo.save(token1)
          _       <- repo.save(token2)
          alice   <- repo.findByUserId(UserId("user-alice"))
          bob     <- repo.findByUserId(UserId("user-bob"))
        yield assertTrue(
          alice.isDefined,
          bob.isDefined,
          alice.get.token == GitHubToken("ghp_alice_original"),
          bob.get.token   == GitHubToken("ghp_bob")
        )
      }

    ) @@ TestAspect.before(truncateTokens) @@ TestAspect.sequential)
      .provideShared(
        TestDatabase.suiteLayer(migrationLocation),
        transactorLayer,
        MagnumTokenRepository.layer
      )

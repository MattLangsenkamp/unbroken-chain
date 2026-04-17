package ubc.common

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import zio.*
import zio.config.magnolia.*

import javax.sql.DataSource

private final case class DatabaseConfig(
    postgresHost: String,
    postgresDb: String,
    postgresUser: String,
    postgresPassword: String
) derives Config

object HikariMagnumTransactor:

  private val configLayer: TaskLayer[DatabaseConfig] =
    ZLayer.fromZIO(ZIO.config[DatabaseConfig])

  private val dataSourceLayer: ZLayer[DatabaseConfig, Throwable, DataSource] =
    ZLayer.scoped(
      for
        cfg  <- ZIO.service[DatabaseConfig]
        pool <- ZIO.acquireRelease(
          ZIO.attempt {
            val hc = new HikariConfig()
            hc.setJdbcUrl(s"jdbc:postgresql://${cfg.postgresHost}:5432/${cfg.postgresDb}")
            hc.setUsername(cfg.postgresUser)
            hc.setPassword(cfg.postgresPassword)
            new HikariDataSource(hc)
          }
        )(p => ZIO.attempt(p.close()).orDie)
      yield pool
    )

  val layer: TaskLayer[TransactorZIO] =
    (configLayer >>> dataSourceLayer >>> TransactorZIO.layer).tap(z =>
      z.get[TransactorZIO].connect {
        sql"SELECT 1".query[Int].run()
      }
    )

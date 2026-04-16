package ubc.githubgateway.config

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import zio.*
import zio.config.magnolia.*

import javax.sql.DataSource

final case class DatabaseConfig(
  postgresHost: String,
  postgresDb: String,
  postgresUser: String,
  postgresPassword: String
) derives Config

object DatabaseConfig:

  val layer: TaskLayer[DatabaseConfig] =
    ZLayer.fromZIO(ZIO.config[DatabaseConfig])

  val dataSourceLayer: ZLayer[DatabaseConfig, Throwable, DataSource] =
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

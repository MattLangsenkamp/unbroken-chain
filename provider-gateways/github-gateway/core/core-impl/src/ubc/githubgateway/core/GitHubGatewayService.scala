package ubc.githubgateway.core

import ubc.githubgateway.core.ports.*
import ubc.githubgateway.domain.*
import ubc.githubgateway.domain.internal.*
import UserId.given
import neotype.*
import zio.*
import java.time.Instant

case class GitHubGatewayService(github: GitHubPort, tokenRepo: TokenRepository):

  def storeUserToken(userId: UserId, token: GitHubToken): Task[Unit] =
    val internalToken = InternalToken(
      id = TokenId(0L), // assigned by DB on insert
      userId = userId,
      token = token,
      scopes = List(TokenScope("repo"), TokenScope("user:email")),
      createdAt = Instant.now(),
      expiresAt = None
    )
    tokenRepo.save(internalToken)

  def fetchRepo(userId: UserId, owner: RepoOwner, name: RepoName): Task[GitHubRepo] =
    for
      maybeToken <- tokenRepo.findByUserId(userId)
      _          <- ZIO.fail(Exception(s"No token for user ${userId.unwrap}")).when(maybeToken.isEmpty)
      repo       <- github.getRepo(owner, name)
    yield repo

  def listUserRepos(userId: UserId, owner: RepoOwner): Task[List[GitHubRepo]] =
    for
      _          <- ZIO.log(s"$userId")
      maybeToken <- tokenRepo.findByUserId(userId)
      _          <- ZIO.fail(Exception(s"No token for user ${userId.unwrap}")).when(maybeToken.isEmpty)
      repos      <- github.listRepos(owner)
    yield repos

  def searchRepos(query: String): Task[List[GitHubRepo]] =
    github.searchRepos(query)

object GitHubGatewayService:
  val layer: ZLayer[GitHubPort & TokenRepository, Nothing, GitHubGatewayService] =
    ZLayer.fromFunction(GitHubGatewayService.apply)

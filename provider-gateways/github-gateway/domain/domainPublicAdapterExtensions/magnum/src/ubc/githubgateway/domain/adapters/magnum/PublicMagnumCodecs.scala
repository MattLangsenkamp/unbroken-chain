package ubc.githubgateway.domain.adapters.magnum

import ubc.githubgateway.domain.*
import com.augustnagro.magnum.*

object PublicMagnumCodecs:
  given DbCodec[GitHubRepoId] = DbCodec[Long].biMap(GitHubRepoId.apply, _.value)
  given DbCodec[RepoName]     = DbCodec[String].biMap(RepoName.apply, _.value)
  given DbCodec[RepoOwner]    = DbCodec[String].biMap(RepoOwner.apply, _.value)
  given DbCodec[RepoUrl]      = DbCodec[String].biMap(RepoUrl.apply, _.value)
  given DbCodec[GitHubToken]  = DbCodec[String].biMap(GitHubToken.apply, _.value)
  given DbCodec[TokenId]      = DbCodec[Long].biMap(TokenId.apply, _.value)

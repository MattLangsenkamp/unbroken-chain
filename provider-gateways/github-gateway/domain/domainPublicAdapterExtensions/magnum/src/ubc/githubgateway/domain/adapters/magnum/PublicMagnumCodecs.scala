package ubc.githubgateway.domain.adapters.magnum

import ubc.githubgateway.domain.*
import GitHubRepoId.given, RepoName.given, RepoOwner.given, RepoUrl.given, GitHubToken.given, TokenId.given
import com.augustnagro.magnum.*
import neotype.*

object PublicMagnumCodecs:
  given DbCodec[GitHubRepoId] = DbCodec[Long].biMap(GitHubRepoId(_), _.unwrap)
  given DbCodec[RepoName]     = DbCodec[String].biMap(RepoName(_), _.unwrap)
  given DbCodec[RepoOwner]    = DbCodec[String].biMap(RepoOwner(_), _.unwrap)
  given DbCodec[RepoUrl]      = DbCodec[String].biMap(RepoUrl(_), _.unwrap)
  given DbCodec[GitHubToken]  = DbCodec[String].biMap(GitHubToken(_), _.unwrap)
  given DbCodec[TokenId]      = DbCodec[Long].biMap(TokenId(_), _.unwrap)

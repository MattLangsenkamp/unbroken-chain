package ubc.githubgateway.domain.adapters.magnum

import ubc.githubgateway.domain.*
import com.augustnagro.magnum.*

object PublicMagnumCodecs:
  given DbCodec[GitHubRepoId] = DbCodec[Long].imap(GitHubRepoId.apply)(_.value)
  given DbCodec[RepoName]     = DbCodec[String].imap(RepoName.apply)(_.value)
  given DbCodec[RepoOwner]    = DbCodec[String].imap(RepoOwner.apply)(_.value)
  given DbCodec[RepoUrl]      = DbCodec[String].imap(RepoUrl.apply)(_.value)
  given DbCodec[GitHubToken]  = DbCodec[String].imap(GitHubToken.apply)(_.value)
  given DbCodec[TokenId]      = DbCodec[Long].imap(TokenId.apply)(_.value)

package ubc.githubgateway.domain.adapters.json

import ubc.githubgateway.domain.*
import zio.json.*

object PublicJsonCodecs:
  given JsonCodec[GitHubRepoId] = DeriveJsonCodec.gen
  given JsonCodec[RepoName]     = DeriveJsonCodec.gen
  given JsonCodec[RepoOwner]    = DeriveJsonCodec.gen
  given JsonCodec[RepoUrl]      = DeriveJsonCodec.gen
  given JsonCodec[GitHubRepo]   = DeriveJsonCodec.gen
  given JsonCodec[GitHubToken]  = DeriveJsonCodec.gen
  given JsonCodec[TokenId]      = DeriveJsonCodec.gen

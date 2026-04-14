package ubc.githubgateway.domain.adapters.json

import ubc.githubgateway.domain.*
import zio.json.*
import neotype.interop.ziojson.given

object PublicJsonCodecs:
  given JsonCodec[GitHubRepoId] = summon[JsonCodec[GitHubRepoId]]
  given JsonCodec[RepoName]     = summon[JsonCodec[RepoName]]
  given JsonCodec[RepoOwner]    = summon[JsonCodec[RepoOwner]]
  given JsonCodec[RepoUrl]      = summon[JsonCodec[RepoUrl]]
  given JsonCodec[GitHubToken]  = summon[JsonCodec[GitHubToken]]
  given JsonCodec[TokenId]      = summon[JsonCodec[TokenId]]
  given JsonCodec[GitHubRepo]   = DeriveJsonCodec.gen

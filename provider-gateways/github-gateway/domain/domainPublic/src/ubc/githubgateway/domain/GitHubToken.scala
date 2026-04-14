package ubc.githubgateway.domain

import neotype.*

object GitHubToken extends Newtype[String]
type GitHubToken = GitHubToken.Type

object TokenId extends Newtype[Long]
type TokenId = TokenId.Type

package ubc.githubgateway.domain

/** Lifecycle status of a GitHub App installation as reported by GitHub. */
enum InstallationStatus:
  case Active
  case Suspended

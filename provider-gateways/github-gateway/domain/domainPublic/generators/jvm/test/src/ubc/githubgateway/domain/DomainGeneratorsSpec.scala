package ubc.githubgateway.domain

import neotype.*
import zio.test.*

object DomainGeneratorsSpec extends ZIOSpecDefault:

  import DomainGenerators.*

  override def spec =
    suite("DomainGeneratorsSpec")(
      test("Long-backed id newtypes round-trip through unwrap") {
        check(ghInstallationId, accountId, ghRepositoryId, appId) { (a, b, c, d) =>
          assertTrue(
            GhInstallationId(a.unwrap) == a,
            AccountId(b.unwrap) == b,
            GhRepositoryId(c.unwrap) == c,
            AppId(d.unwrap) == d
          )
        }
      },
      test("UUID-backed id newtypes round-trip through unwrap") {
        check(installationId, repositoryId, linkState, deliveryId) { (a, b, c, d) =>
          assertTrue(
            InstallationId(a.unwrap) == a,
            RepositoryId(b.unwrap) == b,
            LinkState(c.unwrap) == c,
            DeliveryId(d.unwrap) == d
          )
        }
      },
      test("String-backed newtypes round-trip through unwrap") {
        check(accountLogin, repoFullName, eventType, eventAction) { (a, b, c, d) =>
          assertTrue(
            AccountLogin(a.unwrap) == a,
            RepoFullName(b.unwrap) == b,
            EventType(c.unwrap) == c,
            EventAction(d.unwrap) == d
          )
        }
      },
      test("remaining String-backed newtypes round-trip through unwrap") {
        check(installUrl, appSlug) { (a, b) =>
          assertTrue(
            InstallUrl(a.unwrap) == a,
            AppSlug(b.unwrap) == b
          )
        }
      },
      test("enum generators only produce defined cases") {
        check(accountType, installationStatus) { (at, is) =>
          assertTrue(
            AccountType.values.contains(at),
            InstallationStatus.values.contains(is)
          )
        }
      },
      test("Installation generator populates enum fields with defined cases") {
        check(installation) { i =>
          assertTrue(
            AccountType.values.contains(i.accountType),
            InstallationStatus.values.contains(i.status)
          )
        }
      },
      test("product generators for LinkedRepo, LinkInitiation, ReconcileSummary sample without throwing") {
        check(linkedRepo, linkInitiation, reconcileSummary)((_, _, _) => assertCompletes)
      }
    )

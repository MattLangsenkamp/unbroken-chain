package ubc.githubgateway.domain

import neotype.*
import zio.test.*

object DomainGeneratorsSpec extends ZIOSpecDefault:

  import DomainGenerators.*

  override def spec =
    suite("DomainGeneratorsSpec")(
      test("Long-backed id newtypes round-trip through unwrap") {
        check(installationId, ghInstallationId, accountId) { (a, b, c) =>
          assertTrue(
            InstallationId(a.unwrap) == a,
            GhInstallationId(b.unwrap) == b,
            AccountId(c.unwrap) == c
          )
        }
      },
      test("remaining Long-backed id newtypes round-trip through unwrap") {
        check(repositoryId, ghRepositoryId, appId) { (a, b, c) =>
          assertTrue(
            RepositoryId(a.unwrap) == a,
            GhRepositoryId(b.unwrap) == b,
            AppId(c.unwrap) == c
          )
        }
      },
      test("String-backed newtypes round-trip through unwrap") {
        check(accountLogin, repoFullName, linkState, deliveryId) { (a, b, c, d) =>
          assertTrue(
            AccountLogin(a.unwrap) == a,
            RepoFullName(b.unwrap) == b,
            LinkState(c.unwrap) == c,
            DeliveryId(d.unwrap) == d
          )
        }
      },
      test("remaining String-backed newtypes round-trip through unwrap") {
        check(eventType, eventAction, installUrl, appSlug) { (a, b, c, d) =>
          assertTrue(
            EventType(a.unwrap) == a,
            EventAction(b.unwrap) == b,
            InstallUrl(c.unwrap) == c,
            AppSlug(d.unwrap) == d
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

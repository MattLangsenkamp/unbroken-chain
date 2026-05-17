package ubc.githubgateway.domain

import zio.test.*

object AccountTypesSpec extends ZIOSpecDefault:

  override def spec =
    suite("AccountTypesSpec")(
      test("AccountType has User and Organization cases") {
        val user: AccountType = AccountType.User
        val org: AccountType  = AccountType.Organization
        assertTrue(
          user == AccountType.User,
          org == AccountType.Organization,
          user != org
        )
      },
      test("AccountType pattern-matches exhaustively") {
        def label(a: AccountType): String = a match
          case AccountType.User         => "user"
          case AccountType.Organization => "org"
        assertTrue(
          label(AccountType.User) == "user",
          label(AccountType.Organization) == "org"
        )
      },
      test("InstallationStatus has Active and Suspended cases") {
        val active: InstallationStatus    = InstallationStatus.Active
        val suspended: InstallationStatus = InstallationStatus.Suspended
        assertTrue(
          active == InstallationStatus.Active,
          suspended == InstallationStatus.Suspended,
          active != suspended
        )
      },
      test("InstallationStatus pattern-matches exhaustively") {
        def label(s: InstallationStatus): String = s match
          case InstallationStatus.Active    => "active"
          case InstallationStatus.Suspended => "suspended"
        assertTrue(
          label(InstallationStatus.Active) == "active",
          label(InstallationStatus.Suspended) == "suspended"
        )
      }
    )

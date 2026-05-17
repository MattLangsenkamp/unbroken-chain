package ubc.githubgateway.core.adapters.inmemory

import ubc.githubgateway.core.ports.InstallationTokenMinter
import ubc.githubgateway.domain.internal.AppJwt
import zio.*

/** Counter-backed in-memory [[InstallationTokenMinter]]. Returns `AppJwt(s"jwt-$counter")` so
  * core service tests can assert on the exact JWT value.
  *
  * NOT a real signer — production must use the RSA-backed adapter.
  */
final class InMemoryInstallationTokenMinter(counter: Ref[Long]) extends InstallationTokenMinter:

  def mintAppJwt(): Task[AppJwt] =
    counter.updateAndGet(_ + 1L).map(n => AppJwt(s"jwt-$n"))

object InMemoryInstallationTokenMinter:
  val layer: ULayer[InstallationTokenMinter] =
    ZLayer.fromZIO(Ref.make(0L).map(new InMemoryInstallationTokenMinter(_)))

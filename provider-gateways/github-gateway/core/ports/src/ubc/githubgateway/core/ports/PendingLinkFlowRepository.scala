package ubc.githubgateway.core.ports

import ubc.githubgateway.domain.LinkState
import ubc.githubgateway.domain.internal.PendingLinkFlow
import zio.Task

import java.time.Instant

/** Persistence port for in-flight link attempts ([[PendingLinkFlow]]).
  *
  * Rows are inserted on `POST /links/initiate`, single-use consumed on the GitHub callback, and reaped by a periodic
  * sweep against [[deleteExpired]]. Identity is the [[LinkState]] nonce, which is unique per row by construction.
  */
trait PendingLinkFlowRepository:

  /** Insert a fresh pending flow row. The caller is responsible for guaranteeing the `state` nonce is unique — adapters
    * MAY surface a unique-violation as a defect.
    */
  def insert(flow: PendingLinkFlow): Task[Unit]

  /** Look up a pending flow by its state nonce. Returns `None` if no row matches. */
  def findByState(state: LinkState): Task[Option[PendingLinkFlow]]

  /** Single-use consume: delete the row keyed by [[LinkState]]. Idempotent — calling twice is not an error, the second
    * call simply returns `false`.
    *
    * @return
    *   `true` if a row was actually deleted, `false` if no matching row existed.
    */
  def deleteByState(state: LinkState): Task[Boolean]

  /** Sweep expired rows. A row is considered expired when `expiresAt <= now`, matching the spec's "past their
    * expires_at" wording.
    *
    * @return
    *   the number of rows removed
    */
  def deleteExpired(now: Instant): Task[Int]

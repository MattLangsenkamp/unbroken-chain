// Manual smoke test (after deploying with `make build-github-gateway-presentation`):
//   1. Open the SPA — should show "Loading..." then "No installations linked yet."
//      if the gateway has no data.
//   2. Click "Link a new GitHub installation" — should redirect to github.com
//   3. After completing install, GitHub redirects to /links/callback. The SPA's
//      Home view refreshes and shows the new installation.
//   4. Click an installation — should show its repos.
//   5. Click "Reconcile" — banner shows "+N -N ~N" counts; repo list refreshes.
//   6. Click "Unlink" — installation disappears from the list.

import tyrian.*
import tyrian.Html.*
import scala.scalajs.js.annotation.JSExportTopLevel
import neotype.* // brings the `.unwrap` extension method into scope for newtypes
import ubc.githubgateway.api.GitHubGatewayApi
import ubc.githubgateway.api.external.http.SttpFetchGitHubGatewayClient
import ubc.githubgateway.domain.*
import ubc.common.pagination.Page
import zio.*
import zio.interop.catz.*

// @JSExportTopLevel on the object causes it to be initialized at module load time.
// TyrianZIOApp.$init$ then calls this.main([]) via unsafeRunAndForget.
@JSExportTopLevel("TyrianApp")
object Main extends TyrianZIOApp[Msg, Model]:

  // Called by TyrianZIOApp.$init$ — mounts the app to <div id="app">
  def main(args: Array[String]): Unit = launch("app")

  def router: Location => Msg = _ => Msg.NoOp

  def init(flags: Map[String, String]): (Model, Cmd[Task, Msg]) =
    val baseUrl                 = flags.getOrElse("apiBase", "http://api.localhost:8080")
    val client: GitHubGatewayApi = SttpFetchGitHubGatewayClient(baseUrl)
    val initialFetch =
      run(client.listInstallations(None, 50)) {
        case Right(page) => Msg.InstallationsLoaded(page)
        case Left(err)   => Msg.LoadFailed(err)
      }
    (Model.init(client), initialFetch)

  /** Runs a Task and surfaces failures as Msgs (rather than crashing the runtime). */
  private def run[A](task: Task[A])(toMsg: Either[String, A] => Msg): Cmd[Task, Msg] =
    Cmd.Run(
      task.either.map {
        case Right(a) => toMsg(Right(a))
        case Left(t)  => toMsg(Left(Option(t.getMessage).getOrElse(t.getClass.getSimpleName)))
      }
    )

  def update(model: Model): Msg => (Model, Cmd[Task, Msg]) =
    case Msg.NoOp =>
      (model, Cmd.None)

    case Msg.InstallationsLoaded(page) =>
      (model.copy(view = View.Home, installations = page.items, error = None), Cmd.None)

    case Msg.LoadFailed(err) =>
      (model.copy(error = Some(err)), Cmd.None)

    case Msg.SelectInstallation(inst) =>
      val cmd = run(model.client.listInstallationRepos(inst.ghInstallationId, None, 50)) {
        case Right(page) => Msg.ReposLoaded(page)
        case Left(err)   => Msg.LoadFailed(err)
      }
      (model.copy(view = View.InstallationDetail(inst, Nil, None), error = None), cmd)

    case Msg.ReposLoaded(page) =>
      val updatedView = model.view match
        case View.InstallationDetail(inst, repos, _) =>
          View.InstallationDetail(inst, repos ++ page.items, page.nextCursor)
        case other => other
      (model.copy(view = updatedView), Cmd.None)

    case Msg.LoadMoreRepos =>
      model.view match
        case View.InstallationDetail(inst, _, Some(cursor)) =>
          val cmd = run(model.client.listInstallationRepos(inst.ghInstallationId, Some(cursor), 50)) {
            case Right(page) => Msg.ReposLoaded(page)
            case Left(err)   => Msg.LoadFailed(err)
          }
          (model, cmd)
        case _ => (model, Cmd.None)

    case Msg.BackToHome =>
      val cmd = run(model.client.listInstallations(None, 50)) {
        case Right(page) => Msg.InstallationsLoaded(page)
        case Left(err)   => Msg.LoadFailed(err)
      }
      (model.copy(view = View.Loading), cmd)

    case Msg.StartLinking =>
      val cmd = run(model.client.initiate()) {
        case Right(init) => Msg.LinkInitiated(init)
        case Left(err)   => Msg.LoadFailed(err)
      }
      (model.copy(view = View.Linking, error = None), cmd)

    case Msg.LinkInitiated(init) =>
      // Tyrian's typed wrapper around `window.location.href = url` — doesn't produce a Msg.
      val nav = Nav.loadUrl[Task](init.installUrl.unwrap)
      (model, nav)

    case Msg.UnlinkInstallation(ghId) =>
      val cmd = run(model.client.unlink(ghId)) {
        case Right(_)  => Msg.BackToHome // refresh list
        case Left(err) => Msg.LoadFailed(err)
      }
      (model, cmd)

    case Msg.ReconcileInstallation(ghId) =>
      val cmd = run(model.client.reconcile(ghId)) {
        case Right(summary) => Msg.ReconcileDone(summary)
        case Left(err)      => Msg.LoadFailed(err)
      }
      (model, cmd)

    case Msg.ReconcileDone(summary) =>
      // After reconcile, refetch repos for the current installation.
      val refetch = model.view match
        case View.InstallationDetail(inst, _, _) =>
          run(model.client.listInstallationRepos(inst.ghInstallationId, None, 50)) {
            case Right(page) => Msg.ReposLoaded(page)
            case Left(err)   => Msg.LoadFailed(err)
          }
        case _ => Cmd.None
      // Reuse `error` as a generic banner slot — minor abuse, fine for this scope.
      val msg = s"Reconciled: +${summary.added} -${summary.removed} ~${summary.renamed}"
      (
        model.copy(
          error = Some(msg),
          // Reset repos so we don't double-render: refetch will repopulate them.
          view = model.view match
            case View.InstallationDetail(inst, _, _) => View.InstallationDetail(inst, Nil, None)
            case other                               => other
        ),
        refetch
      )

  def view(model: Model): Html[Msg] =
    val banner: List[Html[Msg]] =
      model.error.map(e => div(`class` := "banner")(text(e))).toList
    val body: Html[Msg] = model.view match
      case View.Loading => p()(text("Loading..."))
      case View.Linking => p()(text("Redirecting to GitHub..."))
      case View.Home    => homeView(model.installations)
      case View.InstallationDetail(inst, repos, more) =>
        detailView(inst, repos, more)
    div(`class` := "app")(
      header()(h1()(text("GitHub Gateway"))) :: banner ::: List(body)
    )

  private def homeView(installations: List[Installation]): Html[Msg] =
    val list: Html[Msg] =
      if installations.isEmpty then p()(text("No installations linked yet."))
      else
        ul()(
          installations.map { inst =>
            li()(
              button(onClick(Msg.SelectInstallation(inst)))(text(inst.accountLogin.unwrap)),
              text(s"  (${inst.accountType.toString}, status: ${inst.status.toString})")
            )
          }
        )
    section()(
      h2()(text("Linked Installations")),
      button(onClick(Msg.StartLinking))(text("Link a new GitHub installation")),
      list
    )

  private def detailView(
      inst: Installation,
      repos: List[LinkedRepo],
      more: Option[String]
  ): Html[Msg] =
    val repoList: Html[Msg] =
      if repos.isEmpty then p()(text("No repos."))
      else ul()(repos.map(r => li()(text(r.fullName.unwrap))))
    val loadMore: Elem[Msg] = more match
      case Some(_) => button(onClick(Msg.LoadMoreRepos))(text("Load more"))
      case None    => Empty
    section()(
      button(onClick(Msg.BackToHome))(text("<- Back")),
      h2()(text(inst.accountLogin.unwrap)),
      div()(
        button(onClick(Msg.ReconcileInstallation(inst.ghInstallationId)))(
          text("Reconcile from GitHub")
        ),
        button(onClick(Msg.UnlinkInstallation(inst.ghInstallationId)))(text("Unlink"))
      ),
      h3()(text("Repositories")),
      repoList,
      loadMore
    )

  def subscriptions(model: Model): Sub[Task, Msg] = Sub.None

end Main

case class Model(
    client: GitHubGatewayApi,
    view: View,
    installations: List[Installation],
    error: Option[String]
)

object Model:
  def init(client: GitHubGatewayApi): Model =
    Model(client, View.Loading, Nil, None)

enum View:
  case Loading
  case Home
  case Linking
  case InstallationDetail(
      installation: Installation,
      repos: List[LinkedRepo],
      moreCursor: Option[String]
  )

enum Msg:
  case NoOp
  case InstallationsLoaded(page: Page[Installation])
  case LoadFailed(message: String)
  case SelectInstallation(inst: Installation)
  case ReposLoaded(page: Page[LinkedRepo])
  case LoadMoreRepos
  case BackToHome
  case StartLinking
  case LinkInitiated(init: LinkInitiation)
  case UnlinkInstallation(ghId: GhInstallationId)
  case ReconcileInstallation(ghId: GhInstallationId)
  case ReconcileDone(summary: ReconcileSummary)

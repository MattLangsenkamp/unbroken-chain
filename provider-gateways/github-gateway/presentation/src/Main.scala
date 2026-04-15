import tyrian.*
import tyrian.Html.*
import cats.effect.IO
import scala.scalajs.js.annotation.JSExportTopLevel
import ubc.githubgateway.api.GitHubGatewayApi
import ubc.githubgateway.api.external.http.GitHubGatewayJsClient
import ubc.githubgateway.domain.GitHubRepo
import zio.*

// @JSExportTopLevel on the object causes it to be initialized at module load time.
// TyrianIOApp.$init$ then calls this.main([]) via unsafeRunAndForget.
@JSExportTopLevel("TyrianApp")
object Main extends TyrianIOApp[Msg, Model]:

  // Called by TyrianIOApp.$init$ — mounts the app to <div id="app">
  def main(args: Array[String]): Unit = launch("app")

  // Bridge ZIO Task → Cats Effect IO using the ZIO JS runtime (event-loop backed).
  private def runTask[A](task: Task[A]): IO[A] =
    IO.fromFuture(IO(
      Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe.runToFuture(task)
      }
    ))

  def router: Location => Msg = _ => Msg.NoOp

  def init(flags: Map[String, String]): (Model, Cmd[IO, Msg]) =
    val baseUrl = flags.getOrElse("apiBase", "http://localhost:8080")
    val client: GitHubGatewayApi = GitHubGatewayJsClient(baseUrl)
    val fetchCmd = Cmd.Run(
      runTask(client.listRepos("scala"))
        .map(Msg.ReposLoaded.apply)
        .handleError(e => Msg.FetchFailed(e.getMessage))
    )
    (Model.init, fetchCmd)

  def update(model: Model): Msg => (Model, Cmd[IO, Msg]) =
    case Msg.NoOp               => (model, Cmd.None)
    case Msg.ReposLoaded(repos) => (model.copy(repos = repos), Cmd.None)
    case Msg.FetchFailed(err)   => (model.copy(error = Some(err)), Cmd.None)

  def view(model: Model): Html[Msg] =
    div()(
      h1()(text("GitHub Gateway")),
      model.error match
        case Some(err) =>
          p()(text(s"Error: $err"))
        case None if model.repos.isEmpty =>
          p()(text("Loading repos..."))
        case None =>
          ul()(model.repos.map(r => li()(text(s"${r.owner}/${r.name}")))*)
    )

  def subscriptions(model: Model): Sub[IO, Msg] = Sub.None

case class Model(repos: List[GitHubRepo] = Nil, error: Option[String] = None)
object Model:
  val init = Model()

enum Msg:
  case NoOp
  case ReposLoaded(repos: List[GitHubRepo])
  case FetchFailed(error: String)

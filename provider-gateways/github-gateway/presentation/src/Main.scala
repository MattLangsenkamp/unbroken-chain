import tyrian.*
import tyrian.Html.*
import cats.effect.IO
import scala.scalajs.js.annotation.JSExportTopLevel

// @JSExportTopLevel on the object causes it to be initialized at module load time.
// TyrianIOApp.$init$ then calls this.main([]) via unsafeRunAndForget.
@JSExportTopLevel("TyrianApp")
object Main extends TyrianIOApp[Msg, Model]:

  // Called by TyrianIOApp.$init$ — mounts the app to <div id="app">
  def main(args: Array[String]): Unit = launch("app")

  def router: Location => Msg = _ => Msg.NoOp

  def init(flags: Map[String, String]): (Model, Cmd[IO, Msg]) =
    (Model.init, Cmd.None)

  def update(model: Model): Msg => (Model, Cmd[IO, Msg]) =
    case Msg.NoOp => (model, Cmd.None)

  def view(model: Model): Html[Msg] =
    div()(text("GitHub Gateway"))

  def subscriptions(model: Model): Sub[IO, Msg] =
    Sub.None

case class Model()
object Model:
  val init = Model()

enum Msg:
  case NoOp

import tyrian.*
import tyrian.Html.*
import cats.effect.IO
import scala.scalajs.js.annotation.JSExportTopLevel

object Main extends TyrianIOApp[Msg, Model]:

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

// val (not def) so TyrianIOApp.launch executes at module load time.
// ES module scripts are deferred — DOM is ready when this runs.
// "#app" is the CSS selector for <div id="app">.
@JSExportTopLevel("tyrianMain")
val tyrianMain: Unit = TyrianIOApp.launch(Map("#app" -> Main))

import tyrian.*
import tyrian.Html.*
import cats.effect.IO

object Main extends TyrianIOApp[Msg, Model]:

  def init(flags: Map[String, String]): (Model, Cmd[IO, Msg]) =
    (Model.init, Cmd.None)

  def update(model: Model): Msg => (Model, Cmd[IO, Msg]) =
    case Msg.NoOp => (model, Cmd.None)

  def view(model: Model): Html[Msg] =
    div()(text("UBC Control Plane"))

  def subscriptions(model: Model): Sub[IO, Msg] =
    Sub.None

case class Model()
object Model:
  val init = Model()

enum Msg:
  case NoOp

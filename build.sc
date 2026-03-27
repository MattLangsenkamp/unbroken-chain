import mill._
import mill.scalalib._

object unbrokenchain extends ScalaModule {

  def scalaVersion = "2.13.18"

  object test extends ScalaTests with TestModule.Munit {
    def ivyDeps = Agg(
      ivy"org.scalameta::munit::1.0.1"
    )
  }
}


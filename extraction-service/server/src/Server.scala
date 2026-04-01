package ubc.extractionservice.server

import zio.*

object Server extends ZIOAppDefault:
  def run =
    ZIO.logInfo("hello world")
      .repeat(Schedule.fixed(5.seconds))

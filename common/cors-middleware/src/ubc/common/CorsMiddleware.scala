package ubc.common

import zio.http.{Header, Middleware}

object CorsMiddleware:

  /** Returns a CORS middleware that allows any origin whose host exactly matches one of the
    * given base hosts, or is a subdomain of one (e.g. "localhost" allows both bare
    * localhost and *.localhost origins).
    */
  def forHosts(hosts: List[String]): Middleware[Any] =
    Middleware.cors(
      Middleware.CorsConfig(
        allowedOrigin = {
          case origin @ Header.Origin.Value(_, host, _)
              if hosts.exists(h => host == h || host.endsWith(s".$h")) =>
            Some(Header.AccessControlAllowOrigin.Specific(origin))
          case _ => None
        }
      )
    )

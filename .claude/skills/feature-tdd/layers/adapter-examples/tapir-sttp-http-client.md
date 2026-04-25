# Tapir + sttp HTTP Client — Reference

Use this when your driven adapter is a Tapir HTTP client calling an external HTTP API over JVM.

## Location

`<service>/core/adapters/tapir-<name>/src/ubc/<service>/core/adapters/tapir/`

## Implementation

```scala
class TapirGitHubClient(backend: SttpBackend[Task, Any]) extends GitHubPort:

  private val getRepoEndpoint =
    endpoint.get
      .in("repos" / path[String]("owner") / path[String]("repo"))
      .out(jsonBody[GitHubRepo])
      .errorOut(stringBody)

  def getRepo(owner: RepoOwner, name: RepoName): Task[GitHubRepo] =
    SttpClientInterpreter()
      .toRequestThrowDecodeFailures(getRepoEndpoint, Some(uri"https://api.github.com"))
      .apply((owner.unwrap, name.unwrap))
      .send(backend)
      .flatMap(r => ZIO.fromEither(r.body).mapError(Exception(_)))

object TapirGitHubClient:
  val layer: ZLayer[SttpBackend[Task, Any], Nothing, GitHubPort] =
    ZLayer.fromFunction(new TapirGitHubClient(_))
```

## Tests — SttpBackendStub

```scala
import sttp.client3.testing.SttpBackendStub
import sttp.client3.impl.zio.RIOMonadAsyncError

object TapirGitHubClientSpec extends ZIOSpecDefault:

  private val stubBackend: ULayer[SttpBackend[Task, Any]] =
    ZLayer.succeed(
      SttpBackendStub(new RIOMonadAsyncError[Any])
        .whenRequestMatchesPartial {
          case r if r.uri.path.startsWith(List("repos")) =>
            Response.ok("""{"owner":"octocat","name":"Hello-World","description":"test"}""")
        }
    )

  override def spec = suite("TapirGitHubClientSpec")(
    test("getRepo returns a parsed repo") {
      for
        client <- ZIO.service[GitHubPort]
        repo   <- client.getRepo(RepoOwner("octocat"), RepoName("Hello-World"))
      yield assertTrue(repo.name == RepoName("Hello-World"))
    }
  ).provide(stubBackend, TapirGitHubClient.layer)
```

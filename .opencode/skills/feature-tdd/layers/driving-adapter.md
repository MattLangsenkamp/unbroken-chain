# Driving Adapter Layer — Sub-Agent Instructions

You are implementing a driving (inbound) adapter — the entry point that receives an external signal and delegates to the core service. The transport could be anything: HTTP, a message queue (SQS, RabbitMQ, Kafka), gRPC, a scheduled job, or any other inbound mechanism. No business logic lives here.

The adapter's job is always the same: receive the signal, decode it into domain types, call the core service, encode the response (if any). The core service must not know what transport delivered the call.

## General pattern

```scala
// Transport-specific name — the reader should know the full stack from the class name alone.
object <Feature><Transport>Controller:

  def handle(service: <Feature>Service): <TransportEffect> =
    // 1. Decode inbound signal into domain types
    // 2. Call service
    // 3. Encode response (or ack/nack/commit depending on transport)
    // No business logic here.

  val layer: ZLayer[<Feature>Service, Nothing, <TransportOutput>] =
    ZLayer.fromFunction(handle)
```

Rules:
- Decode → call service → encode. Nothing else.
- Depends on `api/api-defn` (or equivalent contract definition) and `core/core-impl`. Never on port traits or adapter modules directly.
- The external API adapter (consumed by other services calling this one) and the internal adapter (what pulls from the queue/request and calls core) are separate. The external adapter is a thin client that emits; the internal adapter translates the inbound signal into a core call.

## Choose the right reference

Pick the reference that matches your adapter's transport:

- **HTTP controller via Tapir + ZIO HTTP** → [`adapter-examples/tapir-zio-http-controller.md`](adapter-examples/tapir-zio-http-controller.md)

For transports not listed above, follow the same principles: name the class for the full transport stack, decode the signal into domain types, call the service, encode the result.

## TDD cycle (Iron Law — no exceptions)

**RED** — Write one failing test for one endpoint or handler. Run it. Confirm it fails because the handler does not exist.

**GREEN** — Implement the minimal handler. Run the test.
Expected: PASS.

**REFACTOR** — Is there any business logic in the adapter? Move it to core. Stay green.

Repeat for each endpoint or handler.

## Naming rules

- Adapter name includes the transport: `<Feature>HttpController`, `<Feature>SqsConsumer`, `<Feature>GrpcHandler` — never just `<Feature>Controller`
- Module dir names follow the same pattern: `http`, `sqs`, `grpc`

## Report back

When complete:
1. **Handlers implemented** — transport, path/topic/method, request type, response type
2. **Tests written** — one line per test: what it verifies
3. **Deviations from plan** — anything that changed
4. **Server wiring needed** — which modules `server` must add to `moduleDeps` to wire this feature end-to-end

# Agent Gateway gRPC Push Service Design

## Scope

Task 7 adds the bidirectional gRPC Agent channel only. The gateway owns
protocol validation, authentication seams, connection lifecycle, command
delivery, and inbound event routing. PostgreSQL remains the source of truth
for task state and durable command/inbound-event state; no task state is kept
in the connection registry.

## Boundaries

- `AgentChannelService` owns one stream and accepts `AgentHello` before any
  other agent payload. It validates metadata, runtime, authentication, and
  `ProtocolCompatibility`, sends `AgentReady`, registers the connection, and
  starts replay.
- `ConnectionRegistry` stores only active connection metadata: connection
  UUID, agent identity, runtime, capabilities, last-seen time, and last
  acknowledged command sequence. A new registration atomically supersedes
  the previous connection for the same Agent.
- `CommandDeliveryService` uses an injected durable command event store. The
  store allocates ordered per-Agent sequences, persists commands before
  delivery, records acknowledgements, and replays unacknowledged commands.
- `InboundEventHandler` rejects stale sessions and agent-id mismatches, asks a
  durable inbound-event store to deduplicate event IDs, then forwards accepted,
  progress, heartbeat, completed, and failed events to an application handler.
- `AgentAuthenticator` is a transport-independent seam. An mTLS or signed
  token implementation can replace the default decision without changing any
  domain/application method signature.

## Data flow

```text
Connect -> Hello -> authenticate -> negotiate -> Ready -> register -> replay
                                      |
                                      +-> reject before registry mutation

assignment -> durable command store (sequence) -> current stream
agent event -> stale check -> durable event-id dedup -> application handler
ack -> stale check -> registry monotonic ack + durable command ack
```

The gateway ports are intentionally small and injectable. An infrastructure
adapter can bridge the command store to the existing transactional outbox and
the inbound-event/application ports to PostgreSQL and domain services without
making the gateway depend on `control-plane` JDBC classes.

## Failure handling

- Invalid Hello, unsupported protocol, failed authentication, or a second
  payload before Hello terminates the stream with a gRPC `INVALID_ARGUMENT` or
  `UNAUTHENTICATED` status.
- Messages from a superseded connection are rejected as stale and never reach
  the application handler or durable acknowledgement state.
- Duplicate event IDs are acknowledged as already applied by the gateway and
  are not forwarded again.
- A command is durable before an active-stream send is attempted; disconnected
  Agents receive it from replay after reconnect.

## Tests

Focused unit tests cover valid/invalid Hello and Ready, TaskAssigned delivery,
accepted event persistence/routing, duplicate suppression, stale connections,
and reconnect replay. They use in-memory port fakes only; no Maven, Java 17,
Docker, NATS, or PostgreSQL runtime is required for the implementation.

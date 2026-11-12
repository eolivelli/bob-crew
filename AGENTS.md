# AGENTS.md

Guidance for coding agents (Bob, Claude Code, and others) working on this repository. Read
[README.md](README.md) first for what the product does, how to run it, and the API reference; this
file covers what you need to change the code safely.

## What this is

bob-crew runs many IBM Bob Shell sessions on a server. Each session is one `bob acp` child process
speaking the [Agent Client Protocol](https://agentclientprotocol.com) (newline-delimited JSON-RPC
2.0 on stdin/stdout). The server puts an HTTPS API in front of them; a small local app serves the
web UI and forwards API calls with the user's token.

```
browser --http 127.0.0.1--> bobcrew-ui --HTTPS + Bearer JWT--> bobcrew-server --stdio--> bob acp
```

## Build and test

Java 21 and Maven are required (`.sdkmanrc` pins the JDK; run `sdk env` or set `JAVA_HOME`). The
default JDK on a machine may be older, and the build fails on it.

```
mvn install                                   # everything, with tests (~40s)
mvn -q install -DskipTests                    # just build the jars
mvn test -pl bobcrew-server -am               # one module and what it needs
mvn test -pl bobcrew-server -Dtest=ServerIntegrationTest#aUserAnswersAPermissionRequest
```

Tests never need Bob or an API key: they start `bobcrew-fake-agent` as the agent process. Do not
add a test that requires the real `bob` binary or network access.

After changing `bobcrew-fake-agent`, run `mvn install -pl bobcrew-fake-agent` (or build with
`-am`) before running server tests, because the server tests load it from the local repository.

To try a change by hand, follow "Quick start" in the README with the fake agent
(`BOB_PATH=java`, `BOB_ARGUMENTS=-jar,<path to bobcrew-fake-agent-*-all.jar>`).

## Layout

| Path | Contents |
|---|---|
| `bobcrew-server/` | The service: Spring Boot 4 (Spring MVC, virtual threads), package `bobcrew.server` |
| `bobcrew-ui/` | Loopback-only UI app, package `bobcrew.ui`; pages in `src/main/resources/static/` |
| `bobcrew-fake-agent/` | `FakeAcpAgent`: scripted ACP agent, hand-written JSON-RPC, no SDK |
| `scripts/gen-dev-certs.sh` | Private CA, server certificate and JWT secret for development |

### Server classes

| Class | Role |
|---|---|
| `SessionController` | The whole HTTP API, including the SSE stream. Thin: validates input, calls `SessionManager`. |
| `SessionManager` | The only entry point the API uses. Owns the in-memory session map, the session limit, idle cleanup, shutdown. |
| `AgentSession` | One agent process and its ACP session: start, prompt, cancel, mode, permissions, close, crash detection. |
| `EventLog` | A session's numbered events with replay; one queue per subscriber. |
| `UiEvent` | The event record clients receive, and the event type names. |
| `Workspaces` | Turns a client-supplied workspace name into a directory under the root. The only place that does. |
| `SessionAccessPolicy` | Who may use a session. Currently everyone; narrow it here and nowhere else. |
| `SecurityConfig` | JWT validation (signature, expiry, issuer, audience, subject, revocation list). |
| `Tokens`, `IssueTokenCommand` | Secret loading and the `issue-token` command line. |
| `BobCrewProperties` | All `bobcrew.*` settings. Defaults live here and in `application.yml`; keep the two in step. |

### UI classes

| Class | Role |
|---|---|
| `ProxyController` | Forwards `/api/**` to the server with the token; streams responses through. |
| `LocalOnlyFilter` | Refuses anything not coming from the UI's own pages on the loopback interface. |
| `ServerConnection` | Server URL, token, and the HTTP client with the trusted CA. |
| `static/app.js` | The whole front end: plain JavaScript, no build step, no dependencies. |

## How a prompt flows

1. `POST /api/sessions/{id}/prompt` reaches `AgentSession.prompt`, which records a `user_prompt`
   event, sets status `RUNNING`, and returns. The HTTP call answers 202 at once.
2. A virtual thread calls the SDK's blocking `client.prompt(...)`.
3. The agent's `session/update` notifications arrive in `AgentSession.onUpdate` and are appended
   to the `EventLog` unchanged, as `update` events. The server does not interpret them, apart from
   tracking the current mode.
4. If the agent sends `session/request_permission`, `onPermissionRequest` parks a
   `CompletableFuture` keyed by tool call id and emits `permission_request`. It is completed by
   `answerPermission` (a user), by `cancel`, or by the timeout, which picks a reject option.
5. When `client.prompt` returns, a `turn_end` event is appended and status returns to `IDLE`.
6. Clients read all of this from `GET /api/sessions/{id}/events?after=<seq>`.

## Things that are easy to get wrong

- **ACP SDK** (`com.agentclientprotocol:acp-core`, version in the root `pom.xml`) is pre-1.0 and
  changes between releases. Do not rely on memory of its API: read the sources jar in
  `~/.m2/repository/com/agentclientprotocol/acp-core/<version>/`. Its "enums" such as
  `PermissionOptionKind` and `StopReason` are records with a `value()`, not Java enums, so that
  unknown values survive. Unknown `session/update` kinds arrive as `UnknownSessionUpdate`; keep
  passing them through.
- **Jackson 3**: Spring Boot 4 uses `tools.jackson.*` (`JsonMapper`, `JsonNode.asString()`), with
  annotations still in `com.fasterxml.jackson.annotation`. Do not add Jackson 2 imports.
- **Child process handle**: the SDK does not expose the process it starts. `AgentSession.start`
  finds it by comparing the JVM's children before and after, under `START_LOCK`. The handle is
  what allows killing the agent's whole process tree and noticing a crash. Keep client
  construction and `initialize()` inside that lock.
- **SSE is written from the request thread** in a blocking loop (`SessionController.events`), not
  with `SseEmitter`. This relies on virtual threads being enabled. `EventLog.close()` must wake
  waiting readers, otherwise shutdown waits for the keepalive interval.
- **Event sequence numbers** are contiguous per session and clients resume by them. Never mutate
  or reorder events after `append`; add new event types rather than changing the shape of
  existing ones, and update `app.js` and the README table when you do.
- **Status changes** go through `AgentSession.setStatus`, which also emits the `status` event the
  UI depends on.
- **Shutdown**: `SessionManager.closeAll` runs on `ContextClosedEvent`, before the web server's
  graceful shutdown, so that open event streams end first.

## Security rules

These are requirements, not preferences. A change that weakens one needs an explicit request from
the maintainer.

- The Bob API key stays in server configuration and the child's environment. It must never appear
  in a DTO, an event, a log line, or `toString()`.
- The JWT secret and tokens are never logged. The UI's token must never be sent to the browser.
- Every path from a client goes through `Workspaces.resolve`. Do not build paths from request
  data anywhere else.
- The server has no cookies and no CORS headers. Adding either creates a CSRF surface that does
  not exist today.
- The UI app binds to `127.0.0.1` and has no login; `LocalOnlyFilter` (Host, Origin,
  `Sec-Fetch-Site`, the `X-BobCrew` header on changes) is what stops other web pages from using
  it. New UI endpoints must stay behind it.
- In `app.js`, text from the agent or from other users is inserted with `textContent` only. No
  `innerHTML`, no inline scripts or styles (the Content-Security-Policy forbids them).
- `--auto-approve` is not a default agent argument, and the permission timeout rejects.
- TLS verification in `ServerConnection` is always on. Do not add a "trust all" option.

## Conventions

- Plain Java, records for data, package-private by default. No Lombok, no reactive types in our
  own code (the SDK uses Reactor internally; we use its synchronous client).
- Errors meant for the API client are thrown as `ApiException(status, message)`; the message is
  shown to users, so write it for them.
- Javadoc on classes and on anything non-obvious, saying why rather than what.
- Every behaviour change comes with a test. Prefer extending `ServerIntegrationTest` (real HTTPS,
  real child process) for server behaviour; add a keyword to `FakeAcpAgent` if you need the agent
  to do something new, and document it in its class comment and the README.
- Keep dependencies minimal: a new one needs a reason that the JDK and Spring cannot cover.
- Commits: short imperative subject, body explaining why. No generated attribution trailers.

## Where the project is going

The first version is deliberately one process on one machine with no database. The planned next
shape is a hub: worker processes that own the agent processes, stateless coordinators serving the
API, mutual TLS between them, and a small metadata store for users, sessions and worker discovery.
`SessionManager` is the seam for that split, so keep controllers talking only to it and keep
`AgentSession` free of HTTP concerns. Other open items are listed under "Not in this version" in
the README.

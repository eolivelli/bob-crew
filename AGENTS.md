# AGENTS.md

Guidance for coding agents (Bob, Claude Code, and others) working on this repository. Read
[README.md](README.md) first for what the product does, how to run it, and the API reference; this
file covers what you need to change the code safely.

## What this is

bob-crew runs many IBM Bob Shell sessions on a server. Each session is one Docker container running
`bob acp`, which speaks the [Agent Client Protocol](https://agentclientprotocol.com) (newline-delimited
JSON-RPC 2.0) on the container's stdin/stdout. The server reaches it through the Docker API
(docker-java), attached to those streams. It puts an HTTPS API in front of the sessions; a small local
app serves the web UI and forwards API calls with the user's token.

```
browser --http 127.0.0.1--> bobcrew-ui --HTTPS + Bearer JWT--> bobcrew-server --Docker attach--> container: bob acp
   \--- log in ---> Keycloak <--- tokens, keys ---/
```

Users log in with Keycloak (OpenID Connect; the realm is `keycloak/bobcrew-realm.json`), or are given HS256
tokens made with `issue-token`; the server accepts both, each checked as its issuer requires.

## Build and test

Java 21 and Maven are required (`.sdkmanrc` pins the JDK; run `sdk env` or set `JAVA_HOME`). The
default JDK on a machine may be older, and the build fails on it.

```
mvn install                                   # everything, with tests (~40s)
mvn -q install -DskipTests                    # just build the jars
mvn test -pl bobcrew-server -am               # one module and what it needs
mvn test -pl bobcrew-server -Dtest=ServerIntegrationTest#aUserAnswersAPermissionRequest
```

The UI's JavaScript tests (`bobcrew-ui/src/test/js`) run in the same `test` phase through
exec-maven-plugin, so `node` (18 or newer) must be on the PATH; `-DskipTests` skips them.

Tests never need Bob or an API key: the server tests run `bobcrew-fake-agent` in real containers, from
an image they build once per run (`bobcrew-fake-agent:test`, from
`bobcrew-server/src/test/docker/fake-agent/Dockerfile` plus `docker/bob-base/bobcrew-entrypoint.sh`).
They need a Docker daemon and are skipped, not failed, without one (`TestDocker`, a JUnit condition, decides
before Spring starts), unless `BOBCREW_REQUIRE_DOCKER=true`, which CI (`.github/workflows/ci.yml`) sets so that
they fail instead. The only network use is building that image the first time (its base, and
`microdnf install git-core`). The image holds a bare repository, `/opt/test-repos/sample.git` (branches
`main` and `feature`), which the tests clone over `file://` (allowed only in tests, through
`bobcrew.git.allowed-schemes`). The OpenID Connect tests (`OidcIntegrationTest`, `UiKeycloakIntegrationTest`) run a
real Keycloak, `bobcrew-test-support`'s `TestKeycloak`: one container per test JVM with the shipped realm imported,
users and a password-grant test client added through the admin API, skipped like the Docker tests
(`KeycloakAvailable`). Pulling its pinned image the first time is the other network use. Do not add a test that
requires the real `bob` binary, the `bobcrew/bob-*` images, or other network access.

After changing `bobcrew-fake-agent`, run `mvn install -pl bobcrew-fake-agent` (or build with
`-am`) before running server tests: they copy its `-all` jar from the local repository into the image.

To try a change by hand, follow "Quick start" in the README with the fake agent's image
(`BOBCREW_DEFAULT_IMAGE=bobcrew-fake-agent:test`, `BOBCREW_AGENT_COMMAND=java,-jar,/opt/fake-agent.jar`).

## Layout

| Path | Contents |
|---|---|
| `bobcrew-server/` | The service: Spring Boot 4 (Spring MVC, virtual threads), package `bobcrew.server` |
| `bobcrew-ui/` | Loopback-only UI app, package `bobcrew.ui`; pages in `src/main/resources/static/` |
| `bobcrew-fake-agent/` | `FakeAcpAgent`: scripted ACP agent, hand-written JSON-RPC, no SDK |
| `bobcrew-test-support/` | Tests only: `TestKeycloak` (a real Keycloak in Docker, with the realm) and `KeycloakAvailable` (the JUnit condition) |
| `keycloak/bobcrew-realm.json` | The Keycloak realm: roles, the public `bobcrew-ui` client (PKCE, port-less loopback callback), the audience and limit-attribute mappers, the user profile declaring those attributes. `RealmFileTest` keeps it in step with the code |
| `docker/bob-base/` | The default session image (UBI 9, Bob Shell, git, gh, Node, Homebrew) and `bobcrew-entrypoint.sh`, the image/server contract |
| `docker/bob-java/` | Example image built on bob-base (JDK 21, Maven, Gradle, Ant) |
| `docker/bob-java-python/` | Development template image (JDK 21, Maven 3.9, Python 3.11); git/GitHub setup comes from bob-base's `profile.d/40-git.sh` |
| `docker/bob-power/` | bob-java-python plus Docker Engine and Vagrant/libvirt (QEMU from CentOS Stream 9), for sessions with the docker and libvirt capabilities; `root.d/` starts the daemons asked for |
| `ui.template/` | Starting `~/.bobcrew/` for UI users (`ui.properties`, `roles/developer.md`, `roles/power-user.md`), copied by `scripts/init-ui-config.sh`; `RoleRegistryTest` checks the roles stay valid |
| `scripts/gen-dev-certs.sh` | Private CA, server certificate and JWT secret for development |
| `scripts/build-images.sh`, `scripts/push-images.sh` | Build the images; copy images to another machine's Docker over ssh, only the layers it lacks (`drop-layers.py` filters the `docker save` archive) |
| `scripts/deploy-keycloak.sh`, `scripts/keycloak-user.sh` | Run Keycloak in Docker on a machine (production mode, HTTPS, dev-file database on a volume); manage its users with `kcadm.sh` inside the container (`keycloak-admin.sh`, the part that runs there) |

### Server classes

| Class | Role |
|---|---|
| `SessionController` | The whole HTTP API, including the SSE stream. Thin: validates input, calls `SessionManager` with the request's `Caller`. |
| `Caller`, `CallerFactory`, `CallerAuthentication`, `CallerArgumentResolver` | Who makes a request and what they may do: name, admin, allowed capabilities, limits. `CallerFactory` makes it from a checked token: from `issue-token`'s as before Keycloak, from Keycloak's by roles and limit claims (bounded by the server's settings and ceilings). |
| `SessionManager` | The only entry point the API uses. Owns the in-memory session map, the session limits (in all and per user), the image allowlist, idle cleanup, shutdown, and the checks on client-supplied environment variables and secrets. |
| `AgentSession` | One agent container and its ACP session: start (with the repository clone), prompt (the first one carries the environment notes), cancel, mode, permissions, clear, metrics, file reads and uploads, close, crash detection. |
| `DockerRuntime` | The only class that talks to Docker: creates the sessions' network (inter-container traffic off) and containers (hardened, labelled, never pulling; privileged ones only for allowed capabilities), attaches, execs as the session's user (workspace wipe, file writes, `git clone` through the image's entrypoint), stats, copies files out, removes; removes this instance's leftover containers at startup and shutdown. |
| `AllowedImages` | Which images a client may name (`bobcrew.docker.allowed-images` globs, plus the default image). |
| `Capabilities`, `Resources` | What a session may run besides commands (`docker`, `libvirt`), checked against `bobcrew.allowed-capabilities`; and its memory and CPU limits, checked against the server's caps. |
| `GitRepository` | A repository to clone into a new session: checks the URL (allowed schemes, no credentials) and branch, and names the clone's directory. |
| `ContainerAcpTransport` | The SDK's `AcpClientTransport` over a container's attach streams. The one class of ours with Reactor types, because the SDK interface has them. |
| `ContainerMetrics` | The metrics record (`GET /sessions/{id}/metrics`). |
| `EventLog` | A session's numbered events with replay; one queue per subscriber. |
| `UiEvent` | The event record clients receive, and the event type names. |
| `ContainerPaths` | Turns a client-supplied file path into a path inside `/opt/workspace` of the container. The only place that does. |
| `SessionAccessPolicy` | Who may use a session (owner, users it is shared with, admins) and who may close or re-share it (owner, admins). Decide access here and nowhere else. |
| `SecurityConfig` | JWT validation, per issuer (`JwtIssuerAuthenticationManagerResolver`): `issue-token`'s HS256 (signature, expiry, issuer, audience, subject, revocation list) and Keycloak's (its keys, fetched on first use; expiry, issuer, audience, user name). Every endpoint needs the `CallerAuthentication.USE` authority; its absence answers 403 with a message. |
| `Tokens`, `IssueTokenCommand` | Secret loading and the `issue-token` command line. |
| `BobCrewProperties` | All `bobcrew.*` settings. Defaults live here and in `application.yml`; keep the two in step. |

### UI classes

| Class | Role |
|---|---|
| `ProxyController` | Forwards `/api/**` to the server with the server's token, or the user's login (`OidcLogin.currentToken()`; 401 `{"login": "/login"}` without one); streams responses through, and upload request bodies too (other bodies are read whole, up to 2 MB). Rewrites session creation: `role` in, the role's `image`, `secrets` (values from `SecretStore`), `userProfile` (from `UserProfileStore`) and `repository` out; refuses `image`/`env`/`secrets`/`repository` sent by the browser. |
| `LocalOnlyFilter` | Refuses anything not coming from the UI's own pages on the loopback interface. |
| `ServerConnection` | Server URL, token (none when the login provides it), and the HTTP client with the trusted CA. |
| `OidcLogin`, `LoginController` | The user's Keycloak login: authorization code with PKCE as a public client, `state` once and for 10 minutes, tokens in memory only, refreshed 30 s before expiry; `/login`, `/login/callback`, `/logout`, `/api/login`. |
| `RoleRegistry`, `RolesController` | Roles (first prompt, `image`, `secrets` names (`env` is an alias), `repository` and `branch`): Markdown manifests in `~/.bobcrew/roles`, read on every request; `GET /api/roles[/{id}]` answered locally, read-only. UI only, the server knows nothing of roles. Ids must match a strict name pattern and are never joined into a path otherwise; symlinks are ignored. |
| `SecretStore` | Where a role's secret values come from: `~/.bobcrew/secrets.json` (`bobcrew.ui.secrets-file`), one JSON object, refused unless mode 600, read for every new session, never quoted in errors. Never the UI process's environment. |
| `UserProfileStore` | Where user profile files come from: `~/.bobcrew/user-profile/` (`bobcrew.ui.user-profile-dir`), regular files loaded and Base64-encoded for every new session and synced into `/home/bob/` in the container. |
| `static/app.js` | The front end: plain JavaScript, no build step, no dependencies. |
| `static/links.js` | Makes `https://` links in agent and user text clickable (https only, built as DOM elements, never HTML). Tested with `node bobcrew-ui/src/test/js/links.test.js` (Maven runs it too). |
| `static/transcript-export.js` | Content-to-text helpers and the transcript export (events to a gzipped HTML page). Builds HTML as a string, so all text goes through `esc()`. Tested with `node bobcrew-ui/src/test/js/transcript-export.test.js` (Maven runs it too). |

## How a prompt flows

0. At creation, `AgentSession.start` has `DockerRuntime` create the container (not started), and
   builds the SDK client on a `ContainerAcpTransport`, whose `connect` attaches to the container and
   only then starts it, so no output is lost, and then writes the session's secrets into the container (the
   entrypoint waits for them).
1. `POST /api/sessions/{id}/prompt` reaches `AgentSession.prompt`, which records a `user_prompt`
   event, sets status `RUNNING`, and returns. The HTTP call answers 202 at once.
2. A virtual thread calls the SDK's blocking `client.prompt(...)`; the request is written as one line
   to the container's stdin.
3. The agent's `session/update` notifications arrive (stdout frames, cut into lines by the transport)
   in `AgentSession.onUpdate` and are appended
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
- **The container transport**: containers have no TTY, so Docker multiplexes stdout and stderr and
  docker-java hands them over as separate frames; only stdout carries ACP. Frames do not respect line
  boundaries, so `LineSplitter` reassembles lines. The container is created with `StdinOnce`, so
  detaching ends the agent's stdin and it exits. When the attach stream ends without a local close, the
  container died: the transport reports it (with the exit code) and `AgentSession.onAgentExit` marks the
  session `FAILED`.
- **Secrets are written between start and the first message**: the entrypoint waits for
  `/run/bobcrew/secrets/.ready` before it sources `profile.d` and runs the agent, and the tmpfs exists only once
  the container runs, so `ContainerAcpTransport.connect` calls its `afterStart` hook (which writes them) right after
  starting the container, before the SDK sends `initialize`. Writing them later, after `initialize`, would wait
  forever for an agent that waits for them.
- **The entrypoint keeps stdout clean**: anything an image prints to stdout before or besides the agent
  becomes an unreadable ACP line. `bobcrew-entrypoint.sh` sends all its output to stderr; images built
  on bob-base must do the same in their `profile.d` snippets.
- **docker-java** brings Jackson 2 with it (`com.fasterxml.jackson.databind`). It is for docker-java's
  own use; our code stays on Jackson 3. Docker 29 refuses API versions below 1.44, so the version is set
  explicitly (`bobcrew.docker.api-version`). Exec exit codes can appear a moment after the exec's output
  ends; `DockerRuntime.exec` polls for them.
- **Exec input never ends**: docker-java's httpclient5 transport does not half-close the connection when
  an exec's stdin stream ends, so a command reading to end of input waits forever. `DockerRuntime.writeFile`
  therefore spools the upload to a temporary file first, to know its size, and runs `head -c <size>`.
- **Sessions with capabilities start as root**: the container's user is `0:0`, `BOBCREW_AGENT_USER` names the
  user to become and `BOBCREW_CAPABILITIES` what the `root.d` scripts should start. `bobcrew-entrypoint` runs `/etc/bobcrew/root.d/*.sh` (daemons) and re-runs itself through
  `setpriv`; execs the server makes (clone, uploads) still name `bobcrew.docker.user`, so they never take
  that root path. Docker data needs a volume (dockerd cannot layer on overlayfs), which bob-power declares
  with `VOLUME`; the cgroup v2 move into `/init` in `root.d/50-docker.sh` is what lets dockerd create
  cgroups. `CapabilitiesIntegrationTest` covers this with the fake agent's image and a test `root.d` hook.
- **Leftover containers**: every container carries `bobcrew.managed` and `bobcrew.instance` labels;
  `DockerRuntime.removeOrphans` removes those of this instance at startup and in `closeAll`. Tests use a
  random instance id so that they never touch a real server's containers on the same machine.
- **SSE is written from the request thread** in a blocking loop (`SessionController.events`), not
  with `SseEmitter`. This relies on virtual threads being enabled. `EventLog.close()` must wake
  waiting readers, otherwise shutdown waits for the keepalive interval.
- **Event sequence numbers** are contiguous per session and clients resume by them. Never mutate
  or reorder events after `append` (the one exception is `EventLog.discard()`, used by clearing a
  session, which drops all kept events and goes on numbering); add new event types rather than changing the shape of
  existing ones, and update `app.js` and the README table when you do.
- **Status changes** go through `AgentSession.setStatus`, which also emits the `status` event the
  UI depends on.
- **Shutdown**: `SessionManager.closeAll` runs on `ContextClosedEvent`, before the web server's
  graceful shutdown, so that open event streams end first.

## Security rules

These are requirements, not preferences. A change that weakens one needs an explicit request from
the maintainer.

- The Bob API key stays in server configuration and the container's environment. It must never appear
  in a DTO, an event, a log line, or `toString()`. The same goes for the values of the environment
  variables and secrets clients pass (`ContainerSpec.toString()`, `CreateRequest.toString()` and the logs
  show names only).
- Secrets are files, never environment variables: `DockerRuntime.writeSecrets` puts them in the tmpfs
  `/run/bobcrew/secrets` (mode 700, files 400, the session's user) through an exec's standard input, never a
  command line, and `AgentSession` drops the values once written. Images read them from there when needed
  (bob-base's `gh` wrapper and `git-credential-bobcrew`); do not export them in a `profile.d` snippet. The UI
  takes their values from `~/.bobcrew/secrets.json` only, never from its own environment.
- The JWT secret and tokens are never logged. The UI's tokens (configured, or the user's Keycloak access, refresh
  and id tokens) must never be sent to the browser, and live in the UI's memory only.
- A token's issuer picks how it is checked; never let a token of one issuer be checked with the other's key
  (an HS256 token claiming Keycloak's issuer must be refused). Keycloak roles and attributes only narrow what
  the server allows: a capability needs `bobcrew.allowed-capabilities` and the role; a user's limits stay under
  the ceilings.
- The Keycloak client for UIs is public: never give it a secret, the password grant or the implicit flow, and
  keep PKCE required. Its callback is the loopback address without a port. The admin password of
  `deploy-keycloak.sh` stays on Keycloak's machine and reaches `kcadm.sh` through the environment only.
- Every path from a client goes through `ContainerPaths.resolve`. Do not build paths from request
  data anywhere else. File downloads serve regular files only, never what a symbolic link points to.
  Uploads are written by an exec running as the session's user, never through Docker's archive API
  (which writes as root and follows links anywhere in the container).
- Repository URLs and branches go through `GitRepository.of`, and reach `git` as separate arguments after
  `--`, never through a shell. URLs with credentials are refused: the URL shows in logs, events and the
  session's details.
- Containers get no host mounts (binds or volumes), no host environment, no capabilities,
  `no-new-privileges`, and a non-root user enforced by the server. Images are never pulled, and must match
  `bobcrew.docker.allowed-images` (`AllowedImages`, checked by the server, not just the UI's roles). They join
  their own network with inter-container traffic off, never Docker's built-in ones, and get memory and process
  limits by default. Keep it so.
  The one exception is a session with capabilities: only for capabilities in `bobcrew.allowed-capabilities`
  (default none) that the session asked for, the container is privileged and starts as root, and the entrypoint must still drop
  to `BOBCREW_AGENT_USER` before the agent runs (it exits 77 rather than run the agent as root). Never make
  this the default, never apply it to sessions that did not ask, and keep host mounts out of it too (image
  `VOLUME`s are anonymous and removed with the container).
- Session creation from the browser goes through the UI's role expansion: the page never sees
  secret values and cannot pick an image, secrets, variables or a repository itself. It may ask for
  `capabilities` and `resources`; the server decides whether they are allowed.
- A session is its owner's: other users see it only once it is shared with them, and only the owner or an admin
  closes or re-shares it. Every endpoint reaches a session through `SessionManager` with the caller's name, which
  asks `SessionAccessPolicy`; a session the caller may not use answers 404, never 403, so that its existence does
  not leak. Anything long-lived (the event stream) checks access again as it goes.
- The server has no cookies and no CORS headers. Adding either creates a CSRF surface that does
  not exist today.
- The UI app binds to `127.0.0.1` and acts with its one user's token or login; `LocalOnlyFilter` (Host, Origin,
  `Sec-Fetch-Site`, the `X-BobCrew` header on changes) is what stops other web pages from using
  it. New UI endpoints must stay behind it. Its one exception, `GET /login/callback` from another site (Keycloak
  sends the browser there), must only ever complete a login the UI started.
- In `app.js`, text from the agent or from other users is inserted with `textContent` only. No
  `innerHTML`, no inline scripts or styles (the Content-Security-Policy forbids them).
- `--auto-approve` is not in the default agent command, and the permission timeout rejects.
- TLS verification in `ServerConnection` is always on. Do not add a "trust all" option.

## Conventions

- Plain Java, records for data, package-private by default. No Lombok, no reactive types in our
  own code (the SDK uses Reactor internally; we use its synchronous client).
- Errors meant for the API client are thrown as `ApiException(status, message)`; the message is
  shown to users, so write it for them.
- Javadoc on classes and on anything non-obvious, saying why rather than what.
- Every behaviour change comes with a test. Prefer extending `ServerIntegrationTest` (real HTTPS,
  real container) for server behaviour; add a keyword to `FakeAcpAgent` if you need the agent
  to do something new, and document it in its class comment and the README.
- Keep dependencies minimal: a new one needs a reason that the JDK and Spring cannot cover.
- Commits: short imperative subject, body explaining why. No generated attribution trailers.

## Where the project is going

The first version is deliberately one process on one machine with no database. The planned next
shape is a hub: worker processes that own the agent containers, stateless coordinators serving the
API, mutual TLS between them, and a small metadata store for users, sessions and worker discovery.
`SessionManager` is the seam for that split, so keep controllers talking only to it and keep
`AgentSession` free of HTTP concerns. Other open items are listed under "Not in this version" in
the README.

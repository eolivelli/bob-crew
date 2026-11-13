# bob-crew

> [!WARNING]
> **For testing purposes only. Not meant to be used in production.**
>
> bob-crew is an experimental extension for IBM Bob. It has no database, keeps sessions in memory,
> has no user management, and lets every signed-in user use every session. The wide-permission
> scripts run agents with `--trust` and `--auto-approve`. Do not run it on a machine that holds
> data or credentials you cannot afford to expose, and do not put it on a public network.

Run many [IBM Bob Shell](https://bob.ibm.com/docs/shell/features/acp) sessions on a server and drive
them from a browser.

`bob acp` speaks the [Agent Client Protocol](https://agentclientprotocol.com) on stdin/stdout, so
one process serves one local client. bob-crew starts one `bob acp` process per session and puts an
HTTPS API in front of them.

```
laptop                                             server machine
browser --http 127.0.0.1--> [bobcrew-ui] --HTTPS + Bearer JWT--> [bobcrew-server] --stdio--> bob acp (N processes)
          (no login)         pages + proxy                        REST + SSE API
```

| Module | What it is |
|---|---|
| `bobcrew-server` | The service. Starts the agent processes, exposes the API over TLS, checks a JWT on every request. |
| `bobcrew-ui` | A small app for your laptop. Serves the web pages on `127.0.0.1` and forwards API calls with your token. |
| `bobcrew-fake-agent` | A scripted ACP agent, for tests and for trying things without a Bob API key. |

Sessions live in memory: restarting the server ends them, as it ends their Bob processes.

## Build

Needs Java 21 and Maven. The build runs all tests against the fake agent; it needs neither Bob nor
an API key.

```
mvn install
```

## Quick start

On the server machine:

```
# 1. TLS certificate (private CA) and JWT signing secret, written to ./certs
scripts/gen-dev-certs.sh certs my-server.example.com

# 2. A token for each user (run where the secret file is)
export BOBCREW_JWT_SECRET_FILE=certs/jwt.secret
java -jar bobcrew-server/target/bobcrew-server-0.1.0-SNAPSHOT.jar issue-token --user alice --ttl 30d

# 3. Start the server (reads certs/server.crt and certs/server.key by default)
export BOB_API_KEY=...            # the key Bob uses, shared by all sessions
java -jar bobcrew-server/target/bobcrew-server-0.1.0-SNAPSHOT.jar
```

To try it without Bob, point the server at the fake agent instead of setting `BOB_API_KEY`:

```
export BOB_PATH=java
export BOB_ARGUMENTS=-jar,$PWD/bobcrew-fake-agent/target/bobcrew-fake-agent-0.1.0-SNAPSHOT-all.jar
```

The fake agent reacts to words in the prompt: `tool` (asks permission), `plan`, `slow` (runs until
stopped), `crash`.

On your laptop, with the token and a copy of `certs/ca.crt`, create `~/.bobcrew/ui.properties`:

```
bobcrew.ui.server-url=https://my-server.example.com:8443
bobcrew.ui.token=<the token>
bobcrew.ui.ca-file=/path/to/ca.crt
```

then start the UI and open <http://127.0.0.1:8642>:

```
java -jar bobcrew-ui/target/bobcrew-ui-0.1.0-SNAPSHOT.jar
```

## Exporting a session

The **Export transcript** button in a session's header downloads the whole session as one
standalone HTML page, gzipped (`<title>-<date>.html.gz`; `gunzip` it, then open it). It is built in
the browser from the events the page has received, so it contains what the server still holds: the
last `bobcrew.event-buffer-size` events (5000 by default), and the page says so when the start of a
session is missing. The file has no scripts and carries a Content-Security-Policy that blocks all
network access.

## Server configuration

Set as environment variables, or as the Spring properties in
[`application.yml`](bobcrew-server/src/main/resources/application.yml).

| Variable | Default | Meaning |
|---|---|---|
| `BOBCREW_PORT` | `8443` | HTTPS port |
| `BOBCREW_TLS_CERT`, `BOBCREW_TLS_KEY` | `file:certs/server.crt`, `file:certs/server.key` | PEM certificate (chain) and private key |
| `BOBCREW_JWT_SECRET_FILE` | none, required | File with the HS256 secret, at least 32 bytes |
| `BOBCREW_REVOKED_JTI_FILE` | none | File listing revoked token ids, one per line |
| `BOBCREW_WORKSPACE_ROOT` | `./workspaces` | Every session works in a directory under this one |
| `BOBCREW_FILE_DOWNLOADS_ENABLED` | `false` | Whether clients may download files from a session's workspace |
| `BOB_PATH` | `bob` | Agent executable |
| `BOB_ARGUMENTS` | `acp,--trust,--accept-license,--disable-mcp,--disable-subagents` | Its arguments, comma separated |
| `BOB_API_KEY` | none | Passed to the agent as `BOB_API_KEY` and `BOBSHELL_API_KEY` |

Further properties: `bobcrew.max-sessions` (8), `bobcrew.request-timeout` (30s),
`bobcrew.prompt-timeout` (10m), `bobcrew.permission-timeout` (2m), `bobcrew.idle-timeout` (12h),
`bobcrew.event-buffer-size` (5000), `bobcrew.file-downloads-enabled` (false), `bobcrew.agent.inherit-environment` (false).

Notes on Bob:

- `--trust` is on by default because Bob refuses to create a session in a workspace it does not
  trust. `--auto-approve` is deliberately not on: tool calls ask for permission in the UI, and a
  request nobody answers within the permission timeout is rejected.
- Without an API key (and without a stored SSO login for the server's user) Bob answers
  "Authentication required" and the session is not created.
- The agent process gets a minimal environment (`HOME`, `LOGNAME`, `PATH`, `SHELL`, `TERM`, `USER`)
  plus the API key. Set `bobcrew.agent.inherit-environment=true` if your tools need more.

## Security

- **Transport**: the server speaks HTTPS only. The UI app always verifies the certificate and host
  name; `ca-file` adds a private CA.
- **Tokens**: HS256 JWTs with subject (the user name), expiry, issuer, audience and id. Issuing one
  needs the secret file, so it is done on the server machine with `issue-token`. To revoke a
  token, add its `jti` to the revocation file (it is re-read when it changes); to revoke all,
  replace the secret and restart.
- **Users and sessions**: the session records its creator as owner. For now every valid token can
  see and use every session; prompts and permission answers record who made them.
- **Workspaces**: a client names a directory relative to the workspace root. Absolute paths, `..`
  and symbolic links leading outside are refused before any process starts.
- **The API key** stays in the server's configuration. It is never part of a response or event.
- **The UI app** has no login, so it listens on `127.0.0.1` only and holds the token itself; the
  token never reaches the browser's JavaScript. It refuses requests whose `Host` is not the
  loopback interface, requests from other origins, and changes without its `X-BobCrew` header, so
  that another web page open in the same browser cannot use it.

## API

All under `/api`, with `Authorization: Bearer <token>`.

| Request | Does |
|---|---|
| `GET /me` | The token's user |
| `GET /workspaces` | Directories under the workspace root |
| `GET /sessions` | All sessions |
| `POST /sessions` `{"workspace": "dir", "title": "..."}` | Creates a session and starts its agent |
| `GET /sessions/{id}` | One session |
| `DELETE /sessions/{id}` | Closes it and ends its agent process |
| `GET /sessions/{id}/file?path=<path>` | Downloads a file from the session's workspace (refused with 403 if downloads are disabled) |
| `POST /sessions/{id}/prompt` `{"text": "..."}` | Starts a turn; progress arrives as events |
| `POST /sessions/{id}/cancel` | Stops the running turn |
| `POST /sessions/{id}/clear` | Starts the conversation over (a fresh agent session in the same agent process, earlier events forgotten) and deletes everything in the session's workspace; the session stays open. Only when idle; refused (409) while another open session uses that directory, nested or not, and (400) for the workspace root |
| `POST /sessions/{id}/mode` `{"modeId": "..."}` | Switches the agent's mode |
| `POST /sessions/{id}/permissions/{toolCallId}` `{"optionId": "..."}` | Answers a permission request |
| `GET /sessions/{id}/events?after=<seq>` | Server-Sent Events: the session's events after `seq`, then new ones |

Each event is `{"seq", "time", "type", "user", "data"}`. Types: `update` (`data` is the ACP
`session/update` as the agent sent it), `user_prompt`, `permission_request`,
`permission_resolved`, `turn_end`, `status`, `error`, `cleared` (the session was cleared: the server dropped all earlier events, and a client shows an empty transcript from here). The server keeps the last 5000 events of a
session, so a client can reconnect with `after` (or `Last-Event-ID`) and miss nothing.

## Not in this version

One process on one machine, by design. A later version can split it into workers (which own the
agent processes), stateless coordinators and a small metadata store; `SessionManager` is the seam
for that. Also not done yet: per-user API keys, cloning a git repository into a new workspace,
restricting sessions to their owner, resuming a session after a restart.

## License

Apache License 2.0, see [LICENSE](LICENSE).

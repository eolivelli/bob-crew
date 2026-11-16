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
one process serves one local client. bob-crew runs each session's `bob acp` in a Docker container of
its own and puts an HTTPS API in front of them.

```
laptop                                             server machine
browser --http 127.0.0.1--> [bobcrew-ui] --HTTPS + Bearer JWT--> [bobcrew-server] --Docker API--> container: bob acp (one per session)
          (no login)         pages + proxy                        REST + SSE API     attach stdin/stdout
```

Each container runs as a non-root user in `/opt/workspace`, sees no directory of the host and none of
its environment variables, and is removed when its session ends. The image carries every tool the
agent needs, so the server machine needs only Java and Docker. Optionally, and only where the server
allows it, a session can get a Docker Engine or virtual machines of its own
([capabilities](#capabilities-docker-and-virtual-machines)). By default containers have no resource
limits; sessions can [ask for some](#resources).
Users can upload files into a session's workspace, and a role can have a git repository cloned there
when the session starts.

| Module | What it is |
|---|---|
| `bobcrew-server` | The service. Starts the agent containers, exposes the API over TLS, checks a JWT on every request. |
| `bobcrew-ui` | A small app for your laptop. Serves the web pages on `127.0.0.1` and forwards API calls with your token. |
| `bobcrew-fake-agent` | A scripted ACP agent, for tests and for trying things without a Bob API key. |
| `docker/` | The session images: `bob-base` (the default), and `bob-java-python`, `bob-java` and `bob-power` (Docker and Vagrant/libvirt) built on it. |
| `ui.template/` | A starting `~/.bobcrew/` for the UI: `ui.properties` and two example roles, Developer and Power User. |

Sessions live in memory: restarting the server ends them and removes their containers.

## Build

Needs Java 21, Maven and Node.js 18+ (for the UI's script tests). The server tests run the fake
agent in real containers, so they need a Docker daemon; without one they are skipped. The first run
pulls `eclipse-temurin:21-jre-ubi9-minimal` to build the test image. Neither Bob nor an API key is
needed.

```
mvn install
```

## Session images

The server never pulls images: every image a session uses must already be on the server's Docker.

```
scripts/build-images.sh                    # bobcrew/bob-base, bob-java, bob-java-python and bob-power (all :latest)
scripts/build-images.sh --only java-python # just one (base, java, java-python or power)
scripts/build-images.sh --refresh-bob      # reinstall the latest Bob Shell, keep the other layers
scripts/push-images.sh [-i key] user@host bobcrew/bob-java:latest   # or --all for every bobcrew/* image
```

`push-images.sh` streams `docker save | gzip` over ssh into `docker load` on the other side, and skips
images whose ID is already there.

`bobcrew/bob-base` is Red Hat UBI 9 with Bob Shell (installed with the official installer), git, gh,
Node.js 22 and Linux Homebrew. Its entrypoint, `bobcrew-entrypoint`, is the contract with the server:

- the agent runs as uid 1000 (the server enforces `bobcrew.docker.user`) in `/opt/workspace`;
- standard input and output carry ACP, so nothing else may write to standard output;
- `/etc/bobcrew/profile.d/*.sh` are sourced before the agent starts: that is where an image adds
  `PATH` entries and tool settings (no login shell runs);
- the command is the server's `bobcrew.agent.command` (by default `bob acp ...`);
- in sessions with [capabilities](#capabilities-docker-and-virtual-machines) only, the container starts as
  root: `/etc/bobcrew/root.d/*.sh` run first, as root (to start daemons), then the entrypoint becomes the
  agent's user, and it refuses to run the agent as root;
- git and GitHub are set up from environment variables a role can pass: `GIT_USER_NAME` and
  `GIT_USER_EMAIL` become git's `user.name` and `user.email`, and `GH_TOKEN` (or `GITHUB_TOKEN`) is used
  by the gh CLI and stored in git's credential store for `https://github.com` (in `$HOME`, mode 600,
  never in the workspace).

`bobcrew/bob-java-python` ([`docker/bob-java-python`](docker/bob-java-python/Dockerfile)) is the
development template: JDK 21, Maven 3.9 and Python 3.11 (`python`, `python3` and `pip` are 3.11). It is
what the example Developer role uses. `bobcrew/bob-java` adds Gradle and Ant instead of Python.

For a stack of your own, copy one of them: build on `bobcrew/bob-base`, put PATH and tool settings in
a `profile.d` snippet, keep `USER bob` at the end, and name the image in a role.

`bobcrew/bob-power` ([`docker/bob-power`](docker/bob-power/Dockerfile)) is bob-java-python plus Docker
Engine (with compose and buildx) and Vagrant with the libvirt provider (QEMU/KVM), for
sessions with the [docker and libvirt capabilities](#capabilities-docker-and-virtual-machines). QEMU and libvirt are not in UBI 9,
so they come from CentOS Stream 9.

## Quick start

On the server machine:

```
# 1. TLS certificate (private CA) and JWT signing secret, written to ./certs
scripts/gen-dev-certs.sh certs my-server.example.com

# 2. A token for each user (run where the secret file is)
export BOBCREW_JWT_SECRET_FILE=certs/jwt.secret
java -jar bobcrew-server/target/bobcrew-server-0.1.0-SNAPSHOT.jar issue-token --user alice --ttl 30d

# 3. The session images (or copy them here with scripts/push-images.sh)
scripts/build-images.sh

# 4. Start the server (reads certs/server.crt and certs/server.key by default)
export BOB_API_KEY=...            # the key Bob uses, shared by all sessions
java -jar bobcrew-server/target/bobcrew-server-0.1.0-SNAPSHOT.jar
```

The server's user must be able to use Docker (for example, be in the `docker` group).

To try it without Bob, use the fake agent's test image (built by `mvn install` when Docker is there)
instead of setting `BOB_API_KEY`:

```
export BOBCREW_DEFAULT_IMAGE=bobcrew-fake-agent:test
export BOBCREW_AGENT_COMMAND=java,-jar,/opt/fake-agent.jar
```

The fake agent reacts to words in the prompt: `tool` (asks permission), `plan`, `slow` (runs until
stopped), `crash`, `whoami` (its user, uid and directory), `showenv NAME` (an environment variable),
`writefile` (writes `out/hello.txt`), `makelink` (a symbolic link `out/link` to `/etc/passwd`).

On your laptop, `scripts/init-ui-config.sh` prepares `~/.bobcrew/` from [`ui.template/`](ui.template/README.md)
(it never overwrites a file). With the token and a copy of `certs/ca.crt`, edit `~/.bobcrew/ui.properties`:

```
bobcrew.ui.server-url=https://my-server.example.com:8443
bobcrew.ui.token=<the token>
bobcrew.ui.ca-file=/path/to/ca.crt
```

Multiple servers can also be configured:

```
bobcrew.ui.servers.prod.url=https://prod.example.com:8443
bobcrew.ui.servers.prod.token=<token>
bobcrew.ui.servers.prod.ca-file=/path/to/prod-ca.crt

bobcrew.ui.servers.dev.url=https://dev.example.com:8443
bobcrew.ui.servers.dev.token-file=/path/to/dev.token
bobcrew.ui.servers.dev.ca-file=/path/to/dev-ca.crt
```

then start the UI and open <http://127.0.0.1:8642>:

```
java -jar bobcrew-ui/target/bobcrew-ui-0.1.0-SNAPSHOT.jar
```

## Deploying to another machine

From a checkout with the jars built (`mvn install -DskipTests`) and the images built
(`scripts/build-images.sh`), over ssh:

```
scripts/push-images.sh [-i key] user@host --all     # the session images (skips what is already there)
BOB_API_KEY=... scripts/deploy-remote.sh [-i key] user@host   # copy, certify and (re)start the server
scripts/connect-ui.sh [-i key] user@host             # run the UI here against it
scripts/deploy-remote.sh [-i key] user@host --stop   # stop it
```

The remote machine needs Java 21, openssl and Docker usable by that user; Bob itself lives in the
images. `deploy-remote.sh` starts the server with `scripts/start-server-wide.sh`, which runs Bob with
`--auto-approve`, MCP servers and subagents, and allows file downloads. Read its header before using it.

| Script | Does |
|---|---|
| `scripts/gen-dev-certs.sh` | Private CA, server certificate and JWT secret |
| `scripts/build-images.sh` | Builds the session images |
| `scripts/push-images.sh` | Copies images to another machine's Docker over ssh |
| `scripts/deploy-remote.sh`, `scripts/connect-ui.sh` | Run the server on another machine, and the UI here against it |
| `scripts/start-server-wide.sh` | Starts the server with permissive agent settings |
| `scripts/init-ui-config.sh` | Prepares `~/.bobcrew/` from `ui.template/` |

## Roles

A role is a named kind of session ("QA Tester", "Java developer", ...): a first prompt, the Docker image
to run, and the environment variables to give it. Roles exist only in the UI app: the server never sees
them. Each role is a Markdown manifest in `~/.bobcrew/roles/` (or the directory
in `bobcrew.ui.roles-dir`), next to `ui.properties`. The file name without `.md` is the role's id
(letters, digits, `.`, `_`, `-`; up to 100 characters).

```
---
name: QA Tester
description: Exercises the application and reports defects
image: bobcrew/bob-java:latest
env: GH_TOKEN, JIRA_TOKEN
---
You are a QA tester. Clone the repository, run the application, try to break it, and report each
defect with the steps to reproduce it.
```

[`ui.template/roles/developer.md`](ui.template/roles/developer.md) is a complete example: a Java and
Python developer in `bobcrew/bob-java-python`, given `GH_TOKEN`, `GIT_USER_NAME` and `GIT_USER_EMAIL`,
told to work per ticket with unit tests, the ticket id in commit messages, and a subagent review of each
patch before committing (which needs a server running Bob without `--disable-subagents`).

The front matter is optional. It understands `name` (default: the id), `description`, `image` (default:
the server's `bobcrew.docker.default-image`), `repository` and `branch` (below), `capabilities`
([Capabilities](#capabilities-docker-and-virtual-machines)), `memory` and `cpus` ([Resources](#resources)),
and `env`: names of
environment variables, separated by commas or spaces. When a session is created with the role, the UI app reads those variables from **its
own** environment (the laptop's, not the server's) and passes them to the session's container; the
values never go through the browser, and a variable that is not set is left out (the page says which).
The browser cannot choose an image or variables without a role. The rest of the file is the prompt. Files that are not `.md`, are symbolic links, are larger than 64 KiB, have an
empty prompt, or come after the first 200 are ignored (a warning is logged). The directory is read on every
request, so changes show up without restarting the UI.

A role can start every session with a repository checked out:

```
---
name: Cassandra developer
image: bobcrew/bob-java-python:latest
env: GH_TOKEN, GIT_USER_NAME, GIT_USER_EMAIL
repository: https://github.com/datastax/cassandra
branch: main
---
...
```

When the session is created, the server clones the repository into the workspace, at
`/opt/workspace/<name>` (`cassandra` here): a shallow clone (`--depth 1`) of that branch only, or of the
default branch when `branch` is left out. It runs `git` in the container as the session's user, through
the image's entrypoint, so with bob-base a `GH_TOKEN` among the role's variables also opens private
GitHub repositories. Session creation waits for the clone (up to `bobcrew.git.clone-timeout`, 10 minutes)
and fails, leaving nothing behind, if git does. The first prompt then carries a short note telling the
agent where the repository is and that it is shallow; the note is not shown as part of the user's text,
and it is left out after a **Clear** that deleted the workspace. Only `https://` URLs are accepted, never with
credentials in them.

A role can ask for [capabilities](#capabilities-docker-and-virtual-machines) (`capabilities: docker, libvirt`) and
[resources](#resources) (`memory: 16GB`, `cpus: 4`);
[`ui.template/roles/power-user.md`](ui.template/roles/power-user.md) is an example, with `bobcrew/bob-power`.

When creating a session, the **Role** list next to the title (shown when there are roles) can pick
one: once the session exists, the UI sends the role's prompt to it as the first prompt, and a blank title becomes
the role's name. The **Roles** button in the sidebar lists the roles and shows each manifest; it is read-only.

## Exporting a session

The **Export transcript** button in a session's header downloads the whole session as one
standalone HTML page, gzipped (`<title>-<date>.html.gz`; `gunzip` it, then open it). It is built in
the browser from the events the page has received, so it contains what the server still holds: the
last `bobcrew.event-buffer-size` events (5000 by default), and the page says so when the start of a
session is missing. The file has no scripts and carries a Content-Security-Policy that blocks all
network access.

## Uploading files

**Upload files** in a session's header picks one or more files from your computer and puts them in the
session's workspace, in `/opt/workspace` or a folder below it (created if needed); dropping files on the
conversation uploads them to `/opt/workspace` directly. A file of the same name is replaced. The files are
written by the session's own (non-root) user, so they land only where the agent itself could write, and
never through a symbolic link. Every upload shows in the conversation with who made it. The limit is
`bobcrew.max-upload-size` (100 MB) per file; `BOBCREW_FILE_UPLOADS_ENABLED=false` turns uploads off.

## Clearing a session and container metrics

**Clear** in a session's header starts the conversation over in the same container. It asks whether to
keep the workspace's files (useful when the agent has cloned a repository there) or delete them.

**Metrics** opens a small panel with the container's state, uptime, CPU, memory (used and limit),
process count, network and disk I/O. It is fetched only while the panel is open, every 5 seconds.

## Capabilities: Docker and virtual machines

Some work needs more than the agent's container: integration tests that start containers
(Testcontainers, `docker compose`), or virtual machines with Vagrant. A session can ask for two
capabilities, each allowed separately by the server:

| Capability | The agent gets |
|---|---|
| `docker` | A Docker Engine of its own, inside the session's container (`docker`, `docker compose`, `docker buildx`); not the server's Docker, so nothing it starts shows up there |
| `libvirt` | A libvirt daemon for QEMU/KVM virtual machines, which Vagrant's libvirt provider uses |

None is allowed unless the server says so, for example `BOBCREW_ALLOWED_CAPABILITIES=docker` or
`docker,libvirt`. Then each session must ask: the role's `capabilities: docker, libvirt`, or the
**Docker** and **Virtual machines** boxes under **Options** below the role list (each shown only when the
selected server allows it; a role's capabilities are ticked and locked). The image must have the tools:
`bobcrew/bob-power` has both. A session asking for one the server does not allow gets 403.

How it works: a session with any capability gets a privileged container, started as root, with
`BOBCREW_CAPABILITIES` listing what it asked for. `bobcrew-entrypoint` runs the image's
`/etc/bobcrew/root.d/*.sh` as root (bob-power's start `dockerd` for `docker` and `libvirtd` for `libvirt`,
only those asked for, logging to `/var/log/bobcrew/`), then runs the agent as `bobcrew.docker.user` with that
user's groups (`docker`, `kvm`, `libvirt` in bob-power); it refuses to start the agent as root. Docker's and
libvirt's data are in anonymous volumes, removed with the container, so everything the agent started goes
when the session is closed. A **Clear** that deletes the workspace runs as root here, since the agent's own
containers leave files owned by root. Nested containers and virtual machines need memory: ask for it (see
[Resources](#resources)).

Virtual machines need `/dev/kvm` on the server (hardware virtualisation; nested virtualisation when the
server is itself a VM). Vagrant's default provider is libvirt, synced folders use rsync (no NFS in the
container), and boxes must support libvirt (`generic/ubuntu2204`, `generic/rocky9`, ...). Images and boxes
are downloaded from the internet as usual. On hosts with libvirt installed, AppArmor confines
`/usr/sbin/libvirtd` even inside containers, so bob-power runs a copy from `/usr/local/libexec/bobcrew/`.

**This gives up the container's isolation.** Both capabilities need a privileged container, which can reach
the server's devices and kernel, and the agent's groups are as good as root in the container: whoever can use
such a session (any user of the server, for now) can become root on the server. Allow capabilities only on
a server dedicated to users you trust with that.

## Resources

By default a session's container has no memory, CPU or process limit. A session can ask for limits:
`memory` (such as `8GB` or `512MB`) and `cpus` (such as `2` or `0.5`), in its role's front matter, in the
**Memory** and **CPUs** fields under **Options** (filled from the role, and changeable), or in the API's
`resources`. The server can set defaults for sessions that ask for nothing (`BOBCREW_MEMORY_LIMIT`,
`BOBCREW_CPUS`) and caps on what they may ask for (`BOBCREW_MAX_MEMORY`, `BOBCREW_MAX_CPUS`; a session
asking for nothing then gets the cap). A request above a cap is refused with 400. `BOBCREW_PIDS_LIMIT` caps
the number of processes in every container. The session header shows what a session got.

## Server configuration

Set as environment variables, or as the Spring properties in
[`application.yml`](bobcrew-server/src/main/resources/application.yml).

| Variable | Default | Meaning |
|---|---|---|
| `BOBCREW_PORT` | `8443` | HTTPS port |
| `BOBCREW_TLS_CERT`, `BOBCREW_TLS_KEY` | `file:certs/server.crt`, `file:certs/server.key` | PEM certificate (chain) and private key |
| `BOBCREW_JWT_SECRET_FILE` | none, required | File with the HS256 secret, at least 32 bytes |
| `BOBCREW_REVOKED_JTI_FILE` | none | File listing revoked token ids, one per line |
| `BOBCREW_FILE_DOWNLOADS_ENABLED` | `false` | Whether clients may download files from a session's workspace |
| `BOBCREW_FILE_UPLOADS_ENABLED` | `true` | Whether clients may upload files into a session's workspace |
| `BOBCREW_MAX_UPLOAD_SIZE` | `100MB` | Largest file a client may upload |
| `BOBCREW_AGENT_COMMAND` | `bob,acp,--trust,--accept-license,--disable-mcp,--disable-subagents` | The command run in each container, comma separated |
| `BOB_API_KEY` | none | Passed to the container as `BOB_API_KEY` and `BOBSHELL_API_KEY` |
| `DOCKER_HOST` | `unix:///var/run/docker.sock` | The Docker daemon |
| `BOBCREW_DOCKER_API_VERSION` | `1.44` | Docker Engine API version (Docker 29 needs 1.44 or newer) |
| `BOBCREW_DEFAULT_IMAGE` | `bobcrew/bob-base:latest` | Image for sessions whose role names none |
| `BOBCREW_INSTANCE_ID` | `default` | Labels this server's containers; servers sharing a Docker daemon need different ids |
| `BOBCREW_ALLOWED_CAPABILITIES` | none | [Capabilities](#capabilities-docker-and-virtual-machines) sessions may ask for: `docker`, `libvirt`, comma separated (their users can become root on the server) |
| `BOBCREW_MEMORY_LIMIT`, `BOBCREW_CPUS` | `0` (no limit) | Limits for sessions that ask for none ([Resources](#resources)) |
| `BOBCREW_MAX_MEMORY`, `BOBCREW_MAX_CPUS` | `0` (no cap) | The most a session may ask for |
| `BOBCREW_PIDS_LIMIT` | `0` (no limit) | Most processes per container |

Further properties: `bobcrew.max-sessions` (8), `bobcrew.request-timeout` (30s),
`bobcrew.prompt-timeout` (10m), `bobcrew.permission-timeout` (2m), `bobcrew.idle-timeout` (12h),
`bobcrew.event-buffer-size` (5000), and per container `bobcrew.docker.user` (`1000:1000`, never root),
`bobcrew.docker.network` (`bridge`), `bobcrew.docker.stop-timeout` (5s), `bobcrew.docker.max-env-vars`
(32), `bobcrew.docker.max-env-value-length` (32768); for repositories, `bobcrew.git.clone-timeout` (10m) and
`bobcrew.git.allowed-schemes` (`https`).

**Containers.** Each session's container is created with the labels `bobcrew.managed=true`,
`bobcrew.instance=<instance id>`, `bobcrew.session` and `bobcrew.owner`. When the server starts, and when
it stops, it removes every container with its instance id, so a server killed with `kill -9` leaves
nothing behind once restarted. The containers get Docker's init process, no capabilities,
`no-new-privileges`, the resource limits that apply, and no mounts. Sessions with capabilities are the
exception, described above; their containers also carry `bobcrew.capabilities`.

Notes on Bob:

- `--trust` is on by default because Bob refuses to create a session in a workspace it does not
  trust. `--auto-approve` is deliberately not on: tool calls ask for permission in the UI, and a
  request nobody answers within the permission timeout is rejected.
- Without an API key Bob answers "Authentication required" and the session is not created.
- The container's environment is the image's, plus the API key, `BOBCREW_SESSION_ID` and what the
  session's role passes. Nothing comes from the server's own environment.

## Security

- **Transport**: the server speaks HTTPS only. The UI app always verifies the certificate and host
  name; `ca-file` adds a private CA.
- **Tokens**: HS256 JWTs with subject (the user name), expiry, issuer, audience and id. Issuing one
  needs the secret file, so it is done on the server machine with `issue-token`. To revoke a
  token, add its `jti` to the revocation file (it is re-read when it changes); to revoke all,
  replace the secret and restart.
- **Users and sessions**: the session records its creator as owner. For now every valid token can
  see and use every session; prompts and permission answers record who made them.
- **Isolation**: each session is a container with no host directories, no host environment, a
  non-root user, no capabilities and resource limits. The workspace, `/opt/workspace`, lives and dies
  with the container. Containers share Docker's default bridge network, as the agent needs to reach its
  model API. Sessions with [capabilities](#capabilities-docker-and-virtual-machines), off by default, give
  this up: their users can become root on the server.
- **Images**: never pulled. Whoever runs Docker on the server decides what can run; a client naming an
  image that is not there gets 400.
- **Environment variables**: a client may set at most 32, with plain names; `BOB_API_KEY`,
  `BOBSHELL_API_KEY`, `PATH`, `HOME`, `USER`, `LOGNAME`, `SHELL`, `HOSTNAME`, `BOBCREW_*` and `LD_*` are refused.
  Their values are never logged or returned (only the names are logged). Anyone who can use a session
  can ask its agent for them, so a role should pass only what every user of the server may see.
- **File downloads**: the path must stay inside `/opt/workspace`, and only regular files are served, so
  a symbolic link cannot lead elsewhere in the container.
- **File uploads**: the path must stay inside `/opt/workspace`; the file is written by the session's
  user (not by Docker as root), and a symbolic link or a directory at the target is refused.
- **Repositories**: `https://` only (the allowed schemes are a setting), no credentials, query or fragment in
  the URL, a plain branch name; both go to `git` as separate arguments after `--`, never through a shell.
  Like the image and variables, the page can only ask for a repository through a role.
- **The API key** stays in the server's configuration and the containers' environment. It is never part
  of a response or event.
- **The UI app** has no login, so it listens on `127.0.0.1` only and holds the token itself; the
  token never reaches the browser's JavaScript. It refuses requests whose `Host` is not the
  loopback interface, requests from other origins, and changes without its `X-BobCrew` header, so
  that another web page open in the same browser cannot use it.

## API

All under `/api`, with `Authorization: Bearer <token>`.

| Request | Does |
|---|---|
| `GET /me` | The token's user (`user`), the capabilities sessions may ask for (`capabilities`), and the caps on resources (`maxMemoryBytes`, `maxCpus`; null for none) |
| `GET /sessions` | All sessions |
| `POST /sessions` `{"title": "...", "image": "...", "env": {"NAME": "value"}, "repository": {"url": "https://...", "branch": "main"}, "capabilities": ["docker"], "resources": {"memory": "8GB", "cpus": 2}}` | Creates a session and starts its agent in a new container, after cloning `repository` (shallow; `branch` optional) into `/opt/workspace/<name>`; all fields optional (`image` defaults to the server's default image). 400 if the image is not on the server, a variable is refused, or the repository is refused or cannot be cloned, a capability is unknown or a resource request is invalid or above the server's cap; 403 for a capability the server does not allow |
| `GET /sessions/{id}` | One session: `id`, `owner`, `title`, `image`, `containerId`, `repository` (`url`, `branch`, `directory`, or null), `capabilities`, `resources` (`memoryBytes`, `cpus`; null for no limit), `status`, `agent`, `currentMode`, `modes`, `createdAt`, `updatedAt`, `lastSeq` |
| `DELETE /sessions/{id}` | Closes it and removes its container |
| `GET /sessions/{id}/file?path=<path>` | Downloads a regular file from the session's workspace; `path` is relative to `/opt/workspace` or absolute inside it, `file://` allowed (403 if downloads are disabled) |
| `PUT /sessions/{id}/files?path=<path>` (the file as the body) | Writes a file into the workspace, creating directories and replacing a file there; `path` as for downloads. Answers 201 `{"path", "size"}`; 400 for a path outside the workspace, a link or a directory, 413 if too large, 403 if uploads are disabled |
| `GET /sessions/{id}/metrics` | A snapshot of the container: `state`, `startedAt`, `uptimeSeconds`, `cpuPercent` (100 = one core), `memoryUsageBytes`, `memoryLimitBytes`, `pids`, `networkRxBytes`, `networkTxBytes`, `blockReadBytes`, `blockWriteBytes`. Docker samples for a second or two |
| `POST /sessions/{id}/prompt` `{"text": "..."}` | Starts a turn; progress arrives as events |
| `POST /sessions/{id}/cancel` | Stops the running turn |
| `POST /sessions/{id}/clear` `{"wipeWorkspace": true}` | Starts the conversation over (a fresh agent session in the same container, earlier events forgotten); the session stays open. Deletes everything in the workspace unless `wipeWorkspace` is `false` (the body is optional). Only when idle (409 otherwise) |
| `POST /sessions/{id}/mode` `{"modeId": "..."}` | Switches the agent's mode |
| `POST /sessions/{id}/permissions/{toolCallId}` `{"optionId": "..."}` | Answers a permission request |
| `GET /sessions/{id}/events?after=<seq>` | Server-Sent Events: the session's events after `seq`, then new ones |

The UI app answers two more paths itself, without contacting a server: `GET /api/roles` (`[{"id", "name",
"description", "image", "env", "repository", "branch", "capabilities", "memory", "cpus"}]`, `env` being names only) and `GET /api/roles/{id}` (adds
`prompt` and the raw `manifest`; 404 if there is no such role). It also rewrites `POST /api/sessions`: the page
sends `{"title", "role", "capabilities", "resources"}`, and the server receives the role's `image`, `env` and
`repository`, the role's capabilities with any the page adds, and the page's resources or else the role's (header
`X-BobCrew-Missing-Env` lists the variables that were not set). Uploads are streamed through it; other request
bodies are limited to 1 MB.

Each event is `{"seq", "time", "type", "user", "data"}`. Types: `update` (`data` is the ACP
`session/update` as the agent sent it), `user_prompt`, `permission_request`,
`permission_resolved`, `turn_end`, `status`, `error`, `cleared` (the session was cleared: the server dropped all earlier events, and a client shows an empty transcript from here; `data.workspaceWiped` says whether the files went too), `file_uploaded` (`user` uploaded `data.path`, `data.size` bytes), `repository_cloned` (`data.url`, `data.branch`, `data.path`). The server keeps the last 5000 events of a
session, so a client can reconnect with `after` (or `Last-Event-ID`) and miss nothing.

## Not in this version

One process on one machine, by design. A later version can split it into workers (which own the
agent containers), stateless coordinators and a small metadata store; `SessionManager` is the seam
for that. Also not done yet: per-user API keys, cloning over ssh, re-cloning after a wipe,
restricting sessions to their owner, resuming a session after a restart, workspaces that outlive their
session or are shared between sessions, pulling images, per-session network policies, metrics history,
capabilities without a privileged container (with a runtime such as Sysbox), capabilities allowed per user.

## License

Apache License 2.0, see [LICENSE](LICENSE).

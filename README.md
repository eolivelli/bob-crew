# bob-crew

[![CI](https://github.com/eolivelli/bob-crew/actions/workflows/ci.yml/badge.svg)](https://github.com/eolivelli/bob-crew/actions/workflows/ci.yml)

> [!WARNING]
> **For testing purposes only. Not meant to be used in production.**
>
> bob-crew is an experimental extension for IBM Bob. It has no database and keeps sessions in memory.
> Its Keycloak setup uses Keycloak's embedded database, which Keycloak does not support in production. The
> wide-permission scripts run agents with `--trust` and `--auto-approve`. Do not run it on a machine that holds
> data or credentials you cannot afford to expose, and do not put it on a public network.

Run many [IBM Bob Shell](https://bob.ibm.com/docs/shell/features/acp) sessions on a server and drive
them from a browser.

`bob acp` speaks the [Agent Client Protocol](https://agentclientprotocol.com) on stdin/stdout, so
one process serves one local client. bob-crew runs each session's `bob acp` in a Docker container of
its own and puts an HTTPS API in front of them.

```
laptop                                             server machine
browser --http 127.0.0.1--> [bobcrew-ui] --HTTPS + Bearer JWT--> [bobcrew-server] --Docker API--> container: bob acp (one per session)
   |                         pages + proxy                        REST + SSE API     attach stdin/stdout
   |                              |                                     |
   +------ log in (HTTPS) ---> [Keycloak] <------ tokens, keys ---------+
```

Users log in with [Keycloak](#users-and-login-keycloak), whose roles decide who may use a server, who is an
admin, and what each user may ask for; or, without Keycloak, they are given tokens made on the server
machine.

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
| `bobcrew-server` | The service. Starts the agent containers, exposes the API over TLS, checks a JWT (from Keycloak, or made with `issue-token`) on every request. |
| `bobcrew-ui` | A small app for your laptop. Serves the web pages on `127.0.0.1`, logs you in with Keycloak, and forwards API calls with your token. |
| `bobcrew-fake-agent` | A scripted ACP agent, for tests and for trying things without a Bob API key. |
| `bobcrew-test-support` | For tests only: a real Keycloak in Docker with the project's realm. |
| `keycloak/` | The Keycloak realm, `bobcrew-realm.json`: roles, the UI's client, the user attributes for limits. |
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

With `BOBCREW_REQUIRE_DOCKER=true`, a missing Docker daemon fails the tests instead of skipping them. GitHub
Actions runs the whole build on
every push to `main` and every pull request ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)).

## Session images

The server never pulls images: every image a session uses must already be on the server's Docker.

```
scripts/build-images.sh                    # bobcrew/bob-base, bob-java, bob-java-python and bob-power (all :latest)
scripts/build-images.sh --only java-python # just one (base, java, java-python or power)
scripts/build-images.sh --refresh-bob      # reinstall the latest Bob Shell, keep the other layers
scripts/push-images.sh [-i key] user@host bobcrew/bob-java:latest   # or --all for every bobcrew/* image
```

`push-images.sh` streams `docker save | gzip` over ssh into `docker load` on the other side, and skips
images whose ID is already there. It sends only the layers the other side lacks: the bottom layers an
image shares with an image already there are left out of the archive (`scripts/drop-layers.py`), so after
a rebuild only the changed top layers cross the network. That needs python3 locally and Docker's classic
image store on the other side; with the containerd image store there, whole images are sent
(`BOBCREW_PUSH_FULL=true` forces that anywhere).

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
- the session's secrets are files in `$BOBCREW_SECRETS_DIR` (`/run/bobcrew/secrets`), a tmpfs owned by the
  agent's user, mode 700, each file mode 400. The server writes them just after the container starts and then
  `.ready`; the entrypoint waits for that (up to a minute) before sourcing `profile.d`, so snippets can read
  them. They are never environment variables;
- git and GitHub are set up from secrets a role can pass: `GIT_USER_NAME` and `GIT_USER_EMAIL` become git's
  `user.name` and `user.email`; `GH_TOKEN` (or `GITHUB_TOKEN`) is read from its file when needed, by gh
  through the wrapper `/usr/local/bin/gh` and by git over `https://github.com` through the credential helper
  `git-credential-bobcrew`. It is never exported to the agent's environment nor copied to disk. (Variables
  of those names passed through the API's `env` still work.)

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
stopped), `background` (ends the turn, then keeps sending text until stopped), `crash`, `whoami` (its
user, uid and directory), `showenv NAME` (an environment variable),
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
| `scripts/deploy-keycloak.sh`, `scripts/keycloak-user.sh` | Run Keycloak on a machine, and manage its users |
| `scripts/start-server-wide.sh` | Starts the server with permissive agent settings |
| `scripts/init-ui-config.sh` | Prepares `~/.bobcrew/` from `ui.template/` |

## Users and login (Keycloak)

With [Keycloak](https://www.keycloak.org) as identity provider, users log in in the browser with their own
account, and an administrator manages who may do what in one place for all servers. Keycloak must be reachable
at one address by the users' browsers, their UIs and every server. It can run on a machine of its own or next
to a server; in production it is usually a service of its own already, and only the realm needs importing.

```
scripts/deploy-keycloak.sh [-i key] user@kc-host                       # Keycloak in Docker on kc-host:8543
scripts/keycloak-user.sh [-i key] user@kc-host add alice               # prints a temporary password, once
scripts/keycloak-user.sh [-i key] user@kc-host add bob --admin --cap docker --max-sessions 8 --max-memory 32GB
BOB_API_KEY=... scripts/deploy-remote.sh [-i key] user@host \
    --keycloak https://kc-host:8543/realms/bobcrew --keycloak-ca ~/.bobcrew/keycloak-ca.crt
scripts/connect-ui.sh [-i key] user@host \
    --keycloak https://kc-host:8543/realms/bobcrew --keycloak-ca ~/.bobcrew/keycloak-ca.crt
```

Then open <http://127.0.0.1:8642> and click **Log in**. Each person runs their own UI and logs in there; the
UI keeps the tokens in its memory (never in the browser), refreshes them, and uses the login for every
configured server without a token of its own. **Log out** ends the login at Keycloak too. A user given a
temporary password chooses their own at the first login.

`deploy-keycloak.sh` runs `quay.io/keycloak/keycloak` (pinned) as the container `bobcrew-keycloak`, over HTTPS
only, with a certificate from a private CA made on that machine (its `ca.crt` is copied to
`~/.bobcrew/keycloak-ca.crt`), or with yours (`BOBCREW_KEYCLOAK_CERTS`). Its data, users included, is on the
Docker volume `bobcrew-keycloak-data` in Keycloak's embedded database: fine for trying things, not supported
by Keycloak for production (back it up, or move to PostgreSQL). The realm is imported at the first start only,
so changes made in Keycloak survive redeploys. The admin console is at `https://kc-host:8543/admin` (user
`admin`; the password is in `~/bobcrew-keycloak/admin.password` on that machine, and is never printed). Read
the script's header for the settings.

The realm, [`keycloak/bobcrew-realm.json`](keycloak/bobcrew-realm.json), defines:

| In Keycloak | Means on bob-crew servers |
|---|---|
| role `bobcrew-user` | May use the servers at all; others in the realm get 403 |
| role `bobcrew-admin` | May see, use, share and close every session (as `BOBCREW_ADMIN_USERS`) |
| roles `bobcrew-cap-docker`, `bobcrew-cap-libvirt` | May ask for that [capability](#capabilities-docker-and-virtual-machines), where the server allows it too |
| attributes `bobcrew_max_sessions`, `bobcrew_max_memory`, `bobcrew_max_cpus` | The user's own limits, instead of each server's per-user ones (`BOBCREW_MAX_SESSIONS_PER_USER`, `BOBCREW_MAX_MEMORY`, `BOBCREW_MAX_CPUS`); memory and CPUs at most the server's ceilings (`BOBCREW_CEILING_MEMORY`, `BOBCREW_CEILING_CPUS`, by default the machine's) |
| client `bobcrew-ui` | The UIs: public (every user runs one, so it has no secret), authorization code with PKCE, callback `http://127.0.0.1/login/callback` on any port |

The user name on bob-crew is Keycloak's `preferred_username`: it owns sessions and is what sessions are shared
with. `keycloak-user.sh` adds, updates, shows, disables, enables and deletes users, makes new temporary
passwords, and lists users with their roles; the admin console does the same and more.

Tokens made with `issue-token` keep working next to Keycloak's, for scripts and for servers without Keycloak.
They name their user in `sub`, get every capability the server allows and its limits, and are admins only
through `BOBCREW_ADMIN_USERS`. Both kinds share one name space: a token for `alice` acts as Keycloak's
`alice`, so make tokens only for Keycloak users' names or for names that are not in Keycloak.

Without the scripts: give the server `BOBCREW_OIDC_ISSUER_URI` (and `BOBCREW_OIDC_CA_FILE` for a private CA), and
the UI `bobcrew.ui.oidc.issuer-uri` (and `bobcrew.ui.oidc.ca-file`) in `~/.bobcrew/ui.properties` with no
`token` or `token-file`; a server with a token of its own keeps using it.

## Roles

A role is a named kind of session ("QA Tester", "Java developer", ...): a first prompt, the Docker image
to run, and the secrets to give it. Roles exist only in the UI app: the server never sees
them. Each role is a Markdown manifest in `~/.bobcrew/roles/` (or the directory
in `bobcrew.ui.roles-dir`), next to `ui.properties`. The file name without `.md` is the role's id
(letters, digits, `.`, `_`, `-`; up to 100 characters).

```
---
name: QA Tester
description: Exercises the application and reports defects
image: bobcrew/bob-java:latest
secrets: GH_TOKEN, JIRA_TOKEN
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
and `secrets`: names of
secrets, separated by commas or spaces (`env`, the older name, still works the same way). When a session is
created with the role, the UI app reads their values from `~/.bobcrew/secrets.json` (or
`bobcrew.ui.secrets-file`) on the machine it runs on, a JSON object such as
`{"GH_TOKEN": "github_pat_...", "JIRA_TOKEN": "..."}`, and passes them to the session, where each becomes a
file in `/run/bobcrew/secrets` and the first prompt tells the agent which are there. Never the UI's own
environment: a session gets what you put in that file for sessions, nothing that merely happens to be exported
in your shell. The file must be readable by you only (`chmod 600`), or the UI refuses to create sessions whose
role names secrets; it is read for every new session. The values never go through the browser, and a secret
the file does not have is left out (the page says which). The browser cannot choose an image or secrets
without a role. The rest of the file is the prompt. Files that are not `.md`, are symbolic links, are larger than 64 KiB, have an
empty prompt, or come after the first 200 are ignored (a warning is logged). The directory is read on every
request, so changes show up without restarting the UI.

Files in `~/.bobcrew/user-profile/` (or `bobcrew.ui.user-profile-dir`) on the UI machine are automatically
copied into the container user's home directory (`/home/bob/`) when a session is created (mode `0600`, or `0700`
if executable). This allows passing custom dotfiles or tool configurations (such as `.m2/settings.xml` or `.gitconfig`).

A role can start every session with a repository checked out:

```
---
name: Cassandra developer
image: bobcrew/bob-java-python:latest
secrets: GH_TOKEN, GIT_USER_NAME, GIT_USER_EMAIL
repository: https://github.com/datastax/cassandra
branch: main
---
...
```

When the session is created, the server clones the repository into the workspace, at
`/opt/workspace/<name>` (`cassandra` here): a shallow clone (`--depth 1`) of that branch only, or of the
default branch when `branch` is left out. It runs `git` in the container as the session's user, through
the image's entrypoint, so with bob-base a `GH_TOKEN` among the role's secrets also opens private
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
`bobcrew/bob-power` has both. A session asking for one the server does not allow gets 403. With Keycloak, a
user also needs the capability's role (`bobcrew-cap-docker`, `bobcrew-cap-libvirt`): the server's setting is
the most anyone gets there.

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
such a session (its owner, the users it is shared with, admins) can become root on the server. Allow
capabilities only on a server dedicated to users you trust with that, and with Keycloak give their roles only
to those users.

## Resources

By default a session's container may use 8GB of memory and 4096 processes, with no CPU limit. A session can
ask for its own limits:
`memory` (such as `8GB` or `512MB`) and `cpus` (such as `2` or `0.5`), in its role's front matter, in the
**Memory** and **CPUs** fields under **Options** (filled from the role, and changeable), or in the API's
`resources`. The server can set defaults for sessions that ask for nothing (`BOBCREW_MEMORY_LIMIT`,
`BOBCREW_CPUS`) and caps on what they may ask for (`BOBCREW_MAX_MEMORY`, `BOBCREW_MAX_CPUS`; a session
asking for nothing then gets the cap). A request above a cap is refused with 400; with no cap a session may
ask for more than the default. `BOBCREW_PIDS_LIMIT` caps the number of processes in every container. Set a
default to `0` to remove it, at the risk of one session taking the whole machine down. The session header shows what a session got.

With Keycloak, a user's attributes `bobcrew_max_memory` and `bobcrew_max_cpus` replace the caps for that user
(higher or lower), and `bobcrew_max_sessions` replaces `BOBCREW_MAX_SESSIONS_PER_USER`. The caps from attributes
are never above the server's ceilings, `BOBCREW_CEILING_MEMORY` and `BOBCREW_CEILING_CPUS` (by default the Docker
host's memory and CPUs). A default above a user's cap is lowered to it.

## Server configuration

Set as environment variables, or as the Spring properties in
[`application.yml`](bobcrew-server/src/main/resources/application.yml).

| Variable | Default | Meaning |
|---|---|---|
| `BOBCREW_PORT` | `8443` | HTTPS port |
| `BOBCREW_TLS_CERT`, `BOBCREW_TLS_KEY` | `file:certs/server.crt`, `file:certs/server.key` | PEM certificate (chain) and private key |
| `BOBCREW_OIDC_ISSUER_URI` | none | The Keycloak realm whose tokens are accepted, exactly as its tokens' issuer (`https://kc-host:8543/realms/bobcrew`); the server fetches its keys from there |
| `BOBCREW_OIDC_CA_FILE` | none | PEM certificates to trust for Keycloak's HTTPS instead of the JDK's (a private CA) |
| `BOBCREW_OIDC_USERNAME_CLAIM` | `preferred_username` | The claim with the user name |
| `BOBCREW_OIDC_ACCESS_ROLE`, `BOBCREW_OIDC_ADMIN_ROLE` | `bobcrew-user`, `bobcrew-admin` | The realm roles that let a user in (empty: everyone the realm accepts), and that make an admin |
| `BOBCREW_JWT_SECRET_FILE` | none | File with the HS256 secret (at least 32 bytes) of the tokens made with `issue-token`; without it, those are refused. This or `BOBCREW_OIDC_ISSUER_URI` is required |
| `BOBCREW_REVOKED_JTI_FILE` | none | File listing revoked token ids, one per line |
| `BOBCREW_FILE_DOWNLOADS_ENABLED` | `false` | Whether clients may download files from a session's workspace |
| `BOBCREW_FILE_UPLOADS_ENABLED` | `true` | Whether clients may upload files into a session's workspace |
| `BOBCREW_MAX_UPLOAD_SIZE` | `100MB` | Largest file a client may upload |
| `BOBCREW_AGENT_COMMAND` | `bob,acp,--trust,--accept-license,--disable-mcp,--disable-subagents` | The command run in each container, comma separated |
| `BOB_API_KEY` | none | Passed to the container as `BOB_API_KEY` and `BOBSHELL_API_KEY` |
| `DOCKER_HOST` | `unix:///var/run/docker.sock` | The Docker daemon |
| `BOBCREW_DOCKER_API_VERSION` | `1.44` | Docker Engine API version (Docker 29 needs 1.44 or newer) |
| `BOBCREW_DEFAULT_IMAGE` | `bobcrew/bob-base:latest` | Image for sessions whose role names none |
| `BOBCREW_ALLOWED_IMAGES` | `bobcrew/*` | Images a session may name, comma separated; `*` matches anything. The default image is always allowed |
| `BOBCREW_DOCKER_NETWORK` | `bobcrew` | The sessions' Docker network, created if missing ([Security](#security)) |
| `BOBCREW_ADMIN_USERS` | none | Users (user names, comma separated) who may see, use, share and close every session, besides those with the admin role |
| `BOBCREW_MAX_SESSIONS_PER_USER` | `4` | Most open sessions one user may have (`0`: only `bobcrew.max-sessions` applies) |
| `BOBCREW_INSTANCE_ID` | `default` | Labels this server's containers; servers sharing a Docker daemon need different ids |
| `BOBCREW_ALLOWED_CAPABILITIES` | none | [Capabilities](#capabilities-docker-and-virtual-machines) sessions may ask for: `docker`, `libvirt`, comma separated (their users can become root on the server) |
| `BOBCREW_MEMORY_LIMIT`, `BOBCREW_CPUS` | `8GB`, `0` (no limit) | Limits for sessions that ask for none ([Resources](#resources)) |
| `BOBCREW_MAX_MEMORY`, `BOBCREW_MAX_CPUS` | `0` (no cap) | The most a session may ask for |
| `BOBCREW_CEILING_MEMORY`, `BOBCREW_CEILING_CPUS` | the Docker host's | The most a user's own limits from Keycloak can give a session |
| `BOBCREW_PIDS_LIMIT` | `4096` | Most processes per container (`0`: no limit) |

Further properties: `bobcrew.max-sessions` (8), `bobcrew.request-timeout` (30s),
`bobcrew.prompt-timeout` (10m), `bobcrew.permission-timeout` (2m), `bobcrew.idle-timeout` (12h),
`bobcrew.event-buffer-size` (5000), and per container `bobcrew.docker.user` (`1000:1000`, never root),
`bobcrew.docker.stop-timeout` (5s), `bobcrew.docker.max-env-vars`
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
- The container's environment is the image's, plus the API key, `BOBCREW_SESSION_ID`, `BOBCREW_SECRETS_DIR`
  and the API's `env` (which the UI never sends). Secrets are files, not variables. Nothing comes from the
  server's own environment.

## Security

- **Transport**: the server speaks HTTPS only. The UI app always verifies the certificate and host
  name; `ca-file` adds a private CA.
- **Tokens**: the server accepts tokens from two issuers, each when configured, and a token's issuer
  decides how it is checked (an HS256 token claiming Keycloak's issuer is refused).
  - Keycloak's: RS256, checked with the realm's published keys (fetched on first use, so the server starts
    while Keycloak is down; rotated keys are picked up), expiry, issuer, and audience `bobcrew-api` (which the
    realm's `bobcrew-ui` client adds; tokens Keycloak makes for other clients are refused). They last 5
    minutes; the UI refreshes them while Keycloak's session lasts (2 hours idle, 10 at most). Disabling a
    user in Keycloak stops them within those 5 minutes.
  - `issue-token`'s: HS256 JWTs with subject (the user name), expiry, issuer, audience and id. Issuing one
    needs the secret file, so it is done on the server machine. To revoke a token, add its `jti` to the
    revocation file (it is re-read when it changes); to revoke all, replace the secret and restart.
- **Authorization**: with Keycloak, a user without the `bobcrew-user` role gets 403 for everything; roles also
  make admins and allow capabilities, and attributes set limits ([Users and login](#users-and-login-keycloak)).
  Roles can only narrow what the server allows (`BOBCREW_ALLOWED_CAPABILITIES`, the ceilings).
- **Users and sessions**: a session belongs to the user who created it, and nobody else can see it (it
  answers 404) until its owner shares it with them by name (**Share** in the UI, `PUT
  /sessions/{id}/sharing` in the API). Users it is shared with can do everything with it except close it and
  change who it is shared with; that includes prompting the agent, answering its permission requests and
  having it reveal the secrets the session was given, so share only with people who may have those. When a
  sharing is withdrawn, that user's event stream ends at once. Users named in `BOBCREW_ADMIN_USERS` may do
  everything with every session. Prompts, permission answers and sharing changes record who made them.
- **Isolation**: each session is a container with no host directories, no host environment, a
  non-root user, no capabilities and resource limits. The workspace, `/opt/workspace`, lives and dies
  with the container. Sessions can open outgoing connections (the agent needs to reach its model API) but not
  one another: they join their own network (`bobcrew` by default), which the server creates at startup with
  inter-container traffic off (`com.docker.network.bridge.enable_icc=false`). Docker's built-in `bridge`,
  `host` and `none` networks are refused; a network that exists already is used as it is, with a warning in
  the log if containers on it can reach one another. Containers can still reach the Docker host and the
  networks it can reach, cloud metadata services included; block those on the host, for example
  `iptables -I DOCKER-USER -i br-$(docker network inspect -f '{{.Id}}' bobcrew | cut -c1-12) -d 169.254.169.254 -j DROP`,
  and the same for private address ranges the sessions have no business reaching. Sessions with [capabilities](#capabilities-docker-and-virtual-machines), off by default, give
  this up: their users can become root on the server.
- **Images**: never pulled, and limited to `BOBCREW_ALLOWED_IMAGES` (by default `bobcrew/*`, plus the default
  image). The server checks this itself, since anyone with a token can call the API without the UI's roles: a
  client naming an image not on the list gets 403, one that is on it but not on the Docker host gets 400.
- **Quotas**: at most `bobcrew.max-sessions` open sessions in all and `BOBCREW_MAX_SESSIONS_PER_USER` per
  user (429 beyond either), each with the memory and process limits of [Resources](#resources), so that one
  user or one runaway session cannot take the machine from everyone else.
- **Environment variables**: a client may set at most 32, with plain names; `BOB_API_KEY`,
  `BOBSHELL_API_KEY`, `PATH`, `HOME`, `USER`, `LOGNAME`, `SHELL`, `HOSTNAME`, `BOBCREW_*` and `LD_*` are refused.
  Their values are never logged or returned (only the names are logged). The UI does not use them.
- **Secrets**: up to 32 (`[A-Za-z_][A-Za-z0-9_]*` names, values up to 32768 characters), written after the
  container starts into the tmpfs `/run/bobcrew/secrets`, one file each, mode 400 and owned by the session's
  user, through an exec's standard input (never a command line). They are not in the container's environment,
  so they do not show in `docker inspect`, in `/proc/*/environ`, or in what the agent's tools inherit, and the
  server forgets the values once written; they live in memory and go with the container. Only their names are
  logged and returned (`secrets` in the session's details). This does not hide them from the agent, which can
  read the files: anyone who can use a session (its owner, the users it is shared with, admins) can ask the
  agent for them. The UI's values come from `~/.bobcrew/secrets.json`, never from its environment.
- **File downloads**: the path must stay inside `/opt/workspace`, and only regular files are served, so
  a symbolic link cannot lead elsewhere in the container.
- **File uploads**: the path must stay inside `/opt/workspace`; the file is written by the session's
  user (not by Docker as root), and a symbolic link or a directory at the target is refused.
- **Repositories**: `https://` only (the allowed schemes are a setting), no credentials, query or fragment in
  the URL, a plain branch name; both go to `git` as separate arguments after `--`, never through a shell.
  Like the image and variables, the page can only ask for a repository through a role.
- **The API key** stays in the server's configuration and the containers' environment. It is never part
  of a response or event.
- **The UI app** acts with its user's token (configured, or from their Keycloak login), so it listens on
  `127.0.0.1` only and holds the token itself; no token reaches the browser's JavaScript. It refuses requests
  whose `Host` is not the loopback interface, requests from other origins, and changes without its
  `X-BobCrew` header, so that another web page open in the same browser cannot use it. The one exception is
  Keycloak sending the browser back to `/login/callback`, which only completes a login this UI started, once
  (`state`, PKCE). It is a public client: anyone who can use the machine's loopback interface while the user
  is logged in acts as them, as with a token file. It warns at startup if a token file can be read by other
  users of the machine.

## API

All under `/api`, with `Authorization: Bearer <token>`: 401 without a valid token, 403 for every request of a
Keycloak user without the access role.

| Request | Does |
|---|---|
| `GET /me` | The token's user (`user`), whether they are an admin (`admin`), where the token is from (`issuer`: `oidc` or `token`), the capabilities their sessions may ask for (`capabilities`), how many sessions they may have open (`maxSessions`; 0 for no limit of their own) and their caps on resources (`maxMemoryBytes`, `maxCpus`; null for none) |
| `GET /sessions` | The sessions the caller may use: their own, those shared with them (all of them for an admin) |
| `POST /sessions` `{"title": "...", "image": "...", "env": {"NAME": "value"}, "secrets": {"NAME": "value"}, "repository": {"url": "https://...", "branch": "main"}, "capabilities": ["docker"], "resources": {"memory": "8GB", "cpus": 2}}` | Creates a session and starts its agent in a new container, after cloning `repository` (shallow; `branch` optional) into `/opt/workspace/<name>`; all fields optional (`image` defaults to the server's default image). `secrets` become files in `/run/bobcrew/secrets`, `env` environment variables. 400 if the image is not on the server, a variable or secret is refused, or the repository is refused or cannot be cloned, a capability is unknown or a resource request is invalid or above the user's cap; 403 for a capability the user may not use |
| `GET /sessions/{id}` | One session: `id`, `owner`, `sharedWith`, `title`, `image`, `containerId`, `secrets` (names), `repository` (`url`, `branch`, `directory`, or null), `capabilities`, `resources` (`memoryBytes`, `cpus`; null for no limit), `status`, `agent`, `currentMode`, `modes`, `createdAt`, `updatedAt`, `lastSeq` |
| `DELETE /sessions/{id}` | Closes it and removes its container. Owner or admin only (403 for users it is shared with) |
| `PUT /sessions/{id}/sharing` `{"users": ["bob", "carol"]}` | Shares the session with exactly these users (`[]`: nobody else), and records a `shared` event. Owner or admin only (403 for users it is shared with); at most 50 names |
| `GET /sessions/{id}/file?path=<path>` | Downloads a regular file from the session's workspace; `path` is relative to `/opt/workspace` or absolute inside it, `file://` allowed (403 if downloads are disabled) |
| `PUT /sessions/{id}/files?path=<path>` (the file as the body) | Writes a file into the workspace, creating directories and replacing a file there; `path` as for downloads. Answers 201 `{"path", "size"}`; 400 for a path outside the workspace, a link or a directory, 413 if too large, 403 if uploads are disabled |
| `GET /sessions/{id}/metrics` | A snapshot of the container: `state`, `startedAt`, `uptimeSeconds`, `cpuPercent` (100 = one core), `memoryUsageBytes`, `memoryLimitBytes`, `pids`, `networkRxBytes`, `networkTxBytes`, `blockReadBytes`, `blockWriteBytes`. Docker samples for a second or two |
| `POST /sessions/{id}/prompt` `{"text": "..."}` | Starts a turn; progress arrives as events |
| `POST /sessions/{id}/cancel` | Stops the running turn. When idle it still sends the agent `session/cancel`, for work it started that outlives the turn (Bob can end a turn while a command it started goes on) |
| `POST /sessions/{id}/clear` `{"wipeWorkspace": true}` | Starts the conversation over (a fresh agent session in the same container, earlier events forgotten); the session stays open. Deletes everything in the workspace unless `wipeWorkspace` is `false` (the body is optional). Only when idle (409 otherwise) |
| `POST /sessions/{id}/mode` `{"modeId": "..."}` | Switches the agent's mode |
| `POST /sessions/{id}/permissions` `{"toolCallId": "...", "optionId": "..."}` | Answers a permission request (the tool call id is in the body because agents' ids can contain `/`) |
| `GET /sessions/{id}/events?after=<seq>` | Server-Sent Events: the session's events after `seq`, then new ones |

The UI app answers some paths itself, without contacting a server: `GET /api/login` (`{"enabled", "loggedIn"}`:
whether it logs its user in with Keycloak, and whether someone is), `/login`, `/login/callback` and `/logout`
(the login's redirects), and `GET /api/roles` (`[{"id", "name",
"description", "image", "secrets", "repository", "branch", "capabilities", "memory", "cpus"}]`, `secrets` being names only) and `GET /api/roles/{id}` (adds
`prompt` and the raw `manifest`; 404 if there is no such role). It also rewrites `POST /api/sessions`: the page
sends `{"title", "role", "capabilities", "resources"}`, and the server receives the role's `image`, `secrets` (values from
`~/.bobcrew/secrets.json`), `userProfile` (files from `~/.bobcrew/user-profile/`) and `repository`, the role's capabilities with any the page adds, and the page's resources or else the role's (header
`X-BobCrew-Missing-Secrets` lists the secrets the file does not have; the page may not send `image`, `env`,
`secrets` or `repository` itself). Uploads are streamed through it; other request
bodies are limited to 2 MB.

Each event is `{"seq", "time", "type", "user", "data"}`. Types: `update` (`data` is the ACP
`session/update` as the agent sent it), `user_prompt`, `permission_request`,
`permission_resolved`, `turn_end`, `status`, `error`, `cleared` (the session was cleared: the server dropped all earlier events, and a client shows an empty transcript from here; `data.workspaceWiped` says whether the files went too), `file_uploaded` (`user` uploaded `data.path`, `data.size` bytes), `repository_cloned` (`data.url`, `data.branch`, `data.path`), `shared` (`user` shared the session with
`data.users`, the whole new list). The server keeps the last 5000 events of a
session, so a client can reconnect with `after` (or `Last-Event-ID`) and miss nothing.

## Not in this version

One process on one machine, by design. A later version can split it into workers (which own the
agent containers), stateless coordinators and a small metadata store; `SessionManager` is the seam
for that. Also not done yet: per-user API keys, cloning over ssh, re-cloning after a wipe,
resuming a session after a restart, workspaces that outlive their
session or are shared between sessions, pulling images, per-session network policies, metrics history,
capabilities without a privileged container (with a runtime such as Sysbox), a UI shared by many users (each
runs their own), Keycloak with PostgreSQL in the scripts.

## License

Apache License 2.0, see [LICENSE](LICENSE).

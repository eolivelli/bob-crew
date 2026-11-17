---
name: deploy-bobcrew-server
description: Use when the user wants to install, deploy, redeploy, restart or stop the bob-crew server on a remote machine or VM (for example "install the server side on the new VM", "deploy to <host>"), or asks how to connect the UI to it.
---

# Deploy the bob-crew server to a target machine

Always use the repository's scripts. Do not hand-copy jars or write your own start script: a manual
deploy ends up with a different layout (and listens on all interfaces), and `connect-ui.sh` then fails.

- `scripts/deploy-remote.sh [-i key] user@host [--stop]` copies the server, creates certs and a JWT
  secret on the remote, and (re)starts it with nohup under `~/bobcrew` (server.log, server.pid,
  server.env, certs/). It stops a running server first.
- `scripts/push-images.sh [-i key] user@host image... | --all` copies session images to the remote
  Docker over ssh (the server never pulls images). Build them first with `scripts/build-images.sh`.
- `scripts/connect-ui.sh [-i key] user@host` runs the UI locally against it.
- With Keycloak (users log in instead of being given tokens): `scripts/deploy-keycloak.sh [-i key] user@kc-host`
  runs it in Docker (port 8543), `scripts/keycloak-user.sh [-i key] user@kc-host add <name> [--admin]
  [--cap docker]` adds users, and `deploy-remote.sh` / `connect-ui.sh` take
  `--keycloak https://<kc-host>:8543/realms/bobcrew --keycloak-ca ~/.bobcrew/keycloak-ca.crt`.

## 1. Find the target

1. Ask the user for the target if not given. For lab VMs, coordinates are in `.lab/clusterinfo.txt`
   (gitignored; it changes with every new cluster, so re-read it): `ssh user`, `ssh key path`,
   `public hostname`, `private ip`. `.lab/REMOTE_WORKER_SETUP.md` describes provisioning the machine.
2. Check what the machine already has before installing anything:
   `ssh -i <key> user@host 'java -version; which openssl; docker info --format {{.ServerVersion}}'`.
   Needs Java 21+, `openssl`, and Docker usable by that user (in the `docker` group). Bob itself is not
   needed on the machine: it is in the session images.
   If something is missing, follow `.lab/REMOTE_WORKER_SETUP.md` (it is a checklist; run the steps
   non-interactively; gh login and the API key are manual).
3. Never print tokens, `BOB_API_KEY`, `jwt.secret`, `server.env`, Keycloak's `admin.password` or
   `keycloak.env`. Redact when showing config files. `keycloak-user.sh add` and `password` print a temporary
   password once, for the user it is for: hand it over, do not repeat it elsewhere.

## 2. Build and deploy

1. Build with JDK 21: `source ~/.sdkman/bin/sdkman-init.sh; sdk env; mvn -q install -DskipTests`.
   Build the images if they are missing locally (`docker image ls 'bobcrew/*'`):
   `scripts/build-images.sh`, then copy them: `scripts/push-images.sh -i <key> <user>@<host> --all`
   (it skips images the remote already has; the first copy is about 1 GB per image, compressed).
2. `BOB_API_KEY` is taken from the local environment and written to the remote `server.env` (mode 600).
   Check that it is set locally (`${BOB_API_KEY:+yes}`, without echoing it). If not, tell the user.
   An existing remote `server.env` is kept when it is not set.
3. The script makes the server listen on one address only, the host you give. Cloud VMs (EC2) have
   only their private address on the interface, so pass it:
   `BOBCREW_REMOTE_BIND=<private ip> scripts/deploy-remote.sh -i <key> <user>@<public hostname>`
   Use the public hostname as `host` so the certificate matches what the UI connects to.
4. The script uses the wider agent settings of `start-server-wide.sh` (`--auto-approve`, `--trust`).
   If the user has not already chosen this, confirm it before deploying
   (`ask_followup_question`): tool calls will then not ask for permission in the UI.
5. If an older manual deployment exists in `~/bobcrew`, stop it first (`kill $(cat server.pid)`) and
   move the directory away; the script owns `~/bobcrew`.

## 3. Verify

1. `ss -ltn | grep 8443` on the remote shows the private address, not `*`.
2. From here: `scp` the remote `bobcrew/certs/ca.crt` to /tmp, then
   `curl --cacert /tmp/ca.crt https://<public hostname>:8443/api/me`. Expect HTTP 401 with TLS verified
   (`ssl_verify_result=0`). A timeout means the port is closed in the security group: tell the user,
   and recommend opening it to their IP only, never to the internet.
3. Optional end-to-end check: `connect-ui.sh` prints a token internally; for a quick API check issue
   one on the remote with `issue-token --user <name> --ttl 1d`, `POST /api/sessions` with `{}`,
   `POST .../prompt`, read `.../events`, `GET .../metrics`, `DELETE` it. `docker ps --filter
   label=bobcrew.managed` on the remote shows the session's container while it is open.

## 3b. Keycloak (when users should log in)

1. Ask whether Keycloak runs already (then only `keycloak/bobcrew-realm.json` needs importing there) or
   should run on one of the machines: `scripts/deploy-keycloak.sh -i <key> <user>@<public hostname>` (with
   `BOBCREW_KEYCLOAK_BIND=<private ip>` on cloud VMs, as for the server). Port 8543 must be reachable from the
   users' browsers, their UIs and every server: the same address for all of them.
2. Add users: `scripts/keycloak-user.sh -i <key> <user>@<host> add <name> [--admin] [--cap docker]`. List
   them with `list`.
3. Deploy servers with `--keycloak <issuer> --keycloak-ca ~/.bobcrew/keycloak-ca.crt` (the issuer is
   printed by deploy-keycloak.sh). Check: `curl --cacert ~/.bobcrew/keycloak-ca.crt
   <issuer>/.well-known/openid-configuration` answers from here.

## 4. Connect the UI

Tell the user to run (foreground, Ctrl-C stops it; open http://127.0.0.1:8642):

`scripts/connect-ui.sh -i <key> <user>@<public hostname>`, plus
`--keycloak <issuer> --keycloak-ca ~/.bobcrew/keycloak-ca.crt` when users log in with Keycloak (then
**Log in** on the page).

To stop the remote server: `scripts/deploy-remote.sh -i <key> <user>@<host> --stop`.

## Gotchas

- `pgrep -f bobcrew-server` over ssh matches the ssh command itself; use `pgrep -af "java.*bobcrew-server"`
  and filter `pgrep`, or check `server.pid`.
- The server does not survive a reboot or cluster TTL expiry; redeploy with this skill.
- `POST /api/sessions` answers 400 when the image is not on the remote Docker: push it.
- Agents do not see the remote machine's environment. Secrets such as `GH_TOKEN` come from
  `~/.bobcrew/secrets.json` on the machine running the UI, named in the role's `secrets:`.
- Containers left by a server killed with -9 are removed when it starts again (label `bobcrew.instance`).

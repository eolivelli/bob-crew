---
name: deploy-bobcrew-server
description: Use when the user wants to install, deploy, redeploy, restart or stop the bob-crew server on a remote machine or VM (for example "install the server side on the new VM", "deploy to <host>"), or asks how to connect the UI to it.
---

# Deploy the bob-crew server to a target machine

Always use the repository's scripts. Do not hand-copy jars or write your own start script: a manual
deploy ends up with a different layout (and listens on all interfaces), and `connect-ui.sh` then fails.

- `scripts/deploy-remote.sh [-i key] user@host [--stop]` copies the server, creates certs and a JWT
  secret on the remote, and (re)starts it with nohup under `~/bobcrew` (server.log, server.pid,
  server.env, certs/, workspaces/). It stops a running server first.
- `scripts/connect-ui.sh [-i key] user@host` runs the UI locally against it.

## 1. Find the target

1. Ask the user for the target if not given. For lab VMs, coordinates are in `.lab/clusterinfo.txt`
   (gitignored; it changes with every new cluster, so re-read it): `ssh user`, `ssh key path`,
   `public hostname`, `private ip`. `.lab/REMOTE_WORKER_SETUP.md` describes provisioning the machine.
2. Check what the machine already has before installing anything:
   `ssh -i <key> user@host 'java -version; which bob openssl'`. Needs Java 21+, `openssl`, `bob`.
   If something is missing, follow `.lab/REMOTE_WORKER_SETUP.md` (it is a checklist; run the steps
   non-interactively; gh login and the API key are manual).
3. Never print tokens, `BOB_API_KEY`, `jwt.secret` or `server.env`. Redact when showing config files.

## 2. Build and deploy

1. Build with JDK 21: `source ~/.sdkman/bin/sdkman-init.sh; sdk env; mvn -q install -DskipTests`.
2. `BOB_API_KEY` is taken from the local environment and written to the remote `server.env` (mode 600).
   Check that it is set locally (`${BOB_API_KEY:+yes}`, without echoing it). If not, tell the user.
   An existing remote `server.env` is kept when it is not set.
3. The script makes the server listen on one address only, the host you give. Cloud VMs (EC2) have
   only their private address on the interface, so pass it:
   `BOBCREW_REMOTE_BIND=<private ip> scripts/deploy-remote.sh -i <key> <user>@<public hostname>`
   Use the public hostname as `host` so the certificate matches what the UI connects to.
4. The script uses the wider agent settings of `start-server-wide.sh` (`--auto-approve`, `--trust`,
   inherited environment). If the user has not already chosen this, confirm it before deploying
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
   one on the remote with `issue-token --user <name> --ttl 1d`, create a workspace directory under
   `~/bobcrew/workspaces`, `POST /api/sessions`, `POST .../prompt`, read `.../events`, `DELETE` it.

## 4. Connect the UI

Tell the user to run (foreground, Ctrl-C stops it; open http://127.0.0.1:8642):

`scripts/connect-ui.sh -i <key> <user>@<public hostname>`

To stop the remote server: `scripts/deploy-remote.sh -i <key> <user>@<host> --stop`.

## Gotchas

- `pgrep -f bobcrew-server` over ssh matches the ssh command itself; use `pgrep -af "java.*bobcrew-server"`
  and filter `pgrep`, or check `server.pid`.
- The server does not survive a reboot or cluster TTL expiry; redeploy with this skill.
- Workspaces must exist before creating a session (`POST /api/sessions` answers 400 otherwise).
- The agent inherits the environment of a remote login shell (`~/.profile`), not `~/.bashrc`.

# UI configuration template

A starting point for `~/.bobcrew/`, the configuration directory of the user who runs the bob-crew UI:

```
ui.template/
  ui.properties        -> ~/.bobcrew/ui.properties   server URL, token file or Keycloak login, CA
  secrets.json         -> ~/.bobcrew/secrets.json    the secrets roles pass to sessions (empty, mode 600)
  (none)               -> ~/.bobcrew/user-profile/   files copied to /home/bob/ in sessions (created empty, mode 700)
  roles/developer.md   -> ~/.bobcrew/roles/developer.md
  roles/power-user.md  -> ~/.bobcrew/roles/power-user.md
```

Copy it with `scripts/init-ui-config.sh`, which never overwrites a file that already exists, then edit
`~/.bobcrew/ui.properties` and put the token and the server's `ca.crt` where it says; or, where users log in
with Keycloak, set `bobcrew.ui.oidc.issuer-uri` there instead of a token.

## The Developer role

`roles/developer.md` runs sessions in `bobcrew/bob-java-python:latest` (JDK 21, Maven 3.9, Python
3.11; see `docker/bob-java-python`) and passes three **secrets** from `~/.bobcrew/secrets.json` to the
session, where each becomes a file in `/run/bobcrew/secrets` (a tmpfs readable by the session's user only,
never an environment variable):

| Secret | Used for |
|---|---|
| `GH_TOKEN` | The gh CLI, and git over `https://github.com`, both reading the file when they need it |
| `GIT_USER_NAME`, `GIT_USER_EMAIL` | git's `user.name` and `user.email` |

Put them in `~/.bobcrew/secrets.json`, which must stay readable by you only (`chmod 600`; the UI refuses
it otherwise):

```
{
  "GIT_USER_NAME": "Ada Lovelace",
  "GIT_USER_EMAIL": "ada@example.com",
  "GH_TOKEN": "github_pat_..."
}
```

The file is read each time a session is created, so edits apply at once. A secret the file does not have
is left out, and the page says so. Nothing comes from the UI's own environment. Anyone who can use a
session (you, the users you share it with, admins) can ask its agent for these values, so use tokens with
the narrowest scope that does the job.

One name in that file is special: `BOB_API_KEY`, your own Bob API key. When it is there, every session you
create runs Bob with it instead of the server's key, whatever the role (no role needs to list it). It is not
a file in the session: the server puts it in the agent's environment, where Bob reads it. A new key applies
to the sessions you create afterwards, not to running ones.

Use an expiring fine-grained token for `GH_TOKEN`. When it is about to expire, make a new one on GitHub, put
it in `secrets.json`, and click **Refresh secrets in my sessions** in the sidebar (or **Refresh secrets** in
one session): the running sessions get the new value without being restarted.

Its prompt asks the agent to work per ticket, on a branch, with unit tests for every change, a full
build before committing, a **review of each patch by a subagent** before committing, the ticket id in
every commit message, and pushes or pull requests only after you confirm.

The subagent review needs a server that runs Bob with subagents enabled: the default agent command has
`--disable-subagents`; `scripts/start-server-wide.sh` (and `deploy-remote.sh`) leave it out.

The image must be on the server's Docker: `scripts/build-images.sh --only java-python`, then
`scripts/push-images.sh user@host bobcrew/bob-java-python:latest` if the server is elsewhere.

Copy the role to make others: change `name`, `image`, `secrets` and the prompt. To start each session with a
repository checked out, add `repository: https://github.com/<owner>/<repo>` and optionally `branch: <name>`
to the front matter: the server clones it (shallow) into `/opt/workspace/<repo>` before the agent starts, and
tells the agent so in the first prompt. With a `GH_TOKEN` secret, private GitHub repositories work too. See "Roles" in the main
README for the manifest format.

## User profile (`~/.bobcrew/user-profile/`)

Regular files in `~/.bobcrew/user-profile/` (such as `.m2/settings.xml`, `.gitconfig`, or scripts in `bin/`) are
copied into `/home/bob/` in every new session before the agent starts, keeping their paths (mode 600, or 700 for
executables). The directory must stay readable by you only (`chmod 700`; the UI refuses to create sessions
otherwise). At most 64 files, 256 KiB each, 1 MiB in all. They are not secrets: they stay on the container's
disk, and anyone who can use the session can read them. See "User profile files" in the main README.

## The Power User role

`roles/power-user.md` is the Developer with Docker and virtual machines: it runs in
`bobcrew/bob-power:latest` (`scripts/build-images.sh --only power`) with `capabilities: docker, libvirt`, so
its agent can run `docker`, `docker compose` and `vagrant up` (libvirt/KVM) inside the session, and asks for
16GB of memory and 4 CPUs. The server must allow both capabilities (`BOBCREW_ALLOWED_CAPABILITIES=docker,libvirt`),
and for virtual machines it needs `/dev/kvm`. Drop `libvirt` from the list for a Docker-only role. Whoever uses
such a session can become root on the server: see "Capabilities" in the main README before allowing them.

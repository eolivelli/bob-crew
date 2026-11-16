# UI configuration template

A starting point for `~/.bobcrew/`, the configuration directory of the user who runs the bob-crew UI:

```
ui.template/
  ui.properties        -> ~/.bobcrew/ui.properties   server URL, token file, CA
  roles/developer.md   -> ~/.bobcrew/roles/developer.md
```

Copy it with `scripts/init-ui-config.sh`, which never overwrites a file that already exists, then edit
`~/.bobcrew/ui.properties` and put the token and the server's `ca.crt` where it says.

## The Developer role

`roles/developer.md` runs sessions in `bobcrew/bob-java-python:latest` (JDK 21, Maven 3.9, Python
3.11; see `docker/bob-java-python`) and passes three environment variables **from the UI's environment**
to the session's container:

| Variable | Used for |
|---|---|
| `GH_TOKEN` | The gh CLI, and git over `https://github.com` (written to git's credential store in the container) |
| `GIT_USER_NAME`, `GIT_USER_EMAIL` | git's `user.name` and `user.email` |

Set them in the shell that starts the UI, for example in `~/.profile`:

```
export GIT_USER_NAME="Ada Lovelace"
export GIT_USER_EMAIL=ada@example.com
export GH_TOKEN=$(gh auth token)      # or a fine-grained personal access token
```

A variable that is not set is left out, and the page says so when the session is created. Anyone who
can use a session can ask its agent for these values, so use tokens with the narrowest scope that does
the job, on a server whose users you trust.

Its prompt asks the agent to work per ticket, on a branch, with unit tests for every change, a full
build before committing, a **review of each patch by a subagent** before committing, the ticket id in
every commit message, and pushes or pull requests only after you confirm.

The subagent review needs a server that runs Bob with subagents enabled: the default agent command has
`--disable-subagents`; `scripts/start-server-wide.sh` (and `deploy-remote.sh`) leave it out.

The image must be on the server's Docker: `scripts/build-images.sh --only java-python`, then
`scripts/push-images.sh user@host bobcrew/bob-java-python:latest` if the server is elsewhere.

Copy the role to make others: change `name`, `image`, `env` and the prompt. To start each session with a
repository checked out, add `repository: https://github.com/<owner>/<repo>` and optionally `branch: <name>`
to the front matter: the server clones it (shallow) into `/opt/workspace/<repo>` before the agent starts, and
tells the agent so in the first prompt. With `GH_TOKEN` set, private GitHub repositories work too. See "Roles" in the main
README for the manifest format.

# Sourced by bobcrew-entrypoint: git and GitHub set up from environment variables that a role passes
# (role front matter: `env: GIT_USER_NAME, GIT_USER_EMAIL, GH_TOKEN`). Nothing is printed: stdout
# carries ACP.
#
#   GIT_USER_NAME, GIT_USER_EMAIL   git's user.name and user.email
#   GH_TOKEN (or GITHUB_TOKEN)      used by the gh CLI as is, and stored for git over
#                                   https://github.com in git's credential store
#
# Git's configuration and the credential store live in $HOME, never in the workspace: files there can
# be downloaded by any user of the session.

case "$HOME" in
  /opt/workspace|/opt/workspace/*)
    export GIT_CONFIG_GLOBAL=/tmp/.bobcrew-gitconfig
    bobcrew_credentials=/tmp/.bobcrew-git-credentials
    ;;
  *)
    bobcrew_credentials="$HOME/.git-credentials"
    ;;
esac

if command -v git >/dev/null 2>&1; then
  [ -n "${GIT_USER_NAME:-}" ] && git config --global user.name "$GIT_USER_NAME"
  [ -n "${GIT_USER_EMAIL:-}" ] && git config --global user.email "$GIT_USER_EMAIL"
  git config --global init.defaultBranch main

  bobcrew_token="${GH_TOKEN:-${GITHUB_TOKEN:-}}"
  if [ -n "$bobcrew_token" ]; then
    # gh reads GH_TOKEN itself; give it the token also when only GITHUB_TOKEN was passed
    export GH_TOKEN="$bobcrew_token"
    git config --global credential.helper "store --file=$bobcrew_credentials"
    (
      umask 077
      printf 'https://x-access-token:%s@github.com\n' "$bobcrew_token" > "$bobcrew_credentials"
    )
  fi
  unset bobcrew_token
fi
unset bobcrew_credentials

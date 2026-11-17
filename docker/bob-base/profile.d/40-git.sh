# Sourced by bobcrew-entrypoint: git and GitHub set up from the session's secrets (files in
# $BOBCREW_SECRETS_DIR, from the role's `secrets: GIT_USER_NAME, GIT_USER_EMAIL, GH_TOKEN` and the UI's
# ~/.bobcrew/secrets.json), or from environment variables of the same names for sessions created through the
# API with env. Nothing is printed: stdout carries ACP.
#
#   GIT_USER_NAME, GIT_USER_EMAIL   git's user.name and user.email
#   GH_TOKEN (or GITHUB_TOKEN)      used by gh (through /usr/local/bin/gh) and by git over https://github.com
#                                   (through git-credential-bobcrew), both reading it when they need it: it is
#                                   never exported to the agent's environment nor written to disk
#
# Git's configuration lives in $HOME, never in the workspace: files there can be downloaded by any user of
# the session.

case "$HOME" in
  /opt/workspace|/opt/workspace/*)
    export GIT_CONFIG_GLOBAL=/tmp/.bobcrew-gitconfig
    ;;
esac

# a secret's value from its file, if the session has it
bobcrew_secret() {
  if [ -r "${BOBCREW_SECRETS_DIR:-/run/bobcrew/secrets}/$1" ]; then
    cat "${BOBCREW_SECRETS_DIR:-/run/bobcrew/secrets}/$1"
  fi
}

if command -v git >/dev/null 2>&1; then
  bobcrew_value="$(bobcrew_secret GIT_USER_NAME)"
  [ -n "$bobcrew_value" ] || bobcrew_value="${GIT_USER_NAME:-}"
  [ -n "$bobcrew_value" ] && git config --global user.name "$bobcrew_value"
  bobcrew_value="$(bobcrew_secret GIT_USER_EMAIL)"
  [ -n "$bobcrew_value" ] || bobcrew_value="${GIT_USER_EMAIL:-}"
  [ -n "$bobcrew_value" ] && git config --global user.email "$bobcrew_value"
  git config --global init.defaultBranch main
  git config --global credential.https://github.com.helper bobcrew
  unset bobcrew_value
fi
unset -f bobcrew_secret

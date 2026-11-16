#!/bin/sh
# Entrypoint of every bob-crew session container: the contract between an image and the bob-crew server.
#
# The server starts the container with the agent command as arguments (by default
# `bob acp --trust ...`), a non-root uid:gid, the working directory /opt/workspace, and an environment
# made only of the API key, BOBCREW_SESSION_ID and the variables the client passed. It then speaks ACP
# (newline-delimited JSON-RPC) on the container's standard input and output.
#
# So this script must:
#   - write nothing to standard output (it carries ACP); diagnostics go to standard error;
#   - make sure HOME and the workspace are usable by whatever uid the server chose;
#   - source /etc/bobcrew/profile.d/*.sh, where images add PATH and tool settings
#     (JAVA_HOME, Homebrew, ...), since no login shell runs;
#   - exec the command, so the agent gets signals and its end ends the container.
set -eu

WORKSPACE=/opt/workspace

if [ -z "${HOME:-}" ] || [ ! -w "${HOME}" ]; then
  if [ -d /home/bob ] && [ -w /home/bob ]; then
    HOME=/home/bob
  else
    HOME="${WORKSPACE}"
  fi
  export HOME
fi

if [ ! -d "${WORKSPACE}" ] || [ ! -w "${WORKSPACE}" ]; then
  echo "bobcrew-entrypoint: ${WORKSPACE} is missing or not writable by uid $(id -u)" >&2
  exit 70
fi
cd "${WORKSPACE}"

if [ -d /etc/bobcrew/profile.d ]; then
  for snippet in /etc/bobcrew/profile.d/*.sh; do
    [ -r "${snippet}" ] || continue
    # shellcheck disable=SC1090
    . "${snippet}" >&2
  done
fi

if [ "$#" -eq 0 ]; then
  echo "bobcrew-entrypoint: no command given" >&2
  exit 64
fi

exec "$@"

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
#
# Sessions with capabilities (bobcrew.allowed-capabilities on the server, asked for when the session is
# created) start the container as root, privileged, with BOBCREW_AGENT_USER=uid:gid and
# BOBCREW_CAPABILITIES=docker,libvirt (those asked for). Then, and only then, this script runs
# /etc/bobcrew/root.d/*.sh as root (images such as bob-power start dockerd or libvirtd there, as the
# capabilities say), and runs itself again as BOBCREW_AGENT_USER with that user's groups: the agent never
# runs as root.
set -eu

WORKSPACE=/opt/workspace

if [ "$(id -u)" = "0" ]; then
  agent_user="${BOBCREW_AGENT_USER:-}"
  case "${agent_user%%:*}" in
    ''|0|root)
      echo "bobcrew-entrypoint: refusing to run the agent as root (BOBCREW_AGENT_USER is '${agent_user}')" >&2
      exit 77
      ;;
  esac
  if [ -d /etc/bobcrew/root.d ]; then
    for snippet in /etc/bobcrew/root.d/*.sh; do
      [ -r "${snippet}" ] || continue
      # in a subshell, with root's HOME, so that nothing it does leaks into the agent's environment
      # shellcheck disable=SC1090
      ( HOME=/root; . "${snippet}" ) >&2 || {
        echo "bobcrew-entrypoint: ${snippet} failed" >&2
        exit 71
      }
    done
  fi
  unset BOBCREW_AGENT_USER
  agent_uid="${agent_user%%:*}"
  agent_gid="${agent_user#*:}"
  # the user's groups (docker, kvm, libvirt in bob-power) when the image knows the user, none otherwise
  groups="--clear-groups"
  getent passwd "${agent_uid}" >/dev/null 2>&1 && groups="--init-groups"
  exec setpriv --reuid "${agent_uid}" --regid "${agent_gid}" "${groups}" -- "$0" "$@"
fi

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

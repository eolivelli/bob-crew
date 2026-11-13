# Shared by deploy-remote.sh and connect-ui.sh; source it, do not run it.
#
# Settings (environment):
#   BOBCREW_REMOTE_DIR   directory on the remote machine, relative to its home   (default: bobcrew)
#   BOBCREW_REMOTE_PORT  port the remote server listens on, on its loopback only (default: 8443)

REMOTE_DIR="${BOBCREW_REMOTE_DIR:-bobcrew}"
REMOTE_PORT="${BOBCREW_REMOTE_PORT:-8443}"

die() {
  echo "error: $*" >&2
  exit 1
}

# parse_target "$@": fills IDENTITY, TARGET (user@host) and the leftover words in EXTRA.
parse_target() {
  IDENTITY=""
  TARGET=""
  EXTRA=()
  while [[ $# -gt 0 ]]; do
    case "$1" in
      -i) [[ $# -ge 2 ]] || die "-i needs a file"; IDENTITY="$2"; shift 2 ;;
      -h|--help) usage; exit 0 ;;
      --*) EXTRA+=("$1"); shift ;;
      *) [[ -z "$TARGET" ]] || die "unexpected argument: $1"; TARGET="$1"; shift ;;
    esac
  done
  [[ -n "$TARGET" ]] || { usage >&2; exit 1; }
  [[ "$TARGET" == *@* ]] || die "the target must look like user@host"
  [[ "$TARGET" =~ ^[A-Za-z0-9._@:-]+$ ]] || die "unexpected characters in the target"

  SSH_OPTS=(-o StrictHostKeyChecking=accept-new
            -o ControlMaster=auto -o ControlPersist=5m
            -o "ControlPath=${TMPDIR:-/tmp}/bobcrew-ssh-$(id -u)-%C")
  if [[ -n "$IDENTITY" ]]; then
    [[ -f "$IDENTITY" ]] || die "identity file not found: $IDENTITY"
    SSH_OPTS+=(-i "$IDENTITY" -o IdentitiesOnly=yes)
  fi
}

rssh() { ssh "${SSH_OPTS[@]}" "$TARGET" "$@"; }
rscp() { scp -q "${SSH_OPTS[@]}" "$@"; }

# Closes the shared connection opened by the first ssh call.
close_ssh() { ssh "${SSH_OPTS[@]}" -O exit "$TARGET" 2>/dev/null || true; }

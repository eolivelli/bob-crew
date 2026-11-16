#!/usr/bin/env bash
# Copies Docker images from this machine to a remote machine's Docker over ssh, for a bob-crew server
# there (it never pulls images, so every image a role names must be present on its Docker host).
#
#   scripts/push-images.sh [-i identity-file] user@host image... | --all
#
# --all copies every local bobcrew/* image. An image whose ID is already the same on the remote side
# is skipped. The rest are streamed as `docker save | gzip | ssh ... 'gunzip | docker load'`, so
# nothing is written to disk on either side. `docker save` has no idea what the remote already has,
# so a derived image carries its base layers along too; docker load then skips the layers it has.
#
# The remote user must be able to run docker (member of the docker group, or rootless Docker).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=remote-common.sh
source "$here/remote-common.sh"

usage() {
  echo "usage: scripts/push-images.sh [-i identity-file] user@host image... | --all" >&2
}

# the first plain word is the target, the others are images
args=()
target=""
images=()
all=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    -i) [[ $# -ge 2 ]] || die "-i needs a file"; args+=("$1" "$2"); shift 2 ;;
    -h|--help) usage; exit 0 ;;
    --all) all=true; shift ;;
    -*) die "unknown option: $1" ;;
    *) if [[ -z "$target" ]]; then target="$1"; else images+=("$1"); fi; shift ;;
  esac
done
args+=("$target")
parse_target "${args[@]}"
command -v docker >/dev/null || die "docker is not installed here"

if $all; then
  [[ ${#images[@]} -eq 0 ]] || die "give images or --all, not both"
  mapfile -t images < <(docker image ls --filter reference='bobcrew/*' --format '{{.Repository}}:{{.Tag}}' | grep -v '<none>' | sort -u)
fi
[[ ${#images[@]} -gt 0 ]] || { usage; exit 1; }
for image in "${images[@]}"; do
  [[ "$image" =~ ^[A-Za-z0-9][A-Za-z0-9._/:@-]{0,254}$ ]] || die "unexpected characters in image name: $image"
  docker image inspect "$image" >/dev/null 2>&1 || die "no local image $image (build it with scripts/build-images.sh)"
done

trap close_ssh EXIT
rssh 'docker version --format "remote docker {{.Server.Version}}"' \
  || die "cannot run docker on $TARGET; is the user in the docker group?"

for image in "${images[@]}"; do
  local_id=$(docker image inspect -f '{{.Id}}' "$image")
  remote_id=$(rssh "docker image inspect -f '{{.Id}}' '$image' 2>/dev/null" || true)
  if [[ "$remote_id" == "$local_id" ]]; then
    echo "$image: already up to date on $TARGET"
    continue
  fi
  size=$(docker image inspect -f '{{.Size}}' "$image")
  echo "$image: sending ($((size / 1024 / 1024)) MB uncompressed)..."
  docker save "$image" | gzip -1 | rssh 'gunzip | docker load'
  remote_id=$(rssh "docker image inspect -f '{{.Id}}' '$image'")
  [[ "$remote_id" == "$local_id" ]] || die "$image: the remote image ID ($remote_id) does not match ($local_id)"
  echo "$image: done"
done

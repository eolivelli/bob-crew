#!/usr/bin/env bash
# Copies Docker images from this machine to a remote machine's Docker over ssh, for a bob-crew server
# there (it never pulls images, so every image a role names must be present on its Docker host).
#
#   scripts/push-images.sh [-i identity-file] user@host image... | --all
#
# --all copies every local bobcrew/* image. An image whose ID is already the same on the remote side
# is skipped. The rest are streamed as `docker save | drop-layers.py | gzip | ssh ... 'gunzip | docker load'`,
# so nothing is written to disk on either side. Only the layers the remote lacks are sent: the remote
# lists the layers of its images, and the longest run of bottom layers it has in common with the image
# (the same layers in the same order, which is what Docker can reuse) is left out of the archive.
# After a rebuild that changes only the top layers, only those cross the network.
#
# Leaving layers out relies on docker load in Docker's classic image store (overlay2 and other graph
# drivers). If the remote uses the containerd image store, whole images are sent, as with docker save.
# BOBCREW_PUSH_FULL=true sends whole images in any case.
#
# Needs python3 here. The remote user must be able to run docker (member of the docker group, or
# rootless Docker).
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
command -v python3 >/dev/null || die "python3 is not installed here"

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

incremental=true
if [[ "${BOBCREW_PUSH_FULL:-false}" == true ]]; then
  incremental=false
elif rssh 'docker info --format "{{json .DriverStatus}}"' | grep -q 'io.containerd.snapshotter'; then
  echo "the remote uses the containerd image store: sending whole images"
  incremental=false
fi

# remote_layers: one line per remote image, its layers' diff IDs bottom first, separated by spaces.
remote_layers() {
  rssh 'docker image ls -q --no-trunc | sort -u | xargs -r docker image inspect -f "{{range .RootFS.Layers}}{{.}} {{end}}"'
}

# common_layers image: the diff IDs of the image's bottom layers that some remote image has too, in order.
common_layers() {
  local -a mine theirs
  local line best=0 n
  read -ra mine <<< "$(docker image inspect -f '{{range .RootFS.Layers}}{{.}} {{end}}' "$1")"
  while IFS= read -r line; do
    read -ra theirs <<< "$line"
    n=0
    while [[ $n -lt ${#mine[@]} && $n -lt ${#theirs[@]} && "${mine[$n]}" == "${theirs[$n]}" ]]; do
      n=$((n + 1))
    done
    [[ $n -le $best ]] || best=$n
  done <<< "$REMOTE_LAYERS"
  [[ $best -eq 0 ]] || printf '%s\n' "${mine[@]:0:$best}"
}

for image in "${images[@]}"; do
  local_id=$(docker image inspect -f '{{.Id}}' "$image")
  remote_id=$(rssh "docker image inspect -f '{{.Id}}' '$image' 2>/dev/null" || true)
  if [[ "$remote_id" == "$local_id" ]]; then
    echo "$image: already up to date on $TARGET"
    continue
  fi
  size=$(docker image inspect -f '{{.Size}}' "$image")
  skip=()
  if $incremental; then
    # read again for every image, so that each one can reuse the layers of those sent before it
    REMOTE_LAYERS=$(remote_layers)
    mapfile -t skip < <(common_layers "$image")
  fi
  echo "$image: sending ($((size / 1024 / 1024)) MB uncompressed, ${#skip[@]} bottom layers already on $TARGET)..."
  docker save "$image" | python3 "$here/drop-layers.py" "${skip[@]}" | gzip -1 | rssh 'gunzip | docker load'
  remote_id=$(rssh "docker image inspect -f '{{.Id}}' '$image'")
  [[ "$remote_id" == "$local_id" ]] || die "$image: the remote image ID ($remote_id) does not match ($local_id)"
  echo "$image: done"
done

#!/usr/bin/env bash
# Builds the bob-crew session images on this machine's Docker:
#
#   bobcrew/bob-base:latest   Bob Shell, git, gh, Node.js and Linux Homebrew on Red Hat UBI 9
#   bobcrew/bob-java:latest   bob-base plus JDK 21, Maven, Gradle and Ant (an example of a derived image)
#   bobcrew/bob-java-python:latest   bob-base plus JDK 21, Maven 3.9 and Python 3.11 (development template)
#
#   scripts/build-images.sh [--only base|java|java-python] [--refresh-bob] [--bob-version X.Y.Z] [--tag TAG]
#
# --refresh-bob reinstalls Bob Shell (the latest release, or --bob-version) without rebuilding the
# other layers. The server never pulls images: build them where the server runs, or build them here
# and copy them over with scripts/push-images.sh.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(dirname "$here")"

only=""
tag="latest"
build_args=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --only) [[ $# -ge 2 ]] || { echo "--only needs base, java or java-python" >&2; exit 1; }; only="$2"; shift 2 ;;
    --refresh-bob) build_args+=(--build-arg "BOB_REFRESH=$(date +%s)"); shift ;;
    --bob-version) [[ $# -ge 2 ]] || { echo "--bob-version needs a version" >&2; exit 1; }
                   build_args+=(--build-arg "BOB_VERSION=$2"); shift 2 ;;
    --tag) [[ $# -ge 2 ]] || { echo "--tag needs a value" >&2; exit 1; }; tag="$2"; shift 2 ;;
    -h|--help) sed -n '2,14p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 1 ;;
  esac
done
case "$only" in ""|base|java|java-python) ;; *) echo "--only needs base, java or java-python" >&2; exit 1 ;; esac
[[ "$tag" =~ ^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$ ]] || { echo "invalid tag: $tag" >&2; exit 1; }
command -v docker >/dev/null || { echo "docker is not installed" >&2; exit 1; }

if [[ -z "$only" || "$only" == "base" ]]; then
  echo "== building bobcrew/bob-base:$tag"
  docker build "${build_args[@]}" -t "bobcrew/bob-base:$tag" "$root/docker/bob-base"
fi
if [[ -z "$only" || "$only" == "java" ]]; then
  echo "== building bobcrew/bob-java:$tag"
  docker build --build-arg "BASE_IMAGE=bobcrew/bob-base:$tag" -t "bobcrew/bob-java:$tag" "$root/docker/bob-java"
fi
if [[ -z "$only" || "$only" == "java-python" ]]; then
  echo "== building bobcrew/bob-java-python:$tag"
  docker build --build-arg "BASE_IMAGE=bobcrew/bob-base:$tag" -t "bobcrew/bob-java-python:$tag" \
    "$root/docker/bob-java-python"
fi
docker image ls --filter reference='bobcrew/*'

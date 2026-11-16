#!/usr/bin/env bash
# Starts bobcrew-server with permissive agent settings, for a trusted single-user machine:
#
#   - --auto-approve: tool calls run without asking for permission
#   - --trust: Bob works in any workspace
#   - MCP servers and subagents are enabled
#   - clients may download files from session workspaces
#
# Each session still runs in its own container, with no host directories and only the environment
# variables its role passes (see the README). Docker must be running and the images present
# (scripts/build-images.sh, or scripts/push-images.sh from another machine).
#
# The shipped defaults are stricter; this script only overrides them through environment variables.
# Other variables you export beforehand (BOB_API_KEY, BOBCREW_PORT, ...) are kept.
#
#   scripts/start-server-wide.sh
set -euo pipefail

cd "$(dirname "$0")/.."

jar=$(ls bobcrew-server/target/bobcrew-server-*-SNAPSHOT.jar 2>/dev/null | head -n 1 || true)
if [[ -z "$jar" ]]; then
  echo "bobcrew-server jar not found; run 'mvn -q install -DskipTests' first" >&2
  exit 1
fi

export BOBCREW_JWT_SECRET_FILE="${BOBCREW_JWT_SECRET_FILE:-certs/jwt.secret}"
export BOBCREW_FILE_DOWNLOADS_ENABLED=true
# no --disable-mcp and no --disable-subagents
export BOBCREW_AGENT_COMMAND="bob,acp,--trust,--auto-approve,--accept-license"

exec java -jar "$jar"

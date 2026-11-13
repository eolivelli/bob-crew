#!/usr/bin/env bash
# Starts bobcrew-server with permissive agent settings, for a trusted single-user machine:
#
#   - the agent inherits the server's whole environment (GH_TOKEN and any other secrets in it)
#   - --auto-approve: tool calls run without asking for permission
#   - --trust: Bob works in any workspace
#   - MCP servers and subagents are enabled
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
export BOBCREW_AGENT_INHERIT_ENVIRONMENT=true
export BOBCREW_FILE_DOWNLOADS_ENABLED=true
# no --disable-mcp and no --disable-subagents
export BOB_ARGUMENTS="acp,--trust,--auto-approve,--accept-license"

exec java -jar "$jar"

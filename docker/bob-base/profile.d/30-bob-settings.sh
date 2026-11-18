# Sourced by bobcrew-entrypoint: Bob Shell's own defaults for a session, in $HOME/.bob/settings/settings.json.
#
# session.maxTurns: Bob Shell stops a task after 100 agentic turns by default, which long jobs in a session
# reach. A session has no use for that limit (the user is there to stop it, and the container's resources are
# limited anyway), so it is switched off (0 = unlimited) unless the settings file, which a user may bring with
# the user profile files, already says something. Nothing is printed: stdout carries ACP.

bobcrew_settings="$HOME/.bob/settings/settings.json"
if command -v jq >/dev/null 2>&1; then
  mkdir -p "$HOME/.bob/settings" 2>/dev/null || true
  if [ ! -s "$bobcrew_settings" ]; then
    echo '{"session":{"maxTurns":0}}' > "$bobcrew_settings" 2>/dev/null || true
  elif ! jq -e '.session.maxTurns != null' "$bobcrew_settings" >/dev/null 2>&1; then
    bobcrew_tmp="$(mktemp "$bobcrew_settings.XXXXXX" 2>/dev/null)" || bobcrew_tmp=""
    if [ -n "$bobcrew_tmp" ]; then
      if jq '.session.maxTurns = 0' "$bobcrew_settings" > "$bobcrew_tmp" 2>/dev/null; then
        mv -f "$bobcrew_tmp" "$bobcrew_settings" 2>/dev/null || rm -f "$bobcrew_tmp"
      else
        rm -f "$bobcrew_tmp"
      fi
    fi
    unset bobcrew_tmp
  fi
fi
unset bobcrew_settings

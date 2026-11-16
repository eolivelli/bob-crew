# Sourced by bobcrew-entrypoint: Homebrew's environment (PATH, MANPATH, HOMEBREW_*).
if [ -x /home/linuxbrew/.linuxbrew/bin/brew ]; then
  eval "$(/home/linuxbrew/.linuxbrew/bin/brew shellenv)"
  export HOMEBREW_NO_AUTO_UPDATE=1
fi

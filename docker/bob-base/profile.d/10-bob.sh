# Sourced by bobcrew-entrypoint (and the image build): npm's global bin, where the Bob installer links `bob`.
case ":$PATH:" in
  *":$HOME/.npm-global/bin:"*) ;;
  *) PATH="/home/bob/.npm-global/bin:$PATH" ;;
esac
export PATH

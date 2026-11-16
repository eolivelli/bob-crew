# The default bob-crew session image: Bob Shell plus everyday tools (git, gh, Node.js, Linux Homebrew)
# on Red Hat UBI 9. Images for particular stacks build on it (see ../bob-java/Dockerfile); they must
# keep its entrypoint, /opt/workspace and the bob user (uid 1000).
#
# Build with scripts/build-images.sh; the bob-crew server never pulls images.
FROM registry.access.redhat.com/ubi9/ubi:latest

LABEL org.opencontainers.image.title="bob-crew base" \
      org.opencontainers.image.description="Bob Shell session image for bob-crew" \
      bobcrew.base="1"

# --allowerasing: UBI ships curl-minimal, which the full curl replaces
RUN dnf -y module enable nodejs:22 \
 && dnf -y install --allowerasing \
      git curl ca-certificates tar gzip unzip which findutils procps-ng file diffutils patch \
      gcc gcc-c++ make jq less openssh-clients nodejs npm \
 && curl -fsSL https://cli.github.com/packages/rpm/gh-cli.repo -o /etc/yum.repos.d/gh-cli.repo \
 && dnf -y install gh \
 && dnf clean all && rm -rf /var/cache/dnf

# The user the server runs the agent as (bobcrew.docker.user defaults to 1000:1000). Homebrew lives
# in its standard Linux prefix, owned by that user so that `brew install` works at run time.
RUN groupadd --gid 1000 bob \
 && useradd --uid 1000 --gid 1000 --create-home --home-dir /home/bob --shell /bin/bash bob \
 && mkdir -p /opt/workspace /home/linuxbrew /etc/bobcrew/profile.d \
 && chown bob:bob /opt/workspace /home/linuxbrew

COPY bobcrew-entrypoint.sh /usr/local/bin/bobcrew-entrypoint

USER bob
ENV HOME=/home/bob
WORKDIR /home/bob

# Linux Homebrew, unattended
RUN NONINTERACTIVE=1 /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)" >&2 \
 && /home/linuxbrew/.linuxbrew/bin/brew --version

# Bob Shell, with the official installer (it runs `npm install -g @ibm/bob`, so npm's global prefix is
# moved somewhere the bob user owns). BOB_VERSION picks a release (default: the latest). Change
# BOB_REFRESH (scripts/build-images.sh --refresh-bob) to reinstall it without rebuilding the layers above.
ARG BOB_INSTALL_URL=https://bob.ibm.com/download/bobshell.sh
ARG BOB_VERSION=
ARG BOB_REFRESH=0
RUN echo "installing Bob Shell ${BOB_VERSION:-latest} (refresh ${BOB_REFRESH})" \
 && npm config set prefix /home/bob/.npm-global \
 && curl -fsSL "${BOB_INSTALL_URL}" | bash -s -- --pm npm ${BOB_VERSION:+--version "${BOB_VERSION}"} \
 && export PATH="/home/bob/.npm-global/bin:$PATH" \
 && command -v bob \
 && bob --version

ENV PATH=/home/bob/.npm-global/bin:/home/linuxbrew/.linuxbrew/bin:/home/linuxbrew/.linuxbrew/sbin:${PATH}

# last, so that editing a snippet does not rebuild the layers above
COPY profile.d/ /etc/bobcrew/profile.d/

WORKDIR /opt/workspace
ENTRYPOINT ["/usr/local/bin/bobcrew-entrypoint"]
CMD ["bob", "acp"]

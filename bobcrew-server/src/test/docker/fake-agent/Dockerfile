# The test stand-in for bobcrew/bob-base: the fake ACP agent behind the real bob-crew entrypoint, with the
# same contract (uid 1000, /opt/workspace). TestDocker builds it as bobcrew-fake-agent:test, adding
# bobcrew-entrypoint.sh, bin/git-credential-bobcrew and profile.d/40-git.sh (from docker/bob-base) and
# fake-agent.jar to the build context. With 40-git.sh, sessions set git up from their secrets as in bob-base.
#
# It also has git and a bare repository, /opt/test-repos/sample.git (branches main and feature), for the
# tests that clone a repository into a session's workspace without network access, and a root.d hook
# that sessions with capabilities run as root before the agent starts (it writes its uid and
# BOBCREW_CAPABILITIES to /run/bobcrew-root-hook).
FROM eclipse-temurin:21-jre-ubi9-minimal
RUN microdnf install -y git-core && microdnf clean all
COPY bobcrew-entrypoint.sh /usr/local/bin/bobcrew-entrypoint
COPY git-credential-bobcrew /usr/local/bin/git-credential-bobcrew
COPY 40-git.sh /etc/bobcrew/profile.d/40-git.sh
COPY fake-agent.jar /opt/fake-agent.jar
RUN chmod 755 /usr/local/bin/bobcrew-entrypoint /usr/local/bin/git-credential-bobcrew \
 && chmod 644 /opt/fake-agent.jar /etc/bobcrew/profile.d/40-git.sh \
 && mkdir -p /opt/workspace /home/bob \
 && chown 1000:1000 /opt/workspace /home/bob \
 && git config --global user.name test && git config --global user.email test@example.com \
 && git init -q -b main /tmp/sample && cd /tmp/sample \
 && echo "the main branch" > README.md && git add README.md && git commit -qm "first" \
 && echo "more" >> README.md && git commit -qam "second" \
 && git checkout -q -b feature && echo "a feature" > FEATURE.md && git add FEATURE.md && git commit -qm "feature" \
 && git checkout -q main \
 && mkdir -p /opt/test-repos && git clone -q --bare /tmp/sample /opt/test-repos/sample.git \
 && chown -R 1000:1000 /opt/test-repos && rm -rf /tmp/sample /root/.gitconfig \
 && mkdir -p /etc/bobcrew/root.d \
 && printf 'echo "$(id -u) ${BOBCREW_CAPABILITIES:-}" > /run/bobcrew-root-hook\n' > /etc/bobcrew/root.d/10-test.sh
USER 1000:1000
WORKDIR /opt/workspace
ENTRYPOINT ["/usr/local/bin/bobcrew-entrypoint"]
CMD ["java", "-jar", "/opt/fake-agent.jar"]

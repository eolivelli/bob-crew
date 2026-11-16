# The test stand-in for bobcrew/bob-base: the fake ACP agent behind the real bob-crew entrypoint, with the
# same contract (uid 1000, /opt/workspace). TestDocker builds it as bobcrew-fake-agent:test, adding
# bobcrew-entrypoint.sh (from docker/bob-base) and fake-agent.jar to the build context.
FROM eclipse-temurin:21-jre-ubi9-minimal
COPY bobcrew-entrypoint.sh /usr/local/bin/bobcrew-entrypoint
COPY fake-agent.jar /opt/fake-agent.jar
RUN chmod 755 /usr/local/bin/bobcrew-entrypoint \
 && chmod 644 /opt/fake-agent.jar \
 && mkdir -p /opt/workspace \
 && chown 1000:1000 /opt/workspace
USER 1000:1000
WORKDIR /opt/workspace
ENTRYPOINT ["/usr/local/bin/bobcrew-entrypoint"]
CMD ["java", "-jar", "/opt/fake-agent.jar"]

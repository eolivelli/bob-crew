# Template image for Java and Python development: the bob-crew base plus JDK 21, Maven 3.9 and
# Python 3.11 (python, python3 and pip point to 3.11). Git and GitHub are set up at start from the
# secrets GIT_USER_NAME, GIT_USER_EMAIL and GH_TOKEN (see bob-base/profile.d/40-git.sh).
#
# Use it from a role with `image: bobcrew/bob-java-python:latest` (ui.template/roles/developer.md does).
# Build with scripts/build-images.sh; copy it to a server with scripts/push-images.sh. Copy this
# directory to start an image of your own.
ARG BASE_IMAGE=bobcrew/bob-base:latest
FROM ${BASE_IMAGE}

ARG MAVEN_VERSION=3.9.11

USER root
RUN dnf -y install java-21-openjdk-devel python3.11 python3.11-pip python3.11-devel \
 && dnf clean all && rm -rf /var/cache/dnf \
 && mkdir -p /opt/tools \
 && curl -fsSL "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
      | tar -xz -C /opt/tools \
 && ln -s "/opt/tools/apache-maven-${MAVEN_VERSION}" /opt/tools/maven \
 && ln -sf /usr/bin/python3.11 /usr/local/bin/python3 \
 && ln -sf /usr/bin/python3.11 /usr/local/bin/python \
 && ln -sf /usr/bin/pip3.11 /usr/local/bin/pip3 \
 && ln -sf /usr/bin/pip3.11 /usr/local/bin/pip

COPY profile.d/ /etc/bobcrew/profile.d/

ENV JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
    MAVEN_HOME=/opt/tools/maven
ENV PATH=/usr/local/bin:${JAVA_HOME}/bin:${MAVEN_HOME}/bin:/home/bob/.local/bin:${PATH}

USER bob
RUN java -version && mvn -v && python --version && pip --version

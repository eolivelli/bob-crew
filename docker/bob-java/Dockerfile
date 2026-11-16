# Example of a stack-specific image: the bob-crew base plus a Java 21 toolchain (JDK, Maven, Gradle, Ant).
# Use it from a role with `image: bobcrew/bob-java:latest`. Build with scripts/build-images.sh.
ARG BASE_IMAGE=bobcrew/bob-base:latest
FROM ${BASE_IMAGE}

ARG MAVEN_VERSION=3.9.11
ARG GRADLE_VERSION=8.14.3
ARG ANT_VERSION=1.10.15

USER root
RUN dnf -y install java-21-openjdk-devel \
 && dnf clean all && rm -rf /var/cache/dnf \
 && mkdir -p /opt/tools \
 && curl -fsSL "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
      | tar -xz -C /opt/tools && ln -s "/opt/tools/apache-maven-${MAVEN_VERSION}" /opt/tools/maven \
 && curl -fsSL -o /tmp/gradle.zip "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" \
      && unzip -q /tmp/gradle.zip -d /opt/tools && rm /tmp/gradle.zip \
      && ln -s "/opt/tools/gradle-${GRADLE_VERSION}" /opt/tools/gradle \
 && curl -fsSL "https://archive.apache.org/dist/ant/binaries/apache-ant-${ANT_VERSION}-bin.tar.gz" \
      | tar -xz -C /opt/tools && ln -s "/opt/tools/apache-ant-${ANT_VERSION}" /opt/tools/ant

COPY profile.d/ /etc/bobcrew/profile.d/

ENV JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
    MAVEN_HOME=/opt/tools/maven \
    GRADLE_HOME=/opt/tools/gradle \
    ANT_HOME=/opt/tools/ant
ENV PATH=${JAVA_HOME}/bin:${MAVEN_HOME}/bin:${GRADLE_HOME}/bin:${ANT_HOME}/bin:${PATH}

USER bob
RUN java -version && mvn -v && gradle --version >/dev/null && ant -version

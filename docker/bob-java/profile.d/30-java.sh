# Sourced by bobcrew-entrypoint: the Java toolchain of the bob-java image.
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export MAVEN_HOME=/opt/tools/maven GRADLE_HOME=/opt/tools/gradle ANT_HOME=/opt/tools/ant
PATH="$JAVA_HOME/bin:$MAVEN_HOME/bin:$GRADLE_HOME/bin:$ANT_HOME/bin:$PATH"
export PATH

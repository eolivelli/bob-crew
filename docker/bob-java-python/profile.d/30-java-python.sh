# Sourced by bobcrew-entrypoint: the toolchain of the bob-java-python image.
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export MAVEN_HOME=/opt/tools/maven
# pip install --user puts scripts in ~/.local/bin
PATH="/usr/local/bin:$JAVA_HOME/bin:$MAVEN_HOME/bin:$HOME/.local/bin:$PATH"
export PATH

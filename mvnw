#!/usr/bin/env sh
set -eu

basedir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec java ${JAVA_OPTS:-} ${MAVEN_OPTS:-} -classpath "$basedir/.mvn/wrapper/maven-wrapper.jar" "-Dmaven.multiModuleProjectDirectory=$basedir" org.apache.maven.wrapper.MavenWrapperMain "$@"

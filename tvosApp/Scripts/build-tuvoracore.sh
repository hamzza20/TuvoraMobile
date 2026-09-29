#!/bin/bash
# Builds the shared Kotlin framework for the current tvOS SDK/configuration (same task the iPhone app uses).
set -euo pipefail
if [ "${OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED:-}" = "YES" ]; then exit 0; fi
if [ -z "${JAVA_HOME:-}" ] && [ -x "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/java" ]; then
  export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
fi
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}/opt/homebrew/bin:$PATH"
export GRADLE_OPTS="${GRADLE_OPTS:--Xmx12288M -Dfile.encoding=UTF-8 -XX:MaxMetaspaceSize=2048m}"
cd "$SRCROOT/.."
./gradlew :tvosCore:embedAndSignAppleFrameworkForXcode

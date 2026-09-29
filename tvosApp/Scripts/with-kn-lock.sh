#!/bin/bash
# with-kn-lock.sh <command...> : run a Gradle/Kotlin-Native command under the machine-wide build lock
# (one K/N build at a time — each can take ~16 GB). Example:
#   tvosApp/Scripts/with-kn-lock.sh ./gradlew :tvosCore:tvosSimulatorArm64Test
set -uo pipefail
LOCK=/tmp/tuvora-kn-build.lock
until mkdir "$LOCK" 2>/dev/null; do
  if [ -n "$(find "$LOCK" -maxdepth 0 -mmin +45 2>/dev/null)" ]; then rmdir "$LOCK" 2>/dev/null || true; fi
  echo "waiting for another Kotlin/Native build to finish…"; sleep 10
done
trap 'rmdir "$LOCK" 2>/dev/null || true' EXIT
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
"$@"

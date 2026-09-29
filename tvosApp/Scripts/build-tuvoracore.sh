#!/bin/bash
# Builds the shared Kotlin framework for the current tvOS SDK/configuration (same task the iPhone app uses).
#
# SKIP_TUVORACORE_BUILD=1 reuses the framework already in tvosCore/build/xcode-frameworks (Swift-only
# changes). Kotlin/Native builds take up to ~16 GB, so concurrent worktrees serialise on one machine-wide
# lock (/tmp/tuvora-kn-build.lock) instead of running two at once.
set -euo pipefail
if [ "${OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED:-}" = "YES" ]; then exit 0; fi
if [ "${SKIP_TUVORACORE_BUILD:-0}" = "1" ]; then
  echo "SKIP_TUVORACORE_BUILD=1: using the prebuilt TuvoraCore.framework"; exit 0
fi
if [ -z "${JAVA_HOME:-}" ] && [ -x "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/java" ]; then
  export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
fi
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}/opt/homebrew/bin:$PATH"
export GRADLE_OPTS="${GRADLE_OPTS:--Xmx12288M -Dfile.encoding=UTF-8 -XX:MaxMetaspaceSize=2048m}"
LOCK=/tmp/tuvora-kn-build.lock
until mkdir "$LOCK" 2>/dev/null; do
  # Stale lock (holder died): reclaim after 45 minutes.
  if [ -n "$(find "$LOCK" -maxdepth 0 -mmin +45 2>/dev/null)" ]; then rmdir "$LOCK" 2>/dev/null || true; fi
  echo "waiting for another Kotlin/Native build to finish…"; sleep 10
done
trap 'rmdir "$LOCK" 2>/dev/null || true' EXIT
cd "$SRCROOT/.."
./gradlew :tvosCore:embedAndSignAppleFrameworkForXcode

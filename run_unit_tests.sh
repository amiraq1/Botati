#!/bin/bash
# Standalone fallback test runner: compiles the Android-free slice of the
# domain with kotlinc and runs the JUnit suite without Gradle.
#
# This is the path used where Gradle cannot run (no Android SDK, restricted VM).
# CI is still the authority: `./gradlew :app:testDebugUnitTest` runs everything,
# including the Ktor MockEngine tests and the Android-facing classes below.
#
# Overridable so it is not tied to one machine (it used to hardcode Termux
# paths — BOT-21):
#   KOTLIN_HOME    directory holding kotlin-stdlib.jar and the serialization plugin
#   TEST_DEPS      directory holding the dependency JARs (coroutines, serialization, junit)
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$SCRIPT_DIR"
TEST_CLASSES="$ROOT_DIR/build-tests/classes"

KOTLIN_HOME="${KOTLIN_HOME:-/data/data/com.termux/files/usr/opt/kotlin/lib}"
KOTLIN_LIB="$KOTLIN_HOME/kotlin-stdlib.jar"
KOTLIN_SERIALIZATION_PLUGIN="$KOTLIN_HOME/kotlinx-serialization-compiler-plugin.jar"
DEPS_DIR="${TEST_DEPS:-$HOME/test-deps}"

if [ ! -f "$KOTLIN_LIB" ]; then
  echo "kotlin-stdlib.jar not found at $KOTLIN_LIB — set KOTLIN_HOME" >&2
  exit 2
fi
if ! ls "$DEPS_DIR"/*.jar >/dev/null 2>&1; then
  echo "no dependency JARs in $DEPS_DIR — set TEST_DEPS" >&2
  exit 2
fi

rm -rf "$TEST_CLASSES"
mkdir -p "$TEST_CLASSES"

CP=$(ls "$DEPS_DIR"/*.jar | paste -sd ":" -)

# Gradle-only tests: these need BuildConfig, Ktor MockEngine or an Android
# Context, none of which the standalone kotlinc run provides.
#   OpenRouterClientProviderTest — Ktor + BuildConfig
#   McpParsingTest               — MinimalMcpClient (Ktor)
#   McpSessionRecoveryTest       — MinimalMcpClient + Ktor MockEngine
# BoundedReadTest is NOT excluded: BoundedRead is a pure domain primitive.
GRADLE_ONLY_TESTS="OpenRouterClientProviderTest.kt|McpParsingTest.kt|McpSessionRecoveryTest.kt"
TEST_SOURCES=$(find "$ROOT_DIR/app/src/test/java" -name '*.kt' | grep -Ev "$GRADLE_ONLY_TESTS")

echo "==> Compiling domain and test sources..."
# shellcheck disable=SC2086
kotlinc \
  -cp "$CP" \
  -Xplugin="$KOTLIN_SERIALIZATION_PLUGIN" \
  -d "$TEST_CLASSES" \
  $(find "$ROOT_DIR/app/src/main/java/com/botati/domain" -name '*.kt') \
  "$ROOT_DIR/app/src/main/java/com/botati/crash/CrashReportText.kt" \
  $TEST_SOURCES || { echo "COMPILE FAILED" >&2; exit 1; }

echo "==> Running JUnit tests..."
CP_RUN=$(ls "$DEPS_DIR"/*.jar | grep -v "junit-platform-console-standalone" | paste -sd ":" -)
java -jar "$DEPS_DIR/junit-platform-console-standalone-1.10.5.jar" \
  --class-path "$TEST_CLASSES:$CP_RUN:$KOTLIN_LIB" \
  --scan-class-path="$TEST_CLASSES"

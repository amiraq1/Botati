#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$SCRIPT_DIR"
TEST_CLASSES="$ROOT_DIR/build-tests/classes"
DEPS_DIR="$HOME/test-deps"
KOTLIN_LIB="/data/data/com.termux/files/usr/opt/kotlin/lib/kotlin-stdlib.jar"
KOTLIN_SERIALIZATION_PLUGIN="/data/data/com.termux/files/usr/opt/kotlin/lib/kotlinx-serialization-compiler-plugin.jar"

rm -rf "$TEST_CLASSES"
mkdir -p "$TEST_CLASSES"

CP=$(ls "$DEPS_DIR"/*.jar | paste -sd ":" -)

echo "==> Compiling domain and test sources..."
kotlinc \
  -cp "$CP" \
  -Xplugin="$KOTLIN_SERIALIZATION_PLUGIN" \
  -d "$TEST_CLASSES" \
  $(find "$ROOT_DIR/Botati/app/src/main/java/com/botati/domain" -name '*.kt') \
  "$ROOT_DIR/Botati/app/src/main/java/com/botati/infrastructure/mcp/McpClientPort.kt" \
  "$ROOT_DIR/Botati/app/src/main/java/com/botati/infrastructure/mcp/MinimalMcpClient.kt" \
  $(find "$ROOT_DIR/Botati/app/src/test/java" -name '*.kt')

echo "==> Running JUnit tests..."
CP_RUN=$(ls "$DEPS_DIR"/*.jar | grep -v "junit-platform-console-standalone" | paste -sd ":" -)
java -jar "$DEPS_DIR/junit-platform-console-standalone-1.10.5.jar" \
  --class-path "$TEST_CLASSES:$CP_RUN:$KOTLIN_LIB" \
  --scan-class-path="$TEST_CLASSES"

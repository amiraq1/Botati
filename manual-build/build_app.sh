#!/bin/bash
# Manual full-app compile for Botati (Gradle daemon is blocked in this VM).
set -u
export JAVA_HOME=$HOME/workspace/tools/jdk17
PROJ=$HOME/workspace/android-agent-app/Botati
DEPS=/tmp/botati-deps
B=/tmp/botati-build
AJ=$HOME/workspace/tools/android-sdk/platforms/android-35/android.jar
KOTLINC=$HOME/workspace/tools/kotlinc/bin/kotlinc

# Classpath: android.jar + compiled R/BuildConfig + every AAR classes.jar + every plain jar.
CP="$AJ:$B/rclasses"
for j in $B/exploded/*/classes.jar; do CP="$CP:$j"; done
for j in $DEPS/*.jar; do CP="$CP:$j"; done
CP="$CP:/tmp/coroutines.jar:/tmp/serialization-core.jar:/tmp/serialization-json.jar"
CP="$CP:/tmp/kotlinx-io-core-jvm-0.5.4.jar:/tmp/kotlinx-io-bytestring-jvm-0.5.4.jar"
CP="$CP:/tmp/room-common-2.6.1.jar:/tmp/slf4j-api-2.0.16.jar:/tmp/annotations.jar:/tmp/ktor-sse-jvm-3.0.3.jar:/tmp/ktor-client-core-jvm-3.0.3.jar:/tmp/ktor-http-jvm-3.0.3.jar:/tmp/ktor-io-jvm-3.0.3.jar:/tmp/ktor-utils-jvm-3.0.3.jar:/tmp/ktor-events-jvm-3.0.3.jar:/tmp/ktor-serialization-jvm-3.0.3.jar"

KSP_AP="$DEPS/room-compiler-2.6.1.jar:$DEPS/room-compiler-processing-2.6.1.jar:$DEPS/room-migration-2.6.1.jar:/tmp/room-common-2.6.1.jar:$DEPS/kotlinpoet-1.18.1.jar:$DEPS/kotlinpoet-ksp-1.18.1.jar:$DEPS/javapoet-1.13.0.jar:$DEPS/symbol-processing-api-2.0.21-1.0.28.jar:$DEPS/auto-value-annotations-1.11.0.jar"

mkdir -p $B/ksp/classes $B/ksp/java $B/ksp/kotlin $B/ksp/resources $B/ksp/caches $B/appclasses

EXTRA_SRC="${1:-}"
# Drive the EMBEDDABLE compiler directly: the Compose/KSP plugin jars are built
# against it (relocated IntelliJ packages), not against the standalone CLI.
$JAVA_HOME/bin/java -Xmx850m \
  -cp /tmp/kotlin-compiler-embeddable.jar:$HOME/workspace/tools/kotlinc/lib/kotlin-stdlib.jar:/tmp/annotations.jar:/tmp/coroutines.jar:/tmp/serialization-core.jar:/tmp/trove4j.jar:$DEPS/symbol-processing-api-2.0.21-1.0.28.jar:$DEPS/symbol-processing-common-deps-2.0.21-1.0.28.jar \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -kotlin-home /tmp/fake-kotlin-home \
  -cp "$CP" \
  -jvm-target 17 \
  -Xplugin=/tmp/serialization-plugin-embeddable.jar \
  -Xplugin=$DEPS/kotlin-compose-compiler-plugin-embeddable-2.0.21.jar \
  -Xplugin=$DEPS/symbol-processing-2.0.21-1.0.28.jar \
  -Xplugin=$DEPS/symbol-processing-api-2.0.21-1.0.28.jar \
  -Xplugin=$DEPS/symbol-processing-common-deps-2.0.21-1.0.28.jar \
  -P plugin:com.google.devtools.ksp.symbol-processing:apclasspath=$KSP_AP \
  -P plugin:com.google.devtools.ksp.symbol-processing:projectBaseDir=$B/ksp \
  -P plugin:com.google.devtools.ksp.symbol-processing:classOutputDir=$B/ksp/classes \
  -P plugin:com.google.devtools.ksp.symbol-processing:javaOutputDir=$B/ksp/java \
  -P plugin:com.google.devtools.ksp.symbol-processing:kotlinOutputDir=$B/ksp/kotlin \
  -P plugin:com.google.devtools.ksp.symbol-processing:resourceOutputDir=$B/ksp/resources \
  -P plugin:com.google.devtools.ksp.symbol-processing:cachesDir=$B/ksp/caches \
  -P plugin:com.google.devtools.ksp.symbol-processing:incremental=false \
  -d $B/appclasses \
  $(find $PROJ/app/src/main/java -name '*.kt') $EXTRA_SRC
echo "KOTLINC_EXIT=$?"

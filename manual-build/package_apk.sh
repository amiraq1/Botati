#!/bin/bash
# Manual APK packaging for Botati: d8 + resource merge + zipalign + sign.
set -eu
export JAVA_HOME=$HOME/workspace/tools/jdk17
export PATH=$JAVA_HOME/bin:$PATH
BT=$HOME/workspace/tools/android-sdk/build-tools/35.0.0
AJ=$HOME/workspace/tools/android-sdk/platforms/android-35/android.jar
B=/tmp/botati-build
DEPS=/tmp/botati-deps
OUT=$HOME/workspace/android-agent-app/Botati/app/build/outputs/apk/debug
mkdir -p $OUT $B/dex $B/jarres

# ---- collect dex inputs -------------------------------------------------
INPUTS="$B/appclasses.jar $B/roomimpl.jar $B/rclasses.jar"
for j in $B/exploded/*/classes.jar; do INPUTS="$INPUTS $j"; done
# plain jars, excluding build-time-only artifacts and empty/duplicate ones
SKIP="room-compiler|room-compiler-processing|room-migration|kotlinpoet|javapoet|auto-common|auto-value|symbol-processing|commons-codec|incap|sqlite-jdbc|guava-32|annotation-1.9.1|datastore-preferences-core-1.1.1|lifecycle-common-2.8.7|koin-androidx-workmanager|kotlin-stdlib"
for j in $DEPS/*.jar; do
  case "$j" in *$SKIP*) continue;; esac
  INPUTS="$INPUTS $j"
done
# regex skip above is substring-based on full path segments; do a cleaner pass:
INPUTS2=""
for p in $INPUTS; do
  base=$(basename "$p")
  case "$base" in
    room-compiler*|kotlinpoet*|javapoet*|auto-common*|auto-value*|symbol-processing*|commons-codec*|incap*|sqlite-jdbc*|guava-32*|annotation-1.9.1.jar|datastore-preferences-core-1.1.1.jar|lifecycle-common-2.8.7.jar|koin-androidx-workmanager*) ;;
    *) INPUTS2="$INPUTS2 $p";;
  esac
done
INPUTS="$INPUTS2"
INPUTS="$INPUTS /tmp/serialization-core.jar /tmp/serialization-json.jar"
INPUTS="$INPUTS /tmp/kotlinx-io-core-jvm-0.5.4.jar /tmp/kotlinx-io-bytestring-jvm-0.5.4.jar"
INPUTS="$INPUTS /tmp/ktor-client-core-jvm-3.0.3.jar /tmp/ktor-http-jvm-3.0.3.jar /tmp/ktor-io-jvm-3.0.3.jar"
INPUTS="$INPUTS /tmp/ktor-utils-jvm-3.0.3.jar /tmp/ktor-events-jvm-3.0.3.jar /tmp/ktor-serialization-jvm-3.0.3.jar /tmp/ktor-sse-jvm-3.0.3.jar"
INPUTS="$INPUTS /tmp/room-common-2.6.1.jar /tmp/slf4j-api-2.0.16.jar /tmp/annotation-jvm.jar"
INPUTS="$INPUTS $HOME/workspace/tools/kotlinc/lib/kotlin-stdlib.jar"
echo "dex inputs: $(echo $INPUTS | wc -w)"

# ---- merge java resources (services, pdfbox cmaps, ...) ------------------
cd $B/jarres
for p in $INPUTS; do
  case "$p" in
    *.jar) unzip -qo "$p" -x "*.class" "META-INF/*.SF" "META-INF/*.RSA" "META-INF/*.DSA" "META-INF/MANIFEST.MF" -d $B/jarres 2>/dev/null || true;;
  esac
done
# merge META-INF/services by concatenation (AGP-style)
mkdir -p $B/services-merged
for p in $INPUTS; do
  case "$p" in
    *.jar)
      for svc in $(unzip -Z1 "$p" 2>/dev/null | grep "^META-INF/services/" | grep -v "/$"); do
        unzip -p "$p" "$svc" >> $B/services-merged/$(basename $svc) 2>/dev/null || true
        echo >> $B/services-merged/$(basename $svc)
      done;;
  esac
done
rm -rf $B/jarres/META-INF/services
mkdir -p $B/jarres/META-INF/services
cp $B/services-merged/* $B/jarres/META-INF/services/ 2>/dev/null || true
echo "java resources staged: $(find $B/jarres -type f | wc -l) files, services: $(ls $B/jarres/META-INF/services 2>/dev/null | wc -l)"

# ---- dex ------------------------------------------------------------------
rm -f $B/dex/*.dex
$BT/d8 --lib $AJ --min-api 26 --output $B/dex $INPUTS 2>&1 | grep -vE "^Warning: .*not found|^Info:" | head -20 || true
ls -la $B/dex/

# ---- assemble --------------------------------------------------------------
cp $B/base.apk $B/unsigned.apk
cd $B/dex && zip -q $B/unsigned.apk classes*.dex
# AAR assets (pdfbox cmaps/fonts live in classes; other AAR assets if any)
for d in $B/exploded/*/; do
  if [ -d "$d/assets" ] && [ -n "$(ls -A "$d/assets" 2>/dev/null)" ]; then
    (cd "$d" && zip -qr $B/unsigned.apk assets)
    echo "added assets from $(basename $d)"
  fi
done
# jar java-resources at APK root
(cd $B/jarres && zip -qr $B/unsigned.apk . -x "./META-INF/*")
(cd $B/jarres && zip -q $B/unsigned.apk META-INF/services/* 2>/dev/null) || true
# native libs from AARs
for d in $B/exploded/*/; do
  if [ -d "$d/jni" ] && [ -n "$(ls -A "$d/jni" 2>/dev/null)" ]; then
    (cd "$d" && zip -qr $B/unsigned.apk jni)
    echo "added jni from $(basename $d)"
  fi
done

$BT/zipalign -pf 4 $B/unsigned.apk $B/aligned.apk

# ---- sign (debug key) -------------------------------------------------------
KS=$B/debug.keystore
if [ ! -f $KS ]; then
  $JAVA_HOME/bin/keytool -genkeypair -keystore $KS -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10957 \
    -dname "CN=Android Debug,O=Android,C=US" 2>/dev/null
fi
$BT/apksigner sign --ks $KS --ks-pass pass:android --key-pass pass:android \
  --out $OUT/app-debug.apk $B/aligned.apk
$BT/apksigner verify --print-certs $OUT/app-debug.apk | head -3
ls -la $OUT/app-debug.apk
sha256sum $OUT/app-debug.apk

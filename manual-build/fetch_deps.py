#!/usr/bin/env python3
"""Fetch pinned AAR/JAR dependencies for the manual Botati APK build."""
import subprocess, sys, os

OUT = "/tmp/botati-deps"
os.makedirs(OUT, exist_ok=True)
GOOGLE = "https://maven.google.com"
CENTRAL = "https://repo1.maven.org/maven2"

# (repo, group, artifact, version, ext)
DEPS = [
    # ---- AndroidX core/activity ----
    (GOOGLE, "androidx.core", "core", "1.15.0", "aar"),
    (GOOGLE, "androidx.core", "core-ktx", "1.15.0", "aar"),
    (GOOGLE, "androidx.activity", "activity", "1.9.3", "aar"),
    (GOOGLE, "androidx.activity", "activity-ktx", "1.9.3", "aar"),
    (GOOGLE, "androidx.activity", "activity-compose", "1.9.3", "aar"),
    (GOOGLE, "androidx.savedstate", "savedstate", "1.2.1", "aar"),
    (GOOGLE, "androidx.savedstate", "savedstate-ktx", "1.2.1", "aar"),
    # ---- Compose 1.7.6 / material3 1.3.1 (BOM 2024.12.01) ----
    (GOOGLE, "androidx.compose.runtime", "runtime-android", "1.7.6", "aar"),
    (GOOGLE, "androidx.compose.runtime", "runtime-saveable-android", "1.7.6", "aar"),
    (GOOGLE, "androidx.compose.ui", "ui-android", "1.7.6", "aar"),
    (GOOGLE, "androidx.compose.ui", "ui-graphics-android", "1.7.6", "aar"),
    (GOOGLE, "androidx.compose.ui", "ui-text-android", "1.7.6", "aar"),
    (GOOGLE, "androidx.compose.ui", "ui-unit-android", "1.7.6", "aar"),
    (GOOGLE, "androidx.compose.ui", "ui-geometry-android", "1.7.6", "aar"),
    (GOOGLE, "androidx.compose.ui", "ui-util-android", "1.7.6", "aar"),
    (GOOGLE, "androidx.compose.ui", "ui-tooling-preview-android", "1.7.6", "aar"),
    (GOOGLE, "androidx.compose.ui", "ui-tooling-data-android", "1.7.6", "aar"),
    (GOOGLE, "androidx.compose.foundation", "foundation-android", "1.7.6", "aar"),
    (GOOGLE, "androidx.compose.foundation", "foundation-layout-android", "1.7.6", "aar"),
    (GOOGLE, "androidx.compose.animation", "animation-android", "1.7.6", "aar"),
    (GOOGLE, "androidx.compose.animation", "animation-core-android", "1.7.6", "aar"),
    (GOOGLE, "androidx.compose.material3", "material3-android", "1.3.1", "aar"),
    (GOOGLE, "androidx.compose.material", "material-icons-core-android", "1.7.6", "aar"),
    (GOOGLE, "androidx.compose.material", "material-ripple-android", "1.7.6", "aar"),
    # ---- Lifecycle 2.8.7 ----
    (GOOGLE, "androidx.lifecycle", "lifecycle-common", "2.8.7", "jar"),
    (GOOGLE, "androidx.lifecycle", "lifecycle-runtime-android", "2.8.7", "aar"),
    (GOOGLE, "androidx.lifecycle", "lifecycle-runtime-ktx-android", "2.8.7", "aar"),
    (GOOGLE, "androidx.lifecycle", "lifecycle-viewmodel-android", "2.8.7", "aar"),
    (GOOGLE, "androidx.lifecycle", "lifecycle-viewmodel-ktx", "2.8.7", "aar"),
    (GOOGLE, "androidx.lifecycle", "lifecycle-viewmodel-compose-android", "2.8.7", "aar"),
    (GOOGLE, "androidx.lifecycle", "lifecycle-runtime-compose-android", "2.8.7", "aar"),
    (GOOGLE, "androidx.lifecycle", "lifecycle-livedata-core-android", "2.8.7", "aar"),
    (GOOGLE, "androidx.lifecycle", "lifecycle-process-android", "2.8.7", "aar"),
    (GOOGLE, "androidx.lifecycle", "lifecycle-viewmodel-savedstate", "2.8.7", "aar"),
    # ---- Navigation 2.8.5 ----
    (GOOGLE, "androidx.navigation", "navigation-common-android", "2.8.5", "aar"),
    (GOOGLE, "androidx.navigation", "navigation-common-ktx", "2.8.5", "aar"),
    (GOOGLE, "androidx.navigation", "navigation-runtime-android", "2.8.5", "aar"),
    (GOOGLE, "androidx.navigation", "navigation-runtime-ktx", "2.8.5", "aar"),
    (GOOGLE, "androidx.navigation", "navigation-compose-android", "2.8.5", "aar"),
    # ---- Room / sqlite / datastore ----
    (GOOGLE, "androidx.room", "room-runtime-android", "2.6.1", "aar"),
    (GOOGLE, "androidx.room", "room-ktx", "2.6.1", "aar"),
    (GOOGLE, "androidx.sqlite", "sqlite-android", "2.4.0", "aar"),
    (GOOGLE, "androidx.sqlite", "sqlite-framework-android", "2.4.0", "aar"),
    (GOOGLE, "androidx.datastore", "datastore-android", "1.1.1", "aar"),
    (GOOGLE, "androidx.datastore", "datastore-preferences-android", "1.1.1", "aar"),
    (GOOGLE, "androidx.datastore", "datastore-core-android", "1.1.1", "aar"),
    (GOOGLE, "androidx.datastore", "datastore-preferences-core-android", "1.1.1", "aar"),
    # datastore-core 1.1.1's storage backend (OkioSerializer / OkioStorage,
    # referenced by datastore-core-android classes) lives in datastore-core-okio.
    # At 1.1.1 there is NO -android AAR and no base AAR — only the -jvm jar
    # (same trap pattern as datastore-preferences-core). Fetch the jar.
    (GOOGLE, "androidx.datastore", "datastore-core-okio-jvm", "1.1.1", "jar"),
    # androidx.graphics:graphics-path — PathIterator/PathSegment classes
    # referenced by compose ui-graphics. Base-name AAR (classes + the
    # libandroidx.graphics.path.so JNI, which the package scripts zip in).
    (GOOGLE, "androidx.graphics", "graphics-path", "1.0.1", "aar"),
    # ---- AndroidX support ----
    (GOOGLE, "androidx.annotation", "annotation", "1.9.1", "jar"),
    (GOOGLE, "androidx.annotation", "annotation-experimental", "1.4.1", "aar"),
    (GOOGLE, "androidx.collection", "collection-jvm", "1.4.5", "jar"),
    (GOOGLE, "androidx.collection", "collection-ktx", "1.4.5", "jar"),
    (GOOGLE, "androidx.concurrent", "concurrent-futures", "1.2.0", "jar"),
    (GOOGLE, "androidx.arch.core", "core-common", "2.2.0", "jar"),
    (GOOGLE, "androidx.arch.core", "core-runtime", "2.2.0", "aar"),
    (GOOGLE, "androidx.profileinstaller", "profileinstaller", "1.3.1", "aar"),
    (GOOGLE, "androidx.tracing", "tracing", "1.2.0", "aar"),
    (GOOGLE, "androidx.customview", "customview-poolingcontainer", "1.0.0", "aar"),
    (GOOGLE, "androidx.emoji2", "emoji2", "1.2.0", "aar"),
    (GOOGLE, "androidx.graphics", "graphics-shapes-android", "1.0.1", "aar"),
    (GOOGLE, "androidx.graphics", "graphics-core-android", "1.0.0", "aar"),
    (GOOGLE, "androidx.interpolator", "interpolator", "1.0.0", "aar"),
    (GOOGLE, "androidx.versionedparcelable", "versionedparcelable", "1.1.1", "aar"),
    (GOOGLE, "androidx.startup", "startup-runtime", "1.1.1", "aar"),
    (GOOGLE, "androidx.window", "window", "1.2.0", "aar"),
    # ---- Koin 4.0.0 (Maven Central) ----
    (CENTRAL, "io.insert-koin", "koin-core-jvm", "4.0.0", "jar"),
    (CENTRAL, "io.insert-koin", "koin-core-viewmodel-jvm", "4.0.0", "jar"),
    (CENTRAL, "io.insert-koin", "koin-android", "4.0.0", "aar"),
    (CENTRAL, "io.insert-koin", "koin-androidx-compose", "4.0.0", "aar"),
    (CENTRAL, "io.insert-koin", "koin-androidx-viewmodel", "4.0.0", "aar"),
    (CENTRAL, "io.insert-koin", "koin-androidx-workmanager", "4.0.0", "aar"),
    # koin-androidx-compose's POM depends on io.insert-koin:koin-compose:4.0.0,
    # but Koin 4.0.0 published NO -android variant of it (koin-compose-android
    # starts at 4.1.0-Beta3). The org.koin.compose.* classes (KoinApplicationKt,
    # referenced from BotsScreen via an inlined call) live in the JVM variant
    # jar — plain JVM bytecode, dexes fine. Omitting it shipped the v0.1.2
    # NoClassDefFoundError at BotsScreen (see BUILD_LOG v0.1.3).
    (CENTRAL, "io.insert-koin", "koin-compose-jvm", "4.0.0", "jar"),
    # koin-android's POM brings appcompat 1.7.0 + fragment-ktx 1.8.3; its
    # classes reference androidx.fragment.* (FragmentFactory, FragmentManager,
    # ...) which must be dexed. appcompat itself is deliberately NOT fetched:
    # AppCompatActivity is only the superclass of koin's unused ScopeActivity /
    # RetainedScopeActivity (not manifest components, never loaded here).
    (GOOGLE, "androidx.fragment", "fragment", "1.8.3", "aar"),
    (GOOGLE, "androidx.fragment", "fragment-ktx", "1.8.3", "aar"),
    # fragment 1.8.3's own compile-scope POM deps (LoaderManager signature in
    # FragmentActivity; PagerAdapter superclass of FragmentStatePagerAdapter).
    # Neither carries res/ or references its own R — classes only.
    (GOOGLE, "androidx.loader", "loader", "1.0.0", "aar"),
    (GOOGLE, "androidx.viewpager", "viewpager", "1.0.0", "aar"),
    # viewpager 1.0.0's POM dep; AbsSavedState is ViewPager$SavedState's
    # superclass. No res/, no R references — classes only.
    (GOOGLE, "androidx.customview", "customview", "1.0.0", "aar"),
    # Referenced (signature) by concurrent-futures + androidx.core +
    # profileinstaller; comes transitively from concurrent-futures' POM in
    # real builds. Tiny standalone jar with the real ListenableFuture class.
    (CENTRAL, "com.google.guava", "listenablefuture", "1.0", "jar"),
    # ---- Ktor 3.0.3 jvm ----
    (CENTRAL, "io.ktor", "ktor-client-cio-jvm", "3.0.3", "jar"),
    (CENTRAL, "io.ktor", "ktor-client-content-negotiation-jvm", "3.0.3", "jar"),
    (CENTRAL, "io.ktor", "ktor-client-logging-jvm", "3.0.3", "jar"),
    (CENTRAL, "io.ktor", "ktor-serialization-kotlinx-json-jvm", "3.0.3", "jar"),
    (CENTRAL, "io.ktor", "ktor-network-jvm", "3.0.3", "jar"),
    # ktor-client-cio (JVM) references io.ktor.network.tls.* (TLSConfigBuilder)
    # and websocket serialization helpers; both live in separate -jvm jars
    # that the deps glob must carry or the dex misses them (BUILD_LOG v0.1.3).
    (CENTRAL, "io.ktor", "ktor-network-tls-jvm", "3.0.3", "jar"),
    (CENTRAL, "io.ktor", "ktor-websocket-serialization-jvm", "3.0.3", "jar"),
    (CENTRAL, "io.ktor", "ktor-websockets-jvm", "3.0.3", "jar"),
    # kotlinx-serialization-json references kotlinx/serialization/json/io/*
    # (IoStreamsKt) from this separate -jvm artifact.
    (CENTRAL, "org.jetbrains.kotlinx", "kotlinx-serialization-json-io-jvm", "1.7.3", "jar"),
    (CENTRAL, "io.ktor", "ktor-http-cio-jvm", "3.0.3", "jar"),
    (CENTRAL, "io.ktor", "ktor-serialization-kotlinx-jvm", "3.0.3", "jar"),
    # ---- Tink / pdfbox / misc ----
    (CENTRAL, "com.google.crypto.tink", "tink-android", "1.20.0", "aar"),
    (CENTRAL, "com.tom-roush", "pdfbox-android", "2.0.27.0", "aar"),
    (CENTRAL, "com.google.code.gson", "gson", "2.11.0", "jar"),
    (CENTRAL, "com.google.protobuf", "protobuf-java", "3.25.5", "jar"),
    (CENTRAL, "com.google.protobuf", "protobuf-javalite", "3.25.5", "jar"),
    (CENTRAL, "com.google.errorprone", "error_prone_annotations", "2.27.0", "jar"),
    (CENTRAL, "com.google.j2objc", "j2objc-annotations", "3.0.0", "jar"),
    (CENTRAL, "com.google.code.findbugs", "jsr305", "3.0.2", "jar"),
    (CENTRAL, "com.squareup.okio", "okio-jvm", "3.9.0", "jar"),
    (CENTRAL, "org.jetbrains.kotlinx", "kotlinx-coroutines-android", "1.9.0", "jar"),
    # The -android artifact carries ONLY kotlinx/coroutines/android/* (10 classes);
    # the core classes (SupervisorKt, Dispatchers, Flow, ...) live in the -core-jvm
    # jar. Omitting it shipped a NoClassDefFoundError crash in v0.1.0–0.1.1 (see
    # BUILD_LOG v0.1.2). This jar MUST be in deps/ so the package scripts dex it.
    (CENTRAL, "org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.9.0", "jar"),
    # ---- Room compiler + KSP (build-time only) ----
    (GOOGLE, "androidx.room", "room-compiler", "2.6.1", "jar"),
    (GOOGLE, "androidx.room", "room-compiler-processing", "2.6.1", "jar"),
    (GOOGLE, "androidx.room", "room-migration", "2.6.1", "jar"),
    (CENTRAL, "com.google.devtools.ksp", "symbol-processing-api", "2.0.21-1.0.28", "jar"),
    (CENTRAL, "com.google.devtools.ksp", "symbol-processing", "2.0.21-1.0.28", "jar"),
    (CENTRAL, "com.google.devtools.ksp", "symbol-processing-common-deps", "2.0.21-1.0.28", "jar"),
    (CENTRAL, "com.squareup", "kotlinpoet", "1.18.1", "jar"),
    (CENTRAL, "com.squareup", "kotlinpoet-ksp", "1.18.1", "jar"),
    (CENTRAL, "com.squareup", "javapoet", "1.13.0", "jar"),
    (CENTRAL, "com.google.auto.value", "auto-value-annotations", "1.11.0", "jar"),
    (CENTRAL, "org.jetbrains.kotlin", "kotlin-compose-compiler-plugin-embeddable", "2.0.21", "jar"),
    (CENTRAL, "androidx.annotation", "annotation", "1.9.1", "jar"),
]

fail, ok = [], 0
for repo, g, a, v, ext in DEPS:
    path = f"{g.replace('.', '/')}/{a}/{v}/{a}-{v}.{ext}"
    dest = os.path.join(OUT, f"{a}-{v}.{ext}")
    if os.path.exists(dest) and os.path.getsize(dest) > 1000:
        ok += 1; continue
    r = subprocess.run(["curl", "-sfL", "-o", dest, f"{repo}/{path}"],
                       capture_output=True, timeout=300)
    if r.returncode == 0 and os.path.getsize(dest) > 200:
        ok += 1
    else:
        fail.append(f"{g}:{a}:{v}.{ext}")
        if os.path.exists(dest): os.remove(dest)
print(f"OK={ok} FAIL={len(fail)}")
for f in fail: print("  MISSING:", f)

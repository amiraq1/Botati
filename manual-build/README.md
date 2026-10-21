# manual-build — scripts used for the VM-only manual APK pipeline

These are the exact scripts that produced `app/build/outputs/apk/debug/app-debug.apk`
in the Muse VM (see BUILD_LOG.md "Track B" for the narrative and evidence):

1. `fetch_deps.py` — downloads the pinned AAR/JAR dependency set with curl.
2. (aapt2 compile/link + library R generation — `gen_lib_r.py`.)
3. `build_app.sh` — full-app compile with kotlin-compiler-embeddable
   (Compose + serialization plugins; KSP options present but inert — Room
   impls came from `../manual-room-impl/` instead).
4. `package_apk.sh` — d8 dexing, resource/service merging, zipalign, signing.

They reference /tmp working directories and the VM toolchain layout
(`~/workspace/tools/…`). They are kept as reproducibility evidence, not as a
supported build path — the supported path is Gradle (`./gradlew assembleDebug`)
on a machine where the Gradle daemon can use loopback TCP.

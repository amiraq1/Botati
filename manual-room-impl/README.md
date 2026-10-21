# manual-room-impl — STOPGAP, not part of the Gradle build

Hand-written equivalents of the classes Room's compiler (KSP) would generate
(`BotatiDatabase_Impl`, `*_Dao_Impl`), written in the exact pattern of Room
2.6 generated code.

Why this exists: in the Muse VM, Gradle cannot run (client↔daemon loopback
TCP is denied by the VM's `other_tcp` permission), and both standalone KSP
(silently never engages outside Gradle) and kapt (javac metadata
ClassCastException on vanilla entities) failed to produce these classes.
The manual APK build therefore compiles these Java sources and dexes them
alongside the app classes.

**Do NOT add this directory to the Gradle source sets.** On a normal machine,
`./gradlew assembleDebug` runs KSP (`com.google.devtools.ksp`) and generates
the real implementations from `app/src/main/java/com/botati/data/db/`.
Keeping both would duplicate the classes.

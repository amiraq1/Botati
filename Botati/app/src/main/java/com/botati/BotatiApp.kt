package com.botati

import android.app.Application
import com.botati.crash.CrashReporter
import com.google.crypto.tink.aead.AeadConfig
import com.botati.data.repo.BotRepository
import com.botati.di.appModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.logger.Level
import org.koin.core.context.startKoin

class BotatiApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // v0.1.1 diagnostic build: the crash reporter goes in FIRST, before
        // startKoin — if DI, Room or seeding is what dies on a given device,
        // the trace still lands on disk for MainActivity to show.
        CrashReporter.install(this)
        CrashReporter.breadcrumb("app_oncreate_start")
        // Tink: named key templates ("AES256_GCM") are served from a registry
        // that only AeadConfig.register() fills. Without this, the Vault's
        // first KeyTemplates.get("AES256_GCM") throws GeneralSecurityException
        // ("Name AES256_GCM does not exist") — the 2026-10-21 save-key crash.
        // Must run before startKoin so no DI consumer can touch the Vault
        // first; register() is idempotent (Tink >= 1.7), so this is the one
        // and only call site.
        AeadConfig.register()
        val processStartMs = System.currentTimeMillis()
        startKoin {
            androidLogger(Level.ERROR)
            androidContext(this@BotatiApp)
            modules(appModule)
        }
        CrashReporter.breadcrumb("koin_started")
        // First-launch presets (§13) + process-death reconciliation (§5.7):
        // any task still marked RUNNING from a previous, now-dead process is
        // orphaned — mark INTERRUPTED with a report; never auto-resume it.
        //
        // Defensive rule (v0.1.1): startup seeding must NEVER be able to kill
        // the app. Any failure is written to the diagnostics file as a
        // non-fatal report and swallowed here.
        appScope.launch {
            try {
                CrashReporter.breadcrumb("seed_start")
                val repo = get<BotRepository>()
                repo.seedTemplatesIfEmpty()
                CrashReporter.breadcrumb("db_first_access_ok")
                repo.reconcileOrphanedTasks(processStartMs)
                CrashReporter.breadcrumb("seed_done")
            } catch (t: Throwable) {
                CrashReporter.reportNonFatal("startup seeding/reconcile failed", t)
            }
        }
    }
}

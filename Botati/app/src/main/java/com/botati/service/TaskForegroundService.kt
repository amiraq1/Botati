package com.botati.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.botati.data.db.BotatiDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.android.inject

/**
 * Foreground Service hosting autonomous task sessions (§11/§22) — tonight's
 * scope is the skeleton with the platform contract honored:
 *  - type `dataSync` chosen deliberately in the manifest;
 *  - heartbeat: every 30s the session row's lastHeartbeatAt is refreshed
 *    (the app-start reconciler uses it to detect dead processes, §5.7);
 *  - onTimeout(): Android 15 gives dataSync a 6h/24h budget and then calls
 *    this; we mark the session INTERRUPTED with a report and stopSelf()
 *    within the grace window, instead of dying by RemoteServiceException;
 *  - the run finishes/stops itself when the session leaves RUNNING.
 *
 * Full autonomous mail-task execution is phase 4 (needs the user's Gmail
 * OAuth consent, impossible while he sleeps); the Chat screen's task mode
 * already runs the engine under an approved contract in-process.
 */
class TaskForegroundService : Service() {

    private val db: BotatiDatabase by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeatJob: Job? = null
    private var currentTaskId: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Botati tasks", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val taskId = intent?.getStringExtra(EXTRA_TASK_ID) ?: run { stopSelf(); return START_NOT_STICKY }
        currentTaskId = taskId
        val notification = buildNotification("مهمة تعمل…")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (true) {
                val task = db.taskDao().byId(taskId)
                if (task == null || task.status != "RUNNING") break
                db.taskDao().heartbeat(taskId, System.currentTimeMillis())
                delay(30_000)
            }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /** Android 15 dataSync timeout (§22). A few seconds to stop cleanly. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        val taskId = currentTaskId
        if (taskId != null) {
            runBlocking(Dispatchers.IO) {
                db.taskDao().setStatus(
                    taskId, "INTERRUPTED",
                    System.currentTimeMillis(),
                )
                db.taskDao().upsert(
                    db.taskDao().byId(taskId)!!.copy(
                        finalReport = "INTERRUPTED: Android 15 dataSync timeout (onTimeout, §22).",
                    ),
                )
            }
        }
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Botati")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()

    companion object {
        private const val CHANNEL_ID = "botati_tasks"
        private const val NOTIF_ID = 1001
        private const val EXTRA_TASK_ID = "task_id"

        fun start(context: Context, taskId: String) {
            val intent = Intent(context, TaskForegroundService::class.java)
                .putExtra(EXTRA_TASK_ID, taskId)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TaskForegroundService::class.java))
        }
    }
}

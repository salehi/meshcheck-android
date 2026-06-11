package io.meshcheck.contributor.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import io.meshcheck.contributor.AppContainer
import io.meshcheck.contributor.MainActivity
import io.meshcheck.contributor.MeshCheckApplication
import io.meshcheck.contributor.R
import io.meshcheck.contributor.contribution.ConnectionLocks
import io.meshcheck.protocol.ConnectionState
import io.meshcheck.protocol.StopReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * The always-connected foreground service. While it runs it owns the
 * [io.meshcheck.protocol.AgentClient] — holding the WebSocket, executing
 * tasks, submitting results — and shows a persistent notification that
 * mirrors the connection state.
 *
 * Declared with the `specialUse` foreground-service type: a node must
 * contribute continuously, and Android 15 caps the `dataSync` type at 6h/day.
 */
class ContributionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var container: AppContainer
    private var running = false
    private var locks: ConnectionLocks? = null

    override fun onCreate() {
        super.onCreate()
        container = (application as MeshCheckApplication).container
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(container.agentClient.state.value))

        if (!running) {
            running = true
            val credentials = container.credentialStore.load()
            if (credentials == null) {
                // Not enrolled — nothing to contribute with.
                stopSelf()
                return START_NOT_STICKY
            }
            // Keep the CPU and Wi-Fi radio awake so heartbeats fire and the
            // socket survives the screen turning off.
            locks = ConnectionLocks(this).also { it.acquire() }
            observeConnectionState()
            container.agentClient.start(
                credentials.apiKey,
                credentials.ed25519PublicKey,
                credentials.gatewayUrl,
            )
        }
        // START_STICKY: if the OS kills us under memory pressure, it relaunches.
        return START_STICKY
    }

    override fun onDestroy() {
        locks?.release()
        locks = null
        container.agentClient.stop()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun observeConnectionState() {
        scope.launch {
            container.agentClient.state.collect { state ->
                notificationManager().notify(NOTIFICATION_ID, buildNotification(state))
            }
        }
    }

    private fun buildNotification(state: ConnectionState): Notification {
        val (title, detail) = when (state) {
            is ConnectionState.Connected ->
                "Contributing" to "Your phone is taking jobs."
            ConnectionState.Connecting ->
                "Connecting…" to "Reaching the MeshCheck network."
            is ConnectionState.Reconnecting ->
                "Reconnecting…" to "Lost the connection — retrying."
            ConnectionState.Idle ->
                "Paused" to "Not contributing right now."
            is ConnectionState.Stopped -> when (state.reason) {
                StopReason.REQUESTED -> "Paused" to "Not contributing right now."
                StopReason.UNAUTHORIZED -> "Not linked" to "Open the app to re-link this device."
                StopReason.OUTDATED -> "Update required" to "Update the app to keep contributing."
                StopReason.SHUTDOWN -> "Stopped" to "This node was suspended or revoked."
            }
        }
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            pendingIntentFlags(),
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(detail)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Contribution status",
                NotificationManager.IMPORTANCE_LOW,
            )
            channel.description = "Shows whether this device is contributing to MeshCheck."
            notificationManager().createNotificationChannel(channel)
        }
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun pendingIntentFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

    companion object {
        private const val CHANNEL_ID = "contribution_status"
        private const val NOTIFICATION_ID = 1
        private const val WATCHDOG_WORK = "meshcheck.contribution.watchdog"
        private const val WATCHDOG_INTERVAL_MINUTES = 15L

        /** Turns contribution on: records the user's intent, starts the
         *  service, and schedules the watchdog. */
        fun start(context: Context) {
            val app = context.applicationContext
            ContributionPrefs(app).userWantsConnected = true
            ContextCompat.startForegroundService(
                app,
                Intent(app, ContributionService::class.java),
            )
            scheduleWatchdog(app)
        }

        /** Turns contribution off: clears the intent, stops the service, and
         *  cancels the watchdog. Credentials are kept. */
        fun stop(context: Context) {
            val app = context.applicationContext
            ContributionPrefs(app).userWantsConnected = false
            app.stopService(Intent(app, ContributionService::class.java))
            WorkManager.getInstance(app).cancelUniqueWork(WATCHDOG_WORK)
        }

        private fun scheduleWatchdog(context: Context) {
            val request = PeriodicWorkRequest.Builder(
                ContributionWatchdogWorker::class.java,
                WATCHDOG_INTERVAL_MINUTES,
                TimeUnit.MINUTES,
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WATCHDOG_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}

package io.meshcheck.contributor.contribution

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager

/**
 * The CPU and Wi-Fi locks the contribution service holds while it is running.
 *
 * A foreground service keeps the *process* alive but does not keep the CPU or
 * Wi-Fi radio awake. When the screen turns off the device suspends: the
 * heartbeat/ping timers stop firing (the platform drops the connection after
 * three missed heartbeats) and the Wi-Fi radio power-saves out from under the
 * socket. A `PARTIAL_WAKE_LOCK` keeps the application processor running so those
 * timers fire on schedule, and a Wi-Fi lock keeps the radio from sleeping.
 *
 * The wake lock is process-wide, so it also keeps the native traceroute threads
 * ([io.meshcheck.checks]) running — no native-level lock is needed.
 *
 * Held for the whole service lifetime (not just while `Connected`): the CPU and
 * radio are needed most during the `Connecting`/`Reconnecting` gaps. The
 * deliberate battery cost is consistent with the "no scheduling controls in v1"
 * decision — the node contributes whenever it is Contributing.
 */
internal class ConnectionLocks(context: Context) {

    private val appContext = context.applicationContext

    private val wakeLock =
        (appContext.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "meshcheck:contribution-cpu")
            .apply { setReferenceCounted(false) }

    private val wifiLock =
        (appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(wifiLockMode(), "meshcheck:contribution-wifi")
            .apply { setReferenceCounted(false) }

    /**
     * Acquires both locks. Idempotent — a second call while already held is a
     * no-op. No timeout: this is an intentional lifetime lock released in
     * [release]; a timeout would re-introduce the Doze heartbeat stall this
     * exists to prevent.
     */
    @SuppressLint("WakelockTimeout")
    fun acquire() {
        if (!wakeLock.isHeld) wakeLock.acquire()
        if (!wifiLock.isHeld) wifiLock.acquire()
    }

    /** Releases both locks. Idempotent and safe to call before [acquire]. */
    fun release() {
        if (wakeLock.isHeld) runCatching { wakeLock.release() }
        if (wifiLock.isHeld) runCatching { wifiLock.release() }
    }

    private fun wifiLockMode(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            // FULL_LOW_LATENCY doesn't exist pre-29; HIGH_PERF is the correct fallback.
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
}

package io.meshcheck.contributor.contribution

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * The system battery-optimization exemption.
 *
 * Without it, aggressive OEMs (Xiaomi, Huawei, Oppo, older Samsung) kill the
 * foreground service shortly after the app is swiped away — defeating the
 * "keep contributing when closed" requirement. Battery optimization did not
 * exist before API 23, so a device below that is always considered exempt.
 */
internal object BatteryOptimization {

    fun isExempt(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Opens the system "don't optimize this app" dialog. */
    fun requestExemption(context: Context) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}

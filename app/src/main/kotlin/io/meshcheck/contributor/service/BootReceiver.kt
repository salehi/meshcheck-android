package io.meshcheck.contributor.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts contribution after a device reboot, if the user had it switched on. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (ContributionPrefs(context).userWantsConnected) {
            ContributionService.start(context)
        }
    }
}

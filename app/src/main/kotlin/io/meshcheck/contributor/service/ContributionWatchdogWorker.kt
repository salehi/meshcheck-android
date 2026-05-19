package io.meshcheck.contributor.service

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * Periodic watchdog: re-launches [ContributionService] if it is down while the
 * user still wants to contribute.
 *
 * Best-effort only — background foreground-service starts are restricted on
 * newer Android — so this complements `START_STICKY` and the boot receiver
 * rather than guaranteeing uptime.
 */
class ContributionWatchdogWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    override fun doWork(): Result {
        if (ContributionPrefs(applicationContext).userWantsConnected) {
            runCatching { ContributionService.start(applicationContext) }
        }
        return Result.success()
    }
}

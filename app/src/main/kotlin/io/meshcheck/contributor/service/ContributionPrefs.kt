package io.meshcheck.contributor.service

import android.content.Context

/**
 * Persists whether the user wants this device contributing.
 *
 * This is the one flag that separates "the UI is closed" from "stop
 * contributing": the boot receiver and the watchdog only (re)start the
 * service when it is `true`. It survives the UI closing, the service being
 * killed, and reboots.
 */
class ContributionPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var userWantsConnected: Boolean
        get() = prefs.getBoolean(KEY_WANTS_CONNECTED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_WANTS_CONNECTED, value).apply()
        }

    private companion object {
        const val PREFS_NAME = "meshcheck.contribution"
        const val KEY_WANTS_CONNECTED = "user_wants_connected"
    }
}

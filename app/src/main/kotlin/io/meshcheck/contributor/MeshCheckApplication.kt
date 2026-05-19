package io.meshcheck.contributor

import android.app.Application
import io.meshcheck.checks.Dns

/**
 * Process entry point. Builds the [AppContainer] once and holds it for the
 * lifetime of the process.
 */
class MeshCheckApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Hand dnsjava the device's DNS servers before any dns check runs.
        Dns.configure(this)
    }
}

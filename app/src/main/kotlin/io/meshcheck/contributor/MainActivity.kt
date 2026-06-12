package io.meshcheck.contributor

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import io.meshcheck.contributor.ui.theme.MeshCheckTheme

/**
 * The app's single Activity. It only hosts Compose and routes between the
 * enrollment flow and the (later) contributor screen; all real logic lives in
 * the composables and the [AppContainer].
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as MeshCheckApplication).container
        // Capture a launch-time deep link before Compose starts collecting, so
        // the pending payload is already in place on first composition.
        handleDeepLink(intent)
        setContent {
            val themeMode by container.themePrefs.mode.collectAsState()
            MeshCheckTheme(themeMode) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MeshCheckApp(container)
                }
            }
        }
    }

    // singleTask delivers deep links to the running instance here rather than
    // recreating the Activity.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    /**
     * Routes a `meshcheck://enroll#<payload>` deep link into the enrollment
     * flow. The payload rides in the URI fragment; [encodedFragment] gives the
     * raw base64 verbatim (URL-safe base64 has no percent-escapes to decode).
     * The Compose enrollment UI picks it up via [AppContainer.pendingEnrollment].
     */
    private fun handleDeepLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val payload = intent.data?.encodedFragment?.takeIf { it.isNotBlank() } ?: return
        (application as MeshCheckApplication).container.offerEnrollmentPayload(payload)
    }
}

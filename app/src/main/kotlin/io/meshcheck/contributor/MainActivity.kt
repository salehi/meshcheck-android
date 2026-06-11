package io.meshcheck.contributor

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
        setContent {
            val themeMode by container.themePrefs.mode.collectAsState()
            MeshCheckTheme(themeMode) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MeshCheckApp(container)
                }
            }
        }
    }
}

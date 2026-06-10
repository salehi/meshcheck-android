package io.meshcheck.contributor.diagnostics

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.meshcheck.core.diagnostics.AppLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A read-only viewer for [AppLog]. Surfaced only from debug builds (see
 * `MeshCheckApp`), it shows the agent and enrollment log so connection and
 * redeem problems can be diagnosed on-device without a cable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(onClose: () -> Unit) {
    val entries by AppLog.entries.collectAsState()
    val listState = rememberLazyListState()

    // Keep the newest line in view as entries arrive.
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.scrollToItem(entries.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Logs") },
                navigationIcon = { TextButton(onClick = onClose) { Text("Close") } },
                actions = { TextButton(onClick = { AppLog.clear() }) { Text("Clear") } },
            )
        },
    ) { padding ->
        if (entries.isEmpty()) {
            Column(modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
                Text(
                    "No log entries yet. Scan an enrollment QR code or start " +
                        "contributing to see what the app is doing.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                items(entries) { entry -> LogRow(entry) }
            }
        }
    }
}

@Composable
private fun LogRow(entry: AppLog.Entry) {
    Text(
        text = "${TIME_FORMAT.format(Date(entry.atMillis))} ${entry.tag}: ${entry.message}",
        color = entry.level.color(),
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
    )
}

private fun AppLog.Level.color(): Color = when (this) {
    AppLog.Level.DEBUG -> Color(0xFF9E9E9E)
    AppLog.Level.INFO -> Color(0xFF263238)
    AppLog.Level.WARN -> Color(0xFFE65100)
    AppLog.Level.ERROR -> Color(0xFFC62828)
}

private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

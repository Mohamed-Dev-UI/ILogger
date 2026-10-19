package com.mohamed.dev.ui.ilogger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mohamed.dev.ui.ilogger.logging.AppLogger
import com.mohamed.dev.ui.ilogger.logging.InMemoryTaskRepository
import com.mohamed.dev.ui.ilogger.logging.Task
import com.mohamed.dev.ui.ilogger.logging.logged
import com.mohamed.dev.ui.ilogger.ui.theme.ILoggerTheme
import io.ilogger.core.Actor
import io.ilogger.core.withActor
import io.ilogger.core.withCorrelation
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ILoggerTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    TaskAuditScreen(Modifier.padding(innerPadding))
                }
            }
        }
    }
}

/**
 * Buttons emit real audit events through the generated wrappers; the app-owned
 * sink collects them into Compose state (latest first). Ambient actor context
 * is established per interaction, exactly like `withActor` in any backend.
 */
@Composable
fun TaskAuditScreen(modifier: Modifier = Modifier) {
    val events by AppLogger.eventLines.collectAsState()
    val scope = rememberCoroutineScope()
    val counter = remember { mutableIntStateOf(0) }
    val repository = remember { InMemoryTaskRepository().logged(AppLogger.logger) }

    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("ILogger — annotation-driven audit", style = MaterialTheme.typography.titleMedium)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    withActor(Actor.user("demo", name = "Demo")) {
                        repository.create("repair coffee machine #${counter.intValue++}")
                    }
                }
            }) { Text("Create") }

            OutlinedButton(onClick = {
                scope.launch {
                    withActor(Actor.user("demo", name = "Demo")) {
                        repository.update(
                            Task(id = 1, title = "stale title", passwordHash = "x"),
                            "renamed title",
                        )
                    }
                }
            }) { Text("Rename (before→after)") }

            OutlinedButton(onClick = {
                scope.launch {
                    withCorrelation("local-failure-flow") {
                        runCatching { repository.explode(taskId = 1L) }
                    }
                }
            }) { Text("Fail") }
        }

        Text(
            "Events (latest first):",
            style = MaterialTheme.typography.labelLarge,
        )
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(events.size) { index ->
                Text(
                    text = events[index],
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

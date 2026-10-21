package com.mohamed.dev.ui.ilogger.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mohamed.dev.ui.ilogger.ui.components.Pill
import com.mohamed.dev.ui.ilogger.ui.components.oneLine
import com.mohamed.dev.ui.ilogger.ui.screens.AuditTrailScreen
import com.mohamed.dev.ui.ilogger.ui.screens.EventLogScreen
import com.mohamed.dev.ui.ilogger.ui.screens.FileAuditScreen
import com.mohamed.dev.ui.ilogger.ui.screens.LabScreen
import com.mohamed.dev.ui.ilogger.ui.screens.OverviewScreen
import com.mohamed.dev.ui.ilogger.ui.screens.ProtectionScreen
import com.mohamed.dev.ui.ilogger.ui.screens.RecipesScreen
import com.mohamed.dev.ui.ilogger.ui.screens.TaskBoardScreen
import com.mohamed.dev.ui.ilogger.ui.screens.TestKitScreen
import kotlinx.coroutines.launch

enum class DemoDestination(
    val label: String,
    val subtitle: String,
    val glyph: String,
) {
    OVERVIEW("Overview", "Audit by construction, end to end", "◈"),
    TASKS("Task board", "Annotated repository + Room DAO", "▣"),
    EVENTS("Live events", "Everything the engine emits", "≋"),
    ROOM("Room audit", "Durable, queryable history", "▤"),
    FILES("JSONL files", "Category-routed file sink", "▥"),
    PROTECTION("Protection", "Sensitive values stay compile-safe", "⛉"),
    LAB("Engine lab", "Overflow, retries, sampling, off-switch", "⚙"),
    TESTKIT("TestKit", "Deterministic checks, run live", "✓"),
    RECIPES("Recipes", "Copy-ready integration code", "{}"),
}

@Composable
fun DemoApp(
    controller: DemoController,
    audit: AuditTrailController,
    files: FileAuditController,
    lab: EngineLabController,
    tests: TestKitController,
) {
    val destinationName = rememberSaveable { mutableStateOf(DemoDestination.TASKS.name) }
    val destination = DemoDestination.entries.firstOrNull { it.name == destinationName.value }
        ?: DemoDestination.TASKS
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val events by controller.feed.events.collectAsState()
    val issues by controller.feed.dispatchIssues.collectAsState()
    val metrics by controller.metrics.snapshot.collectAsState()
    val dropped by controller.feed.droppedCount.collectAsState()
    val paused = remember { mutableStateOf(false) }

    fun navigate(target: DemoDestination) {
        destinationName.value = target.name
        scope.launch { drawerState.close() }
    }

    LaunchedEffect(controller) {
        controller.messages.collect { snackbar.showSnackbar(it) }
    }
    LaunchedEffect(audit) {
        audit.messages.collect { snackbar.showSnackbar(it) }
    }
    LaunchedEffect(files) {
        files.messages.collect { snackbar.showSnackbar(it) }
    }
    LaunchedEffect(destination) {
        if (destination != DemoDestination.EVENTS && paused.value) {
            controller.setFeedPaused(false)
            paused.value = false
        }
    }

    BackHandler(enabled = destination != DemoDestination.OVERVIEW) {
        destinationName.value = DemoDestination.OVERVIEW.name
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(304.dp)) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 24.dp)) {
                    Text("ILogger", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "annotation-driven audit",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill("v0.1.0", MaterialTheme.colorScheme.primary)
                        Pill("${metrics.total} events", MaterialTheme.colorScheme.secondary)
                    }
                }
                HorizontalDivider()
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    DemoDestination.entries.forEach { item ->
                        NavigationDrawerItem(
                            label = { Text(item.label) },
                            icon = {
                                Text(
                                    item.glyph,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (item == destination) {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            },
                            selected = item == destination,
                            onClick = { navigate(item) },
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
                        )
                    }
                }
                HorizontalDivider()
                Text(
                    "Room · JSONL · Logcat · live feed\n${metrics.failures} failures captured",
                    modifier = Modifier.padding(20.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopBar(
                    title = destination.label,
                    subtitle = destination.subtitle,
                    liveCount = metrics.total,
                    hasIssues = issues.isNotEmpty(),
                    onMenu = { scope.launch { drawerState.open() } },
                )
            },
            bottomBar = {
                LiveTicker(
                    line = events.firstOrNull()?.let(::oneLine),
                    total = metrics.total,
                    paused = paused.value,
                    hasIssues = issues.isNotEmpty(),
                    onClick = { navigate(DemoDestination.EVENTS) },
                )
            },
        ) { padding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.background),
            ) {
                key(destination) {
                    when (destination) {
                        DemoDestination.OVERVIEW -> OverviewScreen(
                            metrics = metrics,
                            recentEvents = events,
                            droppedCount = dropped,
                            onNavigate = ::navigate,
                        )
                        DemoDestination.TASKS -> TaskBoardScreen(controller)
                        DemoDestination.EVENTS -> EventLogScreen(
                            controller = controller,
                            onPausedChange = { paused.value = it },
                        )
                        DemoDestination.ROOM -> AuditTrailScreen(audit)
                        DemoDestination.FILES -> FileAuditScreen(files)
                        DemoDestination.PROTECTION -> ProtectionScreen(controller)
                        DemoDestination.LAB -> LabScreen(lab)
                        DemoDestination.TESTKIT -> TestKitScreen(tests)
                        DemoDestination.RECIPES -> RecipesScreen()
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(
    title: String,
    subtitle: String,
    liveCount: Int,
    hasIssues: Boolean,
    onMenu: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(62.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onMenu) {
                Text("☰", fontSize = 20.sp)
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Pill(
                if (hasIssues) "issues" else "$liveCount live",
                if (hasIssues) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 10.dp),
            )
        }
    }
}

@Composable
private fun LiveTicker(
    line: String?,
    total: Int,
    paused: Boolean,
    hasIssues: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .background(
                        when {
                            paused -> MaterialTheme.colorScheme.outline
                            hasIssues -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.primary
                        },
                        CircleShape,
                    ),
            )
            Text(
                when {
                    paused -> "Feed paused — tap to inspect"
                    line == null -> "Waiting for the first event…"
                    else -> line
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (line == null && !paused) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
            )
            Text(
                total.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

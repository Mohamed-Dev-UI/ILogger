package com.mohamed.dev.ui.ilogger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mohamed.dev.ui.ilogger.ui.AuditTrailController
import com.mohamed.dev.ui.ilogger.ui.DemoApp
import com.mohamed.dev.ui.ilogger.ui.DemoController
import com.mohamed.dev.ui.ilogger.ui.EngineLabController
import com.mohamed.dev.ui.ilogger.ui.FileAuditController
import com.mohamed.dev.ui.ilogger.ui.TestKitController
import com.mohamed.dev.ui.ilogger.ui.theme.ILoggerTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Single-activity showcase. Each controller is application-scoped, so an
 * in-flight audited operation survives rotation and drawer navigation; the
 * Composable layer only renders state.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var demo: DemoController

    @Inject
    lateinit var audit: AuditTrailController

    @Inject
    lateinit var files: FileAuditController

    @Inject
    lateinit var lab: EngineLabController

    @Inject
    lateinit var tests: TestKitController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ILoggerTheme {
                DemoApp(
                    controller = demo,
                    audit = audit,
                    files = files,
                    lab = lab,
                    tests = tests,
                )
            }
        }
    }
}

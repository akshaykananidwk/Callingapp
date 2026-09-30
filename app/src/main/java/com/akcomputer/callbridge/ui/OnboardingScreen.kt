package com.akcomputer.callbridge.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.akcomputer.callbridge.service.CallBridgeService

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }

    val statuses = remember(refresh) { ALL_RUNTIME_PERMISSIONS.associateWith { granted(ctx, it) } }
    val battery = remember(refresh) { isIgnoringBatteryOptimizations(ctx) }
    val coreOk = remember(refresh) { hasCorePermissions(ctx) }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Welcome to CallBridge", style = MaterialTheme.typography.headlineMedium)
            Text("Live transcripts of your phone calls, processed on your own server.")

            SectionTitle("1 · Permissions")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    statuses.forEach { (perm, ok) ->
                        Text("${if (ok) "✅" else "⬜"}  ${PERMISSION_LABELS[perm] ?: perm}")
                    }
                    Spacer(Modifier.height(4.dp))
                    Button(onClick = { launcher.launch(ALL_RUNTIME_PERMISSIONS.toTypedArray()) }) {
                        Text("Grant all permissions")
                    }
                    OutlinedButton(onClick = { openAppSettings(ctx) }) { Text("Open app settings") }
                }
            }

            SectionTitle("2 · Battery")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${if (battery) "✅" else "⬜"}  Battery optimization disabled")
                    Text(
                        "Samsung S25 Ultra: also set Apps → CallBridge → Battery → Unrestricted, and " +
                            "Permissions → Microphone → Allow.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = { requestIgnoreBatteryOptimizations(ctx) }) {
                        Text("Disable battery optimization")
                    }
                }
            }

            SectionTitle("3 · Server")
            ServerFields()

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    runCatching { CallBridgeService.start(ctx) }
                    onDone()
                },
                enabled = coreOk,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Start CallBridge") }
            if (!coreOk) {
                Text(
                    "Phone state and microphone permissions are required to continue.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

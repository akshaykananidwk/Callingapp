package com.akcomputer.callbridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.akcomputer.callbridge.core.Api
import com.akcomputer.callbridge.core.Prefs
import kotlinx.coroutines.launch

val LANGUAGES = listOf("auto" to "Auto", "gu" to "ગુજરાતી", "hi" to "हिन्दी", "en" to "English")
val AUDIO_SOURCES = listOf(
    "auto" to "Auto",
    "voice_communication" to "Voice comm.",
    "voice_call" to "Voice call",
    "voice_recognition" to "Voice recog.",
    "mic" to "Mic",
)

@Composable
fun ServerFields() {
    var url by remember { mutableStateOf(Prefs.baseUrl) }
    var token by remember { mutableStateOf(Prefs.token) }
    OutlinedTextField(
        value = url,
        onValueChange = { url = it; Prefs.baseUrl = it },
        label = { Text("VPS URL") },
        supportingText = { Text("API: $url/api · Stream: ${Prefs.streamUrl}") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = token,
        onValueChange = { token = it; Prefs.token = it },
        label = { Text("API token") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var language by remember { mutableStateOf(Prefs.language) }
    var source by remember { mutableStateOf(Prefs.audioSource) }
    var liveNotif by remember { mutableStateOf(Prefs.liveNotification) }
    var autoOpen by remember { mutableStateOf(Prefs.autoOpenLive) }
    var testResult by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        SectionTitle("Server")
        ServerFields()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                testResult = "Testing…"
                scope.launch {
                    testResult = try { Api.deviceStatus(); "✓ Connected" } catch (e: Exception) { "✗ ${e.message}" }
                }
            }) { Text("Test connection") }
            testResult?.let { Text(it, modifier = Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodySmall) }
        }

        SectionTitle("Transcript language")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LANGUAGES.forEach { (k, label) ->
                FilterChip(selected = language == k, onClick = { language = k; Prefs.language = k }, label = { Text(label) })
            }
        }

        SectionTitle("Audio source")
        Text(
            "Auto tries Voice communication → Voice call → Voice recognition → Mic. " +
                "If transcripts are empty during calls, try another source (applies from the next call).",
            style = MaterialTheme.typography.bodySmall,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AUDIO_SOURCES.forEach { (k, label) ->
                FilterChip(selected = source == k, onClick = { source = k; Prefs.audioSource = k }, label = { Text(label) })
            }
        }

        SectionTitle("Notifications")
        ToggleRow("Show live transcript in notification", liveNotif) { liveNotif = it; Prefs.liveNotification = it }
        ToggleRow("Open live screen when a call starts", autoOpen) { autoOpen = it; Prefs.autoOpenLive = it }

        DemoSettings()

        SectionTitle("Keep running in background")
        Text(
            "Samsung (One UI): Settings → Apps → CallBridge → Battery → Unrestricted, and " +
                "Permissions → Microphone → Allow. OnePlus: Battery → Advanced → allow background activity.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = { requestIgnoreBatteryOptimizations(ctx) }, modifier = Modifier.fillMaxWidth()) {
            Text(if (isIgnoringBatteryOptimizations(ctx)) "Battery optimization: disabled ✓" else "Disable battery optimization")
        }
        OutlinedButton(onClick = { openAppSettings(ctx) }, modifier = Modifier.fillMaxWidth()) {
            Text("App permissions")
        }
        OutlinedButton(onClick = { openAccessibilitySettings(ctx) }, modifier = Modifier.fillMaxWidth()) {
            Text("Call audio helper (Accessibility, optional)")
        }
        Text(
            "If the other side of the call is silent in transcripts, enable \"CallBridge call audio helper\" " +
                "under Accessibility → Installed apps.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

package com.akcomputer.callbridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.akcomputer.callbridge.service.CallBridgeService
import com.akcomputer.callbridge.service.GatewayCheck

@Composable
fun GatewayCheckCard() {
    var result by remember { mutableStateOf<GatewayCheck.Result?>(null) }
    SectionTitle("Use this phone as a line gateway")
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Checks (without root) whether this phone can send audio straight into a call, " +
                    "so the website can talk through it like a GSM gateway.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = {
                val r = GatewayCheck.run()
                result = r
                CallBridgeService.instance?.log(
                    "Gateway check: qualcomm=${r.qualcomm} soc=${r.soc} incall_music_uplink=${r.incallMusicUplink}"
                )
            }) { Text("Run line gateway check") }
            result?.let { r ->
                Text("Chip: ${r.soc.ifBlank { "unknown" }} · Qualcomm: ${if (r.qualcomm) "yes" else "no"}")
                Text(
                    "In-call audio route: " + when (r.incallMusicUplink) {
                        true -> "found ✓"
                        false -> "not found ✗"
                        null -> "could not read"
                    }
                )
                Text(
                    r.verdict,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (r.incallMusicUplink == true && r.qualcomm) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                )
                r.policyFile?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

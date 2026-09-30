package com.akcomputer.callbridge.ui

import android.Manifest
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import com.akcomputer.callbridge.core.Prefs
import com.akcomputer.callbridge.service.DemoMode
import kotlinx.coroutines.delay
import java.io.File

private const val MAX_RECORD_SEC = 60

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DemoSettings() {
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(Prefs.demoEnabled) }
    var autoAnswer by remember { mutableStateOf(Prefs.demoAutoAnswer) }
    var delaySec by remember { mutableIntStateOf(Prefs.demoAnswerDelaySec) }
    var numbers by remember { mutableStateOf(Prefs.demoNumbers) }
    var outgoing by remember { mutableStateOf(Prefs.demoOnOutgoing) }
    var audioVersion by remember { mutableIntStateOf(0) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordSec by remember { mutableIntStateOf(0) }
    var preview by remember { mutableStateOf<MediaPlayer?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var permTick by remember { mutableIntStateOf(0) }

    val file = DemoMode.audioFile(ctx)
    val durationSec = remember(audioVersion) { audioDurationSec(file) }
    val canAnswer = remember(permTick) { granted(ctx, Manifest.permission.ANSWER_PHONE_CALLS) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permTick++ }
    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            message = try {
                ctx.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
                audioVersion++
                "Demo audio saved"
            } catch (e: Exception) {
                "Could not read file: ${e.message}"
            }
        }
    }

    fun stopRecording() {
        recorder?.let {
            try { it.stop() } catch (_: Exception) { file.delete() }
            it.release()
        }
        recorder = null
        audioVersion++
    }

    fun stopPreview() {
        preview?.release()
        preview = null
    }

    LaunchedEffect(recorder) {
        recordSec = 0
        while (recorder != null) {
            delay(1000)
            recordSec++
            if (recordSec >= MAX_RECORD_SEC) stopRecording()
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            recorder?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
            preview?.release()
        }
    }

    SectionTitle("Demo / test mode")
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Demo mode", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Auto-answer calls and play the demo clip on loudspeaker in a loop",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = enabled, onCheckedChange = { enabled = it; Prefs.demoEnabled = it })
            }

            // ---- Demo audio ----
            Text("Demo audio", style = MaterialTheme.typography.titleSmall)
            Text(
                when {
                    recorder != null -> "● Recording… ${recordSec}s (max ${MAX_RECORD_SEC}s)"
                    durationSec != null -> "Saved clip: ${formatDuration(durationSec)}"
                    else -> "No clip yet — record one or choose an audio file"
                },
                color = if (recorder != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (recorder == null) {
                    Button(onClick = {
                        if (!granted(ctx, Manifest.permission.RECORD_AUDIO)) {
                            message = "Microphone permission is needed"; return@Button
                        }
                        stopPreview()
                        message = null
                        recorder = startRecorder(ctx, file).also { if (it == null) message = "Could not start recording" }
                    }) { Text("Record") }
                } else {
                    Button(
                        onClick = { stopRecording() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) { Text("Stop") }
                }
                OutlinedButton(onClick = { pickLauncher.launch("audio/*") }, enabled = recorder == null) {
                    Text("Choose file")
                }
                if (durationSec != null && recorder == null) {
                    OutlinedButton(onClick = {
                        if (preview != null) stopPreview() else {
                            preview = MediaPlayer().apply {
                                setDataSource(file.absolutePath)
                                setOnCompletionListener { stopPreview() }
                                prepare(); start()
                            }
                        }
                    }) { Text(if (preview != null) "Stop preview" else "Play preview") }
                }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            // ---- Options ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Auto-answer incoming calls", Modifier.weight(1f))
                Switch(checked = autoAnswer, onCheckedChange = { autoAnswer = it; Prefs.demoAutoAnswer = it })
            }
            if (autoAnswer && !canAnswer) {
                OutlinedButton(onClick = { permLauncher.launch(Manifest.permission.ANSWER_PHONE_CALLS) }) {
                    Text("Allow CallBridge to answer calls")
                }
            }
            if (autoAnswer) {
                Text("Answer after", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 2, 5, 10).forEach { s ->
                        FilterChip(
                            selected = delaySec == s,
                            onClick = { delaySec = s; Prefs.demoAnswerDelaySec = s },
                            label = { Text("$s s") },
                        )
                    }
                }
            }
            OutlinedTextField(
                value = numbers,
                onValueChange = { numbers = it; Prefs.demoNumbers = it },
                label = { Text("Only these numbers (optional)") },
                supportingText = { Text("Comma separated. Blank = every incoming call gets the demo.") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Also play on outgoing calls", Modifier.weight(1f))
                Switch(checked = outgoing, onCheckedChange = { outgoing = it; Prefs.demoOnOutgoing = it })
            }
            Text(
                "Android does not let apps send audio straight into a phone call, so the clip is played " +
                    "on the loudspeaker and reaches the caller through the microphone. Keep the phone in a " +
                    "quiet place, screen up. If the speaker does not switch on by itself, tap Speaker on the call screen.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (enabled && numbers.isBlank() && autoAnswer) {
                Text(
                    "⚠ Every incoming call will be answered automatically with the demo clip.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Suppress("DEPRECATION")
private fun startRecorder(ctx: android.content.Context, file: File): MediaRecorder? = try {
    val r = if (android.os.Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
    r.setAudioSource(MediaRecorder.AudioSource.MIC)
    r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
    r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
    r.setAudioSamplingRate(44_100)
    r.setAudioEncodingBitRate(96_000)
    r.setOutputFile(file.absolutePath)
    r.prepare()
    r.start()
    r
} catch (e: Exception) {
    null
}

private fun audioDurationSec(file: File): Int? {
    if (!file.exists() || file.length() == 0L) return null
    return try {
        MediaMetadataRetriever().run {
            setDataSource(file.absolutePath)
            val ms = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            release()
            ms?.let { (it / 1000).toInt().coerceAtLeast(1) }
        }
    } catch (e: Exception) {
        null
    }
}

package com.akcomputer.callbridge.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.akcomputer.callbridge.core.Api
import com.akcomputer.callbridge.core.CallRecord
import com.akcomputer.callbridge.core.LocalHistory
import com.akcomputer.callbridge.core.RemoteCall
import com.akcomputer.callbridge.core.Segment

@Composable
fun LocalTranscriptScreen(record: CallRecord, onBack: () -> Unit) {
    val ctx = LocalContext.current
    TranscriptScaffold(
        title = record.title(),
        subtitle = record.subtitle(),
        segments = record.segments,
        loading = false,
        error = null,
        onBack = onBack,
        onShare = { shareText(ctx, transcriptShareText(record.title(), record.subtitle(), record.segments)) },
        onDelete = { LocalHistory.delete(record.id); onBack() },
    )
}

@Composable
fun RemoteTranscriptScreen(call: RemoteCall, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var segments by remember { mutableStateOf<List<Segment>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(call.id) {
        try { segments = Api.transcript(call.id) } catch (e: Exception) { error = e.message }
        loading = false
    }
    val title = call.number ?: "Unknown number"
    val subtitle = listOfNotNull(call.direction, call.startedAt, call.durationSec?.let { formatDuration(it) })
        .joinToString(" · ")
    TranscriptScaffold(
        title, subtitle, segments, loading, error, onBack,
        onShare = { shareText(ctx, transcriptShareText(title, subtitle, segments)) },
        onDelete = null,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TranscriptScaffold(
    title: String,
    subtitle: String,
    segments: List<Segment>,
    loading: Boolean,
    error: String?,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title)
                        Text(subtitle, style = MaterialTheme.typography.bodySmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = onShare, enabled = segments.isNotEmpty()) { Icon(Icons.Filled.Share, "Share") }
                    if (onDelete != null) IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Delete") }
                },
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                error != null -> Text("Error: $error", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                segments.isEmpty() -> Text("No transcript for this call.", modifier = Modifier.padding(16.dp))
                else -> SelectionContainer {
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                        items(segments) { seg ->
                            Row(Modifier.padding(vertical = 6.dp)) {
                                Text(
                                    formatOffset(seg.offsetMs),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(end = 10.dp, top = 2.dp),
                                )
                                Text(seg.text, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
        }
    }
}

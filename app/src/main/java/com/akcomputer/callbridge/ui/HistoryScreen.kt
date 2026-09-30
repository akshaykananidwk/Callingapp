package com.akcomputer.callbridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.akcomputer.callbridge.core.Api
import com.akcomputer.callbridge.core.CallRecord
import com.akcomputer.callbridge.core.LocalHistory
import com.akcomputer.callbridge.core.RemoteCall
import kotlinx.coroutines.delay

private val DATE_FILTERS = listOf("All" to 0, "Today" to 1, "7 days" to 7, "30 days" to 30)

@Composable
fun HistoryScreen(onOpenLocal: (CallRecord) -> Unit, onOpenRemote: (RemoteCall) -> Unit) {
    var source by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var days by rememberSaveable { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = source) {
            Tab(selected = source == 0, onClick = { source = 0 }, text = { Text("This phone") })
            Tab(selected = source == 1, onClick = { source = 1 }, text = { Text("Server") })
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            label = { Text(if (source == 0) "Filter by number or text" else "Search number or transcript") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DATE_FILTERS.forEach { (label, d) ->
                FilterChip(selected = days == d, onClick = { days = d }, label = { Text(label) })
            }
        }
        if (source == 0) LocalList(query, days, onOpenLocal) else RemoteList(query, days, onOpenRemote)
    }
}

private fun cutoff(days: Int): Long {
    if (days == 0) return 0
    val cal = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        add(java.util.Calendar.DAY_OF_YEAR, -(days - 1))
    }
    return cal.timeInMillis
}

@Composable
private fun LocalList(query: String, days: Int, onOpen: (CallRecord) -> Unit) {
    val records by LocalHistory.records.collectAsState()
    val q = query.trim()
    val from = cutoff(days)
    val filtered = records.filter { r ->
        r.startedAt >= from && (q.isEmpty() ||
            (r.number?.contains(q) == true) ||
            r.transcriptText.contains(q, ignoreCase = true))
    }
    if (filtered.isEmpty()) {
        Empty("No calls found.")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(filtered, key = { it.id }) { r ->
            CallRow(
                r.title(),
                r.subtitle() + if (!r.synced) " · not synced" else "",
                r.transcriptText.ifBlank { "(no transcript)" },
            ) { onOpen(r) }
            HorizontalDivider()
        }
    }
}

@Composable
private fun RemoteList(query: String, days: Int, onOpen: (RemoteCall) -> Unit) {
    var items by remember { mutableStateOf<List<RemoteCall>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(query, days, reload) {
        delay(400) // debounce typing
        loading = true
        error = null
        val q = query.trim()
        val fromDate = if (days == 0) null else java.time.Instant.ofEpochMilli(cutoff(days)).toString()
        try {
            items = when {
                q.isEmpty() -> Api.calls(fromDate = fromDate)
                q.all { it.isDigit() || it == '+' } -> Api.calls(number = q, fromDate = fromDate)
                else -> Api.search(q)
            }
        } catch (e: Exception) {
            error = e.message ?: "Request failed"
        }
        loading = false
    }

    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            when {
                loading -> "Loading…"
                error != null -> "Error: $error"
                else -> "${items.size} calls"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
        IconButton(onClick = { reload++ }) { Icon(Icons.Filled.Refresh, "Refresh") }
    }
    if (loading && items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(items, key = { it.id + (it.snippet ?: "") }) { c ->
            CallRow(
                c.number ?: "Unknown number",
                listOfNotNull(
                    c.direction?.replaceFirstChar { it.uppercase() },
                    c.startedAt,
                    c.durationSec?.let { formatDuration(it) },
                ).joinToString(" · "),
                c.snippet,
            ) { onOpen(c) }
            HorizontalDivider()
        }
    }
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

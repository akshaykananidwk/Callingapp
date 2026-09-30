package com.akcomputer.callbridge.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.akcomputer.callbridge.core.CallRecord
import com.akcomputer.callbridge.core.LiveState
import com.akcomputer.callbridge.core.Prefs
import com.akcomputer.callbridge.core.RemoteCall

private data class Tab(val key: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("home", "Home", Icons.Filled.Home),
    Tab("live", "Live", Icons.Filled.Call),
    Tab("history", "History", Icons.AutoMirrored.Filled.List),
    Tab("settings", "Settings", Icons.Filled.Settings),
)

@Composable
fun AppRoot(requestedScreen: MutableState<String?>) {
    var onboarded by remember { mutableStateOf(Prefs.onboarded) }
    if (!onboarded) {
        OnboardingScreen(onDone = { Prefs.onboarded = true; onboarded = true })
        return
    }

    var tab by rememberSaveable { mutableStateOf("home") }
    var localDetail by remember { mutableStateOf<CallRecord?>(null) }
    var remoteDetail by remember { mutableStateOf<RemoteCall?>(null) }
    val call by LiveState.call.collectAsState()

    LaunchedEffect(requestedScreen.value) {
        requestedScreen.value?.let {
            tab = it
            localDetail = null
            remoteDetail = null
            requestedScreen.value = null
        }
    }
    LaunchedEffect(call?.localId) {
        if (call != null && Prefs.autoOpenLive) {
            tab = "live"; localDetail = null; remoteDetail = null
        }
    }

    localDetail?.let { rec ->
        BackHandler { localDetail = null }
        LocalTranscriptScreen(rec, onBack = { localDetail = null })
        return
    }
    remoteDetail?.let { rc ->
        BackHandler { remoteDetail = null }
        RemoteTranscriptScreen(rc, onBack = { remoteDetail = null })
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                TABS.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t.key,
                        onClick = { tab = t.key },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                "home" -> HomeScreen(onOpenRecord = { localDetail = it }, onOpenLive = { tab = "live" })
                "live" -> LiveScreen()
                "history" -> HistoryScreen(onOpenLocal = { localDetail = it }, onOpenRemote = { remoteDetail = it })
                else -> SettingsScreen()
            }
        }
    }
}

package com.akcomputer.callbridge.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.akcomputer.callbridge.core.CallRecord
import com.akcomputer.callbridge.core.Segment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val CORE_PERMISSIONS = listOf(
    Manifest.permission.READ_PHONE_STATE,
    Manifest.permission.RECORD_AUDIO,
)

val ALL_RUNTIME_PERMISSIONS: List<String> = buildList {
    add(Manifest.permission.READ_PHONE_STATE)
    add(Manifest.permission.READ_CALL_LOG)
    add(Manifest.permission.RECORD_AUDIO)
    @Suppress("DEPRECATION")
    add(Manifest.permission.PROCESS_OUTGOING_CALLS)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}

val PERMISSION_LABELS = mapOf(
    Manifest.permission.READ_PHONE_STATE to "Phone state — detect ringing / active / idle",
    Manifest.permission.READ_CALL_LOG to "Call log — caller number",
    Manifest.permission.RECORD_AUDIO to "Microphone — capture call audio",
    "android.permission.PROCESS_OUTGOING_CALLS" to "Outgoing calls — dialled number",
    "android.permission.POST_NOTIFICATIONS" to "Notifications — status & live transcript",
)

fun granted(ctx: Context, p: String) =
    ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

fun hasCorePermissions(ctx: Context) = CORE_PERMISSIONS.all { granted(ctx, it) }

fun isIgnoringBatteryOptimizations(ctx: Context) =
    ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

@SuppressLint("BatteryLife")
fun requestIgnoreBatteryOptimizations(ctx: Context) {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
    runCatching { ctx.startActivity(direct) }
        .onFailure { runCatching { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
}

fun openAppSettings(ctx: Context) {
    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
}

fun openAccessibilitySettings(ctx: Context) {
    ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
}

fun formatDateTime(ms: Long): String =
    SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(ms))

fun formatDuration(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)

fun formatOffset(ms: Long): String = formatDuration((ms / 1000).toInt())

fun transcriptShareText(title: String, subtitle: String, segments: List<Segment>): String = buildString {
    appendLine(title)
    appendLine(subtitle)
    appendLine()
    segments.forEach { appendLine("[${formatOffset(it.offsetMs)}] ${it.text}") }
}

fun shareText(ctx: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    ctx.startActivity(Intent.createChooser(send, "Share transcript"))
}

fun CallRecord.title() = number ?: "Unknown number"
fun CallRecord.subtitle() =
    "${direction.replaceFirstChar { it.uppercase() }} · ${formatDateTime(startedAt)} · ${formatDuration(durationSec)}"

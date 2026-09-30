package com.akcomputer.callbridge.core

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    const val DEFAULT_BASE_URL = "https://test.akdwk.in"

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.getSharedPreferences("callbridge", Context.MODE_PRIVATE)
    }

    /** Base URL, e.g. https://test.akdwk.in (API at /api, stream at /stream). */
    var baseUrl: String
        get() = sp.getString("base_url", DEFAULT_BASE_URL)!!
        set(v) = sp.edit().putString("base_url", v.trim().trimEnd('/')).apply()

    var token: String
        get() = sp.getString("token", "")!!
        set(v) = sp.edit().putString("token", v.trim()).apply()

    /** auto | gu | hi | en */
    var language: String
        get() = sp.getString("language", "auto")!!
        set(v) = sp.edit().putString("language", v).apply()

    /** auto | voice_communication | voice_call | voice_recognition | mic */
    var audioSource: String
        get() = sp.getString("audio_source", "auto")!!
        set(v) = sp.edit().putString("audio_source", v).apply()

    var serviceEnabled: Boolean
        get() = sp.getBoolean("service_enabled", false)
        set(v) = sp.edit().putBoolean("service_enabled", v).apply()

    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(v) = sp.edit().putBoolean("onboarded", v).apply()

    var liveNotification: Boolean
        get() = sp.getBoolean("live_notification", true)
        set(v) = sp.edit().putBoolean("live_notification", v).apply()

    var autoOpenLive: Boolean
        get() = sp.getBoolean("auto_open_live", true)
        set(v) = sp.edit().putBoolean("auto_open_live", v).apply()

    val apiBase: String get() = "$baseUrl/api"

    val streamUrl: String
        get() = baseUrl
            .replaceFirst(Regex("^https://", RegexOption.IGNORE_CASE), "wss://")
            .replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "ws://") + "/stream"
}

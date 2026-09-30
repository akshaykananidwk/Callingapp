package com.akcomputer.callbridge.service

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.util.Log
import com.akcomputer.callbridge.core.Prefs
import java.io.File

/**
 * Test mode: auto-answer incoming calls and play a looping demo clip on the loudspeaker so the
 * phone's microphone carries it into the call. (Android gives normal apps no API to inject audio
 * into the cellular uplink, so the acoustic path is the only non-root option.)
 */
object DemoMode {
    private const val TAG = "DemoMode"
    private var player: MediaPlayer? = null
    private var savedMusicVolume = -1
    private var speakerForced = false

    fun audioFile(context: Context) = File(context.filesDir, "demo_audio")

    fun hasAudio(context: Context) = audioFile(context).let { it.exists() && it.length() > 0 }

    /** Whether demo mode applies to this call. */
    fun appliesTo(number: String?, incoming: Boolean): Boolean {
        if (!Prefs.demoEnabled) return false
        if (!incoming && !Prefs.demoOnOutgoing) return false
        return !incoming || numberAllowed(number)
    }

    /** Number filter shared by auto-answer and the demo clip. Blank list = every number. */
    fun numberAllowed(number: String?): Boolean {
        val list = Prefs.demoNumbers.split(',', ';', '\n').map { digits(it) }.filter { it.isNotEmpty() }
        if (list.isEmpty()) return true
        val n = digits(number ?: return false)
        return n.isNotEmpty() && list.any { it == n }
    }

    private fun digits(s: String) = s.filter { it.isDigit() }.takeLast(10)

    @Synchronized
    fun startPlayback(context: Context) {
        if (player != null || !hasAudio(context)) return
        val am = context.getSystemService(AudioManager::class.java)
        forceSpeaker(am, true)
        savedMusicVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(audioFile(context).absolutePath)
                isLooping = true
                prepare()
                start()
            }
            Log.i(TAG, "Demo playback started")
        } catch (e: Exception) {
            Log.e(TAG, "Demo playback failed", e)
            stopPlayback(context)
        }
    }

    @Synchronized
    fun stopPlayback(context: Context) {
        player?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        player = null
        val am = context.getSystemService(AudioManager::class.java)
        if (savedMusicVolume >= 0) {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusicVolume, 0)
            savedMusicVolume = -1
        }
        if (speakerForced) forceSpeaker(am, false)
    }

    @Suppress("DEPRECATION")
    private fun forceSpeaker(am: AudioManager, on: Boolean) {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                if (on) {
                    val speaker = am.availableCommunicationDevices
                        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    if (speaker != null) speakerForced = am.setCommunicationDevice(speaker)
                } else {
                    am.clearCommunicationDevice()
                    speakerForced = false
                }
            }
            if (on && !am.isSpeakerphoneOn) { am.isSpeakerphoneOn = true; speakerForced = true }
            if (!on) am.isSpeakerphoneOn = false
        } catch (e: Exception) {
            Log.w(TAG, "Speaker routing failed: ${e.message}")
        }
    }
}

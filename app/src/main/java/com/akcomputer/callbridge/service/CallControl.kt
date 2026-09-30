package com.akcomputer.callbridge.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat

/** Phone call actions used by auto-answer and by commands from the website. */
object CallControl {
    private const val TAG = "CallControl"

    class Result(val ok: Boolean, val error: String? = null)

    private fun has(ctx: Context, p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    fun isRinging(ctx: Context): Boolean = try {
        ctx.getSystemService(TelephonyManager::class.java).callState == TelephonyManager.CALL_STATE_RINGING
    } catch (e: SecurityException) {
        true
    }

    /** Answer a ringing call: TelecomManager first, then the headset-button fallback. */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun answer(ctx: Context, log: (String) -> Unit): Result {
        if (!has(ctx, Manifest.permission.ANSWER_PHONE_CALLS)) {
            log("Answer failed: 'Answer calls' permission is not granted")
            return Result(false, "Answer-calls permission missing on phone")
        }
        try {
            ctx.getSystemService(TelecomManager::class.java).acceptRingingCall()
            log("Answer: acceptRingingCall sent")
        } catch (e: Exception) {
            log("acceptRingingCall failed: ${e.message}")
        }
        // Fallback for OEM builds that ignore acceptRingingCall: emulate the headset button.
        Thread {
            SystemClock.sleep(2500)
            if (isRinging(ctx)) {
                log("Still ringing — trying headset-button answer")
                try {
                    val am = ctx.getSystemService(AudioManager::class.java)
                    am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK))
                    am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK))
                } catch (e: Exception) {
                    log("Headset-button answer failed: ${e.message}")
                }
                SystemClock.sleep(2000)
                if (isRinging(ctx)) log("Auto-answer did not work on this phone")
            }
        }.start()
        return Result(true)
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun hangup(ctx: Context, log: (String) -> Unit): Result {
        if (!has(ctx, Manifest.permission.ANSWER_PHONE_CALLS)) {
            return Result(false, "Answer-calls permission missing on phone")
        }
        return try {
            val ok = ctx.getSystemService(TelecomManager::class.java).endCall()
            log(if (ok) "Call ended" else "endCall returned false")
            Result(ok, if (ok) null else "No call to end")
        } catch (e: Exception) {
            Result(false, e.message)
        }
    }

    @SuppressLint("MissingPermission")
    fun dial(ctx: Context, number: String, log: (String) -> Unit): Result {
        if (!has(ctx, Manifest.permission.CALL_PHONE)) {
            return Result(false, "Call permission missing on phone")
        }
        return try {
            ctx.getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", number, null), null)
            log("Dialing $number")
            Result(true)
        } catch (e: Exception) {
            log("Dial failed: ${e.message}")
            Result(false, e.message)
        }
    }

    @Suppress("DEPRECATION")
    fun speaker(ctx: Context, on: Boolean): Result = try {
        val am = ctx.getSystemService(AudioManager::class.java)
        if (Build.VERSION.SDK_INT >= 31) {
            if (on) {
                am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let { am.setCommunicationDevice(it) }
            } else {
                am.clearCommunicationDevice()
            }
        }
        am.isSpeakerphoneOn = on
        Result(true)
    } catch (e: Exception) {
        Result(false, e.message)
    }

    fun missingPermissions(ctx: Context): List<String> = buildList {
        if (!has(ctx, Manifest.permission.ANSWER_PHONE_CALLS)) add("answer calls")
        if (!has(ctx, Manifest.permission.CALL_PHONE)) add("make calls")
        if (!has(ctx, Manifest.permission.RECORD_AUDIO)) add("microphone")
        if (!has(ctx, Manifest.permission.READ_CALL_LOG)) add("call log")
    }
}

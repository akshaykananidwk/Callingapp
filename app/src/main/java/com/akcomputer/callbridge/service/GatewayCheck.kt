package com.akcomputer.callbridge.service

import android.os.Build
import java.io.File

/**
 * Can this phone act as a real GSM↔SIP line gateway (audio injected digitally into the call)?
 * That needs a Qualcomm audio HAL whose audio policy declares an `incall_music_uplink` output.
 * The policy file is readable without root, so the phone can be vetted before rooting it.
 */
object GatewayCheck {
    data class Result(
        val qualcomm: Boolean,
        val soc: String,
        val incallMusicUplink: Boolean?, // null = policy file not readable
        val policyFile: String?,
    ) {
        val verdict: String
            get() = when {
                !qualcomm -> "Not possible: this phone is not Qualcomm (no digital in-call audio path)."
                incallMusicUplink == true -> "Good candidate: the phone has a digital route into the call uplink. Root + gateway app needed."
                incallMusicUplink == false -> "Not possible: the audio policy has no in-call music uplink route."
                else -> "Unknown: could not read the audio policy. Check with a computer (adb) instead."
            }
    }

    fun run(): Result {
        val socMfr = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else ""
        val socModel = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else ""
        val qualcomm = Build.HARDWARE.equals("qcom", true) || socMfr.equals("QTI", true) ||
            socMfr.contains("qualcomm", true)
        val soc = listOf(socMfr, socModel, Build.BOARD).filter { it.isNotBlank() && it != "unknown" }.joinToString(" ")

        var readable = false
        var found: String? = null
        for (f in policyFiles()) {
            val text = try { f.readText() } catch (e: Exception) { continue }
            readable = true
            if (text.contains("incall_music_uplink")) { found = f.path; break }
        }
        return Result(qualcomm, soc, if (found != null) true else if (readable) false else null, found)
    }

    private fun policyFiles(): List<File> {
        val out = mutableListOf<File>()
        fun walk(dir: File, depth: Int) {
            val list = try { dir.listFiles() } catch (e: Exception) { null } ?: return
            for (f in list) {
                if (f.isDirectory && depth < 3) walk(f, depth + 1)
                else if (f.name.startsWith("audio_policy_configuration") && f.name.endsWith(".xml")) out.add(f)
            }
        }
        walk(File("/vendor/etc"), 0)
        walk(File("/odm/etc"), 0)
        return out
    }
}

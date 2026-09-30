package com.akcomputer.callbridge.service

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Captures 16 kHz mono PCM16 in 200 ms chunks (6400 bytes).
 * Tries the preferred source first, then falls back (PRD 4.2).
 */
class AudioCapture(
    private val sourcePref: String,
    private val onChunk: (ByteArray) -> Unit,
    private val onLevel: (Float) -> Unit,
) {
    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHUNK_BYTES = 1_280 // 40 ms * 16000 Hz * 2 bytes (low latency for live listening)
        private const val TAG = "AudioCapture"

        private val ALL = mapOf(
            "voice_communication" to MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            "voice_call" to MediaRecorder.AudioSource.VOICE_CALL,
            "voice_recognition" to MediaRecorder.AudioSource.VOICE_RECOGNITION,
            "mic" to MediaRecorder.AudioSource.MIC,
        )
        private val AUTO_ORDER = listOf("voice_communication", "voice_call", "voice_recognition", "mic")
    }

    @Volatile private var running = false
    private var thread: Thread? = null
    var activeSource: String? = null
        private set

    fun start(): Boolean {
        val rec = open() ?: return false
        running = true
        thread = Thread({ loop(rec) }, "cb-audio").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        return true
    }

    fun stop() {
        running = false
        thread?.join(1500)
        thread = null
    }

    @SuppressLint("MissingPermission")
    private fun open(): AudioRecord? {
        val order = if (sourcePref == "auto" || sourcePref !in ALL) AUTO_ORDER
        else listOf(sourcePref) + AUTO_ORDER.filter { it != sourcePref }
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = max(minBuf, 6_400 * 4)
        for (name in order) {
            val src = ALL.getValue(name)
            var rec: AudioRecord? = null
            try {
                rec = AudioRecord(src, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    rec.release(); continue
                }
                rec.startRecording()
                if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    rec.release(); continue
                }
                activeSource = name
                Log.i(TAG, "Recording with source $name")
                return rec
            } catch (e: Exception) {
                Log.w(TAG, "Source $name failed: ${e.message}")
                try { rec?.release() } catch (_: Exception) {}
            }
        }
        Log.e(TAG, "No audio source available")
        return null
    }

    private fun loop(rec: AudioRecord) {
        val buf = ByteArray(CHUNK_BYTES)
        try {
            while (running) {
                var off = 0
                while (off < CHUNK_BYTES && running) {
                    val n = rec.read(buf, off, CHUNK_BYTES - off)
                    if (n < 0) { Log.e(TAG, "read error $n"); running = false; break }
                    off += n
                }
                if (off == CHUNK_BYTES) {
                    onChunk(buf.copyOf())
                    onLevel(level(buf))
                }
            }
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }
    }

    /** RMS of little-endian PCM16, normalised to 0..1 (with a bit of gain for display). */
    private fun level(b: ByteArray): Float {
        var sum = 0.0
        val samples = b.size / 2
        for (i in 0 until samples) {
            val s = (b[2 * i].toInt() and 0xFF) or (b[2 * i + 1].toInt() shl 8)
            sum += s.toDouble() * s
        }
        val rms = sqrt(sum / samples) / 32768.0
        return (rms * 4).coerceIn(0.0, 1.0).toFloat()
    }
}

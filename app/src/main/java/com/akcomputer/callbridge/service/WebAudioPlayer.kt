package com.akcomputer.callbridge.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Plays the website user's voice (16 kHz PCM16 from the browser) on the phone loudspeaker,
 * where the microphone picks it up into the call.
 */
class WebAudioPlayer(private val context: Context) {
    private val queue = LinkedBlockingQueue<ByteArray>(200)
    @Volatile private var running = false
    private var thread: Thread? = null
    private var speakerSet = false

    fun write(pcm: ByteArray) {
        if (!running) start()
        if (!queue.offer(pcm)) { queue.poll(); queue.offer(pcm) }
    }

    private fun start() {
        running = true
        if (!speakerSet) { CallControl.speaker(context, true); speakerSet = true }
        thread = Thread({
            val minBuf = AudioTrack.getMinBufferSize(16000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(16000)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, 6400))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            try {
                track.play()
                while (running) {
                    val chunk = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    track.write(chunk, 0, chunk.size)
                }
            } catch (e: Exception) {
                Log.e("WebAudioPlayer", "playback failed", e)
            } finally {
                try { track.stop() } catch (_: Exception) {}
                track.release()
            }
        }, "cb-web-audio").apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(1000)
        thread = null
        queue.clear()
        if (speakerSet) { CallControl.speaker(context, false); speakerSet = false }
    }
}

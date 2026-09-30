package com.akcomputer.callbridge.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Small on-device cache of finished calls + transcripts (JSON file). */
object LocalHistory {
    private const val MAX_RECORDS = 500
    private lateinit var file: File
    private val _records = MutableStateFlow<List<CallRecord>>(emptyList())
    val records: StateFlow<List<CallRecord>> = _records

    fun init(context: Context) {
        file = File(context.filesDir, "history.json")
        _records.value = load()
    }

    @Synchronized
    fun add(record: CallRecord) {
        val list = (listOf(record) + _records.value.filter { it.id != record.id }).take(MAX_RECORDS)
        _records.value = list
        save(list)
    }

    @Synchronized
    fun delete(id: String) {
        val list = _records.value.filter { it.id != id }
        _records.value = list
        save(list)
    }

    private fun load(): List<CallRecord> = try {
        if (!file.exists()) emptyList() else {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        }
    } catch (e: Exception) {
        Log.e("LocalHistory", "load failed", e)
        emptyList()
    }

    private fun save(list: List<CallRecord>) {
        try {
            val arr = JSONArray()
            list.forEach { arr.put(toJson(it)) }
            val tmp = File(file.parentFile, "history.json.tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(file)
        } catch (e: Exception) {
            Log.e("LocalHistory", "save failed", e)
        }
    }

    private fun toJson(r: CallRecord) = JSONObject().apply {
        put("id", r.id)
        put("server_id", r.serverId ?: JSONObject.NULL)
        put("number", r.number ?: JSONObject.NULL)
        put("direction", r.direction)
        put("started_at", r.startedAt)
        put("ended_at", r.endedAt)
        put("duration_sec", r.durationSec)
        put("synced", r.synced)
        put("segments", JSONArray().apply {
            r.segments.forEach { put(JSONObject().put("text", it.text).put("offset_ms", it.offsetMs)) }
        })
    }

    private fun fromJson(o: JSONObject): CallRecord {
        val segs = o.optJSONArray("segments") ?: JSONArray()
        return CallRecord(
            id = o.getString("id"),
            serverId = o.optStringOrNull("server_id"),
            number = o.optStringOrNull("number"),
            direction = o.optString("direction", "incoming"),
            startedAt = o.optLong("started_at"),
            endedAt = o.optLong("ended_at"),
            durationSec = o.optInt("duration_sec"),
            synced = o.optBoolean("synced"),
            segments = (0 until segs.length()).map {
                val s = segs.getJSONObject(it)
                Segment(s.optString("text"), s.optLong("offset_ms"))
            },
        )
    }
}

fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

package com.akcomputer.callbridge.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

object Net {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
}

/** REST client for https://<base>/api (see PRD section 07). */
object Api {
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private fun request(path: String, query: Map<String, String?> = emptyMap()): Request.Builder {
        val url = (Prefs.apiBase + path).toHttpUrl().newBuilder().apply {
            query.forEach { (k, v) -> if (!v.isNullOrBlank()) addQueryParameter(k, v) }
        }.build()
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${Prefs.token}")
            .header("Accept", "application/json")
    }

    private suspend fun execute(req: Request): Any? = withContext(Dispatchers.IO) {
        Net.client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: ${body.take(200)}")
            if (body.isBlank()) null else JSONTokener(body).nextValue()
        }
    }

    private suspend fun post(path: String, body: JSONObject): Any? =
        execute(request(path).post(body.toString().toRequestBody(JSON)).build())

    private suspend fun get(path: String, query: Map<String, String?> = emptyMap()): Any? =
        execute(request(path, query).get().build())

    /** POST /calls/start → call_id */
    suspend fun startCall(number: String?, direction: String, timestampMs: Long): String {
        val res = post("/calls/start", JSONObject().apply {
            put("number", number ?: JSONObject.NULL)
            put("direction", direction)
            put("timestamp", Instant.ofEpochMilli(timestampMs).toString())
            put("language", Prefs.language)
        }) as? JSONObject ?: throw IOException("Empty response")
        return res.optStringOrNull("call_id") ?: res.optStringOrNull("id")
            ?: throw IOException("No call_id in response")
    }

    /** POST /calls/:id/end */
    suspend fun endCall(callId: String, durationSec: Int, number: String?) {
        post("/calls/$callId/end", JSONObject().apply {
            put("duration", durationSec)
            if (number != null) put("number", number)
        })
    }

    /** POST /device/heartbeat */
    suspend fun heartbeat(battery: Int, network: String, serviceStatus: String) {
        post("/device/heartbeat", JSONObject().apply {
            put("battery", battery)
            put("network", network)
            put("service_status", serviceStatus)
        })
    }

    /** GET /device/status — used as a connection test. */
    suspend fun deviceStatus(): String = get("/device/status")?.toString() ?: "OK"

    /** GET /calls */
    suspend fun calls(page: Int = 1, limit: Int = 50, number: String? = null, fromDate: String? = null): List<RemoteCall> =
        parseCalls(get("/calls", mapOf("page" to "$page", "limit" to "$limit", "number" to number, "from_date" to fromDate)))

    /** GET /calls/search?q= */
    suspend fun search(q: String): List<RemoteCall> = parseCalls(get("/calls/search", mapOf("q" to q)))

    /** GET /calls/:id/transcript */
    suspend fun transcript(callId: String): List<Segment> {
        val arr = unwrapArray(get("/calls/$callId/transcript"), "segments", "transcript", "data")
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Segment(o.optString("text"), o.optLong("offset_ms", o.optLong("ts")))
        }
    }

    private fun unwrapArray(v: Any?, vararg keys: String): JSONArray = when (v) {
        is JSONArray -> v
        is JSONObject -> keys.firstNotNullOfOrNull { v.optJSONArray(it) } ?: JSONArray()
        else -> JSONArray()
    }

    private fun parseCalls(v: Any?): List<RemoteCall> {
        val arr = unwrapArray(v, "calls", "results", "data", "items")
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val call = o.optJSONObject("call") ?: o
            RemoteCall(
                id = call.optStringOrNull("id") ?: call.optStringOrNull("call_id") ?: return@mapNotNull null,
                number = call.optStringOrNull("phone_number") ?: call.optStringOrNull("number"),
                direction = call.optStringOrNull("direction"),
                startedAt = call.optStringOrNull("started_at"),
                durationSec = if (call.has("duration_sec") && !call.isNull("duration_sec")) call.optInt("duration_sec") else null,
                snippet = o.optStringOrNull("text") ?: o.optStringOrNull("snippet") ?: o.optStringOrNull("headline"),
            )
        }
    }
}

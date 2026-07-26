package by.ster.wazeissues.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

data class LonLat(val lon: Double, val lat: Double)

data class ReportRemote(
    val id: String,
    val issueType: String,
    val payload: JSONObject,
    val description: String?,
    val reporterNick: String,
    val lon: Double,
    val lat: Double,
    val headingDeg: Double?,
    val createdAt: String,
)

class ApiClient(
    private val baseUrlProvider: () -> String,
    private val apiKeyProvider: () -> String,
) {
    private val client =
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    fun createReport(
        issueType: String,
        lon: Double,
        lat: Double,
        reporterNick: String,
        valueKmh: Int? = null,
        clientEventId: String = UUID.randomUUID().toString(),
        accuracyM: Float? = null,
    ): ReportRemote {
        val payload = JSONObject()
        if (valueKmh != null) payload.put("valueKmh", valueKmh)
        if (accuracyM != null) payload.put("accuracyM", accuracyM.toDouble())
        val body =
            JSONObject()
                .put("issueType", issueType)
                .put("lon", lon)
                .put("lat", lat)
                .put("reporterNick", reporterNick)
                .put("payload", payload)
                .put("clientEventId", clientEventId)
        val req =
            Request.Builder()
                .url("${baseUrlProvider().trimEnd('/')}/api/reports")
                .header("X-Api-Key", apiKeyProvider())
                .post(body.toString().toRequestBody(jsonMedia))
                .build()
        return parseReport(execute(req))
    }

    fun patchTrajectory(id: String, points: List<LonLat>, headingDeg: Double?): ReportRemote {
        val arr = JSONArray()
        points.forEach { p ->
            arr.put(JSONObject().put("lon", p.lon).put("lat", p.lat))
        }
        val body = JSONObject().put("points", arr)
        if (headingDeg != null) body.put("headingDeg", headingDeg)
        val req =
            Request.Builder()
                .url("${baseUrlProvider().trimEnd('/')}/api/reports/$id/trajectory")
                .header("X-Api-Key", apiKeyProvider())
                .patch(body.toString().toRequestBody(jsonMedia))
                .build()
        return parseReport(execute(req))
    }

    fun patchDescription(id: String, description: String): ReportRemote {
        val body = JSONObject().put("description", description)
        val req =
            Request.Builder()
                .url("${baseUrlProvider().trimEnd('/')}/api/reports/$id")
                .header("X-Api-Key", apiKeyProvider())
                .patch(body.toString().toRequestBody(jsonMedia))
                .build()
        return parseReport(execute(req))
    }

    private fun execute(req: Request): JSONObject {
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code}: $text")
            }
            return JSONObject(text)
        }
    }

    private fun parseReport(obj: JSONObject): ReportRemote {
        return ReportRemote(
            id = obj.getString("id"),
            issueType = obj.getString("issueType"),
            payload = obj.optJSONObject("payload") ?: JSONObject(),
            description =
                if (obj.isNull("description")) null else obj.optString("description").ifBlank { null },
            reporterNick = obj.getString("reporterNick"),
            lon = obj.getDouble("lon"),
            lat = obj.getDouble("lat"),
            headingDeg = if (obj.isNull("headingDeg")) null else obj.getDouble("headingDeg"),
            createdAt = obj.getString("createdAt"),
        )
    }
}

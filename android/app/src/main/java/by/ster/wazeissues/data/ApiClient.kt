package by.ster.wazeissues.data

import by.ster.wazeissues.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

data class LonLat(val lon: Double, val lat: Double)

data class AppVersion(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
)

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
) {
    private val client =
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    fun createReport(
        issueType: String,
        lon: Double,
        lat: Double,
        reporterNick: String,
        valueKmh: Int? = null,
        lengthM: Int? = null,
        clientEventId: String = UUID.randomUUID().toString(),
        accuracyM: Float? = null,
    ): ReportRemote {
        val payload = JSONObject()
        if (valueKmh != null) payload.put("valueKmh", valueKmh)
        if (lengthM != null) payload.put("lengthM", lengthM)
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
                .post(body.toString().toRequestBody(jsonMedia))
                .build()
        return parseReport(execute(req))
    }

    /** Reads version.json from the GitHub Releases rolling tag. Null on any failure. */
    fun fetchLatestVersion(): AppVersion? {
        val req =
            Request.Builder()
                .url(BuildConfig.UPDATE_MANIFEST_URL)
                .get()
                .header("Accept", "application/octet-stream")
                .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val obj = JSONObject(resp.body?.string().orEmpty())
                val code = obj.optInt("versionCode", -1)
                if (code < 0) return null
                AppVersion(
                    versionCode = code,
                    versionName = obj.optString("versionName"),
                    apkUrl =
                        obj.optString("apkUrl").ifBlank { BuildConfig.DEFAULT_APK_URL },
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Download APK following redirects (GitHub Releases). */
    fun downloadApk(url: String, destFile: java.io.File): java.io.File {
        val req =
            Request.Builder()
                .url(url)
                .get()
                .header("Accept", "application/vnd.android.package-archive,*/*")
                .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code}")
            }
            val body = resp.body ?: throw IllegalStateException("Empty APK body")
            destFile.parentFile?.mkdirs()
            body.byteStream().use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            }
            if (destFile.length() < 1_000L) {
                destFile.delete()
                throw IllegalStateException("APK too small")
            }
            return destFile
        }
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
                .patch(body.toString().toRequestBody(jsonMedia))
                .build()
        return parseReport(execute(req))
    }

    fun patchDescription(id: String, description: String): ReportRemote {
        val body = JSONObject().put("description", description)
        val req =
            Request.Builder()
                .url("${baseUrlProvider().trimEnd('/')}/api/reports/$id")
                .patch(body.toString().toRequestBody(jsonMedia))
                .build()
        return parseReport(execute(req))
    }

    fun patchReport(
        id: String,
        description: String? = null,
        lengthM: Int? = null,
    ): ReportRemote {
        val body = JSONObject()
        if (description != null) body.put("description", description)
        if (lengthM != null) {
            body.put("payload", JSONObject().put("lengthM", lengthM))
        }
        val req =
            Request.Builder()
                .url("${baseUrlProvider().trimEnd('/')}/api/reports/$id")
                .patch(body.toString().toRequestBody(jsonMedia))
                .build()
        return parseReport(execute(req))
    }

    fun deleteReport(id: String) {
        val req =
            Request.Builder()
                .url("${baseUrlProvider().trimEnd('/')}/api/reports/$id")
                .delete()
                .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code}: $text")
            }
        }
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

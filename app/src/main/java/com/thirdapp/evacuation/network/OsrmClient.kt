package com.thirdapp.evacuation.network

import com.thirdapp.evacuation.model.Coord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class OsrmRoute(
    val points: List<Coord>,
    val distanceMeters: Double,
    val durationSeconds: Double
)

class OsrmClient(
    private val baseUrl: String = "https://router.project-osrm.org",
    private val profile: String = "foot"
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun route(from: Coord, to: Coord): Result<OsrmRoute> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$baseUrl/route/v1/$profile/" +
                    "${from.lon},${from.lat};${to.lon},${to.lat}" +
                    "?overview=full&geometries=geojson"
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("HTTP ${resp.code}: $body")
                val json = JSONObject(body)
                if (json.optString("code") != "Ok") error("OSRM: ${json.optString("code")}")
                val route = json.getJSONArray("routes").getJSONObject(0)
                val coords = route.getJSONObject("geometry").getJSONArray("coordinates")
                val points = buildList(coords.length()) {
                    for (i in 0 until coords.length()) {
                        val pair = coords.getJSONArray(i)
                        add(Coord(lat = pair.getDouble(1), lon = pair.getDouble(0)))
                    }
                }
                OsrmRoute(
                    points = points,
                    distanceMeters = route.getDouble("distance"),
                    durationSeconds = route.getDouble("duration")
                )
            }
        }
    }
}

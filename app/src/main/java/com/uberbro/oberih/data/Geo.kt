package com.uberbro.oberih.data

import android.content.Context
import android.location.Geocoder
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

data class GeoPoint(val lat: Double, val lon: Double, val source: String)

data class Route(
    val points: List<GeoPoint>,
    val miles: Double,
    val minutes: Double,
    /** true — справжній маршрут по дорогах; false — пряма лінія (приблизно). */
    val real: Boolean,
)

/**
 * Пошук адрес на карті і маршрутів. Усе безкоштовно:
 * 1) вбудований геокодер Android (Google), 2) OpenStreetMap Nominatim, 3) назва району зі списку.
 */
object Geo {
    private const val TAG = "Geo"
    const val UA = "Oberih/1.0 (driver safety helper; personal use)"
    private val cache = LruCache<String, GeoPoint>(300)

    // Рамка пошуку: увесь Іллінойс + південь Вісконсину до Мілвокі.
    private const val S = 36.90; private const val W = -91.60; private const val N = 43.30; private const val E = -87.00

    fun buildQuery(raw: String): String {
        val t = raw.replace('\n', ' ').replace(Regex("\\s+"), " ").trim().trimEnd(',', '.')
        val hasCity = Regex(",\\s*[A-Za-z .'-]+(,\\s*IL)?(\\s+\\d{5})?\\s*$").containsMatchIn(t) ||
            Regex("\\bIL\\b|\\b6\\d{4}\\b|Chicago", RegexOption.IGNORE_CASE).containsMatchIn(t)
        return if (hasCity) t else "$t, Chicago, IL"
    }

    suspend fun geocode(ctx: Context, raw: String, grid: RiskGrid?): GeoPoint? {
        if (raw.isBlank()) return null
        val q = buildQuery(raw)
        cache.get(q)?.let { return it }
        val p = withTimeoutOrNull(4_500) { android(ctx, q) }
            ?: withTimeoutOrNull(4_500) { nominatim(q) }
            ?: areaFallback(raw, grid)
        if (p != null) cache.put(q, p)
        return p
    }

    @Suppress("DEPRECATION")
    private suspend fun android(ctx: Context, q: String): GeoPoint? {
        if (!Geocoder.isPresent()) return null
        return runCatching {
            runInterruptible(Dispatchers.IO) {
                Geocoder(ctx, Locale.US).getFromLocationName(q, 1, S, W, N, E)
                    ?.firstOrNull()?.takeIf { it.hasLatitude() && it.hasLongitude() }
                    ?.let { GeoPoint(it.latitude, it.longitude, "android") }
            }
        }.onFailure { Log.w(TAG, "android geocoder", it) }.getOrNull()
    }

    private suspend fun nominatim(q: String): GeoPoint? = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&countrycodes=us" +
                "&viewbox=$W,$N,$E,$S&bounded=1&q=" + URLEncoder.encode(q, "UTF-8")
            val arr = JSONArray(httpGet(url, 4_000))
            if (arr.length() == 0) null else arr.getJSONObject(0).let {
                GeoPoint(it.getString("lat").toDouble(), it.getString("lon").toDouble(), "osm")
            }
        }.onFailure { Log.w(TAG, "nominatim", it) }.getOrNull()
    }

    private fun areaFallback(raw: String, grid: RiskGrid?): GeoPoint? {
        val area = CommunityAreas.find(raw) ?: return null
        val c = grid?.areaCenter(area) ?: return null
        return GeoPoint(c.first, c.second, "area")
    }

    /** Маршрут по дорогах (OSRM, безкоштовний сервер OpenStreetMap). Якщо недоступний — пряма лінія. */
    suspend fun route(a: GeoPoint, b: GeoPoint): Route =
        withTimeoutOrNull(3_500) { osrm(a, b) } ?: straight(a, b)

    private suspend fun osrm(a: GeoPoint, b: GeoPoint): Route? = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://router.project-osrm.org/route/v1/driving/" +
                "${a.lon},${a.lat};${b.lon},${b.lat}?overview=simplified&geometries=geojson"
            val r = JSONObject(httpGet(url, 3_000)).getJSONArray("routes").getJSONObject(0)
            val coords = r.getJSONObject("geometry").getJSONArray("coordinates")
            val pts = (0 until coords.length()).map {
                val c = coords.getJSONArray(it); GeoPoint(c.getDouble(1), c.getDouble(0), "route")
            }
            Route(densify(pts), r.getDouble("distance") / 1609.34, r.getDouble("duration") / 60.0, true)
        }.onFailure { Log.w(TAG, "osrm", it) }.getOrNull()
    }

    private fun straight(a: GeoPoint, b: GeoPoint): Route {
        val km = RiskGrid.distanceKm(a.lat, a.lon, b.lat, b.lon)
        val miles = km * 1.3 / 1.609 // дороги довші за пряму приблизно на 30%
        return Route(densify(listOf(a, b)), miles, miles / 22.0 * 60.0, false)
    }

    /** Точки вздовж маршруту кожні ~150 м — щоб перевірити всі клітинки, через які він проходить. */
    fun densify(pts: List<GeoPoint>): List<GeoPoint> {
        if (pts.size < 2) return pts
        val out = ArrayList<GeoPoint>()
        for (i in 0 until pts.size - 1) {
            val a = pts[i]; val b = pts[i + 1]
            val km = RiskGrid.distanceKm(a.lat, a.lon, b.lat, b.lon)
            val steps = (km / 0.15).toInt().coerceAtLeast(1)
            for (s in 0 until steps) {
                val f = s.toDouble() / steps
                out += GeoPoint(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f, "route")
            }
        }
        out += pts.last()
        return out
    }

    fun httpGet(url: String, timeoutMs: Int): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs; readTimeout = timeoutMs
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }
}

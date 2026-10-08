package com.uberbro.oberih.data

import android.content.Context
import android.util.JsonReader
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Дані про злочинність:
 * - Чикаго — офіційний портал міста (Chicago Data Portal, датасет ijzp-q8t2);
 * - Мілвокі — портал data.milwaukee.gov (WIBR);
 * - інші міста Іллінойсу й Вісконсину — річні дані ФБР, вбудовані в програму (assets/towns.tsv).
 * Безкоштовно, без ключів. Квартали Чикаго й Мілвокі оновлюються раз на добу і зберігаються на телефоні.
 */
object CrimeRepository {
    private const val TAG = "CrimeRepo"
    private const val DAYS = 180L
    private const val PAGE = 25_000
    private const val ENDPOINT = "https://data.cityofchicago.org/resource/ijzp-q8t2.json"
    val CHICAGO: ZoneId = ZoneId.of("America/Chicago")

    private val _grid = MutableStateFlow<RiskGrid?>(null)
    val grid: StateFlow<RiskGrid?> = _grid

    private val _milwaukee = MutableStateFlow<RiskGrid?>(null)
    val milwaukee: StateFlow<RiskGrid?> = _milwaukee

    @Volatile private var towns: TownMap? = null

    /** Прогрес завантаження 0..1, або null коли нічого не завантажується. */
    private val _progress = MutableStateFlow<Float?>(null)
    val progress: StateFlow<Float?> = _progress

    private val mutex = Mutex()

    private fun file(ctx: Context, region: Region = Region.CHICAGO) = File(ctx.filesDir, region.file)

    private fun readGrid(ctx: Context, region: Region): RiskGrid? {
        val f = file(ctx, region)
        if (!f.exists()) return null
        return runCatching {
            DataInputStream(BufferedInputStream(f.inputStream())).use { RiskGrid.read(it) }
        }.onFailure { Log.w(TAG, "load ${region.name} failed", it) }.getOrNull()
    }

    private fun saveGrid(ctx: Context, grid: RiskGrid) {
        val f = file(ctx, grid.region)
        val tmp = File(f.path + ".tmp")
        DataOutputStream(BufferedOutputStream(tmp.outputStream())).use { grid.write(it) }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    /** Завантажує збережену карту Чикаго з телефону (швидко, без інтернету). */
    suspend fun load(ctx: Context): RiskGrid? = withContext(Dispatchers.IO) {
        _grid.value?.let { return@withContext it }
        readGrid(ctx, Region.CHICAGO)?.also { _grid.value = it }
    }

    /** Вся карта: Чикаго + Мілвокі + інші міста. */
    suspend fun map(ctx: Context): RiskMap = withContext(Dispatchers.IO) {
        val chi = load(ctx)
        val mke = _milwaukee.value ?: readGrid(ctx, Region.MILWAUKEE)?.also { _milwaukee.value = it }
        RiskMap(chi, mke, towns(ctx))
    }

    fun towns(ctx: Context): TownMap = towns ?: runCatching {
        TownMap.parse(ctx.assets.open("towns.tsv").bufferedReader().readText())
    }.getOrElse { Log.w(TAG, "towns", it); TownMap(emptyList()) }.also { towns = it }

    fun isStale(g: RiskGrid?): Boolean =
        g == null || System.currentTimeMillis() - g.generatedAt > 20 * 3600_000L

    /** Оновлює, тільки якщо дані застарі (щоб програма й фонове завдання не качали двічі одночасно). */
    suspend fun refreshIfStale(ctx: Context): String? =
        if (isStale(_grid.value ?: load(ctx))) refresh(ctx, onlyIfStale = true) else null

    /** Завантажує свіжі дані з порталу і перебудовує карту. Повертає null при успіху або текст помилки. */
    suspend fun refresh(ctx: Context, onlyIfStale: Boolean = false): String? = mutex.withLock {
        if (onlyIfStale && !isStale(_grid.value)) return@withLock null
        withContext(Dispatchers.IO) {
            _progress.value = 0f
            try {
                val today = LocalDate.now(CHICAGO)
                val since = today.minusDays(DAYS)
                val builder = RiskGridBuilder(Region.CHICAGO)
                val where = "date > '${since}T00:00:00' AND latitude IS NOT NULL AND ${CrimeWeights.WHERE_TYPES}"
                val expected = fetchCount(where).coerceAtLeast(1)
                var offset = 0
                while (true) {
                    val got = fetchPage(where, offset, builder, today)
                    offset += got
                    _progress.value = (offset.toFloat() / expected * 0.9f).coerceIn(0f, 0.9f)
                    if (got < PAGE) break
                }
                if (builder.count < 3000) error("Отримано замало даних (${builder.count})")
                fetchCrowd(since, builder)
                _progress.value = 0.98f
                val grid = builder.build(System.currentTimeMillis())
                saveGrid(ctx, grid)
                _grid.value = grid
                // Мілвокі — окремо: якщо його сервер недоступний, карта Чикаго однаково оновлена.
                runCatching { refreshMilwaukee(ctx, since, today) }.onFailure { Log.w(TAG, "milwaukee", it) }
                Prefs(ctx).lastDataError = null
                null
            } catch (e: Exception) {
                Log.w(TAG, "refresh failed", e)
                val msg = "Не вдалося оновити дані: ${e.message ?: e.javaClass.simpleName}"
                Prefs(ctx).lastDataError = msg
                msg
            } finally {
                _progress.value = null
            }
        }
    }

    private const val MKE_RESOURCE = "87843297-a6fa-46d4-ba5d-cb342fb2d3bb"

    /** Мілвокі: тяжкі злочини (коди NIBRS) з координатами + крадіжки по клітинках як мірило людності. */
    private fun refreshMilwaukee(ctx: Context, since: LocalDate, today: LocalDate) {
        val b = RiskGridBuilder(Region.MILWAUKEE)
        val codes = CrimeWeights.NIBRS_CODES.joinToString(" OR ") { "\"Offense_All\" LIKE '%$it%'" }
        val rows = mkeSql(
            "SELECT \"Incident_Date\",\"Offense_All\",\"Weapon_Used_All\",\"Address_Latitude\",\"Address_Longitude\" " +
                "FROM \"$MKE_RESOURCE\" WHERE \"Incident_Date\" >= '$since' AND \"Incident_Date\" <= '${today.plusDays(1)}' AND ($codes)",
        )
        for (i in 0 until rows.length()) {
            val o = rows.getJSONObject(i)
            val lat = o.optString("Address_Latitude").toDoubleOrNull() ?: continue
            val lon = o.optString("Address_Longitude").toDoubleOrNull() ?: continue
            val date = o.optString("Incident_Date")
            val age = runCatching { ChronoUnit.DAYS.between(LocalDate.parse(date.take(10)), today) }.getOrDefault(90L)
            val w = CrimeWeights.nibrsWeight(o.optString("Offense_All"), o.optString("Weapon_Used_All"), age)
            if (w > 0f) b.add(lat, lon, w, CrimeWeights.isNightTime(date), 0, date.take(10))
        }
        if (b.count < 300) error("Мілвокі: замало даних (${b.count})")
        val crowd = mkeSql(
            "SELECT floor(CAST(\"Address_Latitude\" AS float)*250) AS r, floor(CAST(\"Address_Longitude\" AS float)*200) AS c, count(*) AS n " +
                "FROM \"$MKE_RESOURCE\" WHERE \"Incident_Date\" >= '$since' AND \"Offense_All\" LIKE '%23%' AND \"Address_Latitude\" <> '' GROUP BY 1,2",
        )
        for (i in 0 until crowd.length()) {
            val o = crowd.getJSONObject(i)
            b.addCrowd(o.optDouble("r").toInt() - Region.MILWAUKEE.row0, o.optDouble("c").toInt() - Region.MILWAUKEE.col0, o.optInt("n"))
        }
        val g = b.build(System.currentTimeMillis())
        saveGrid(ctx, g)
        _milwaukee.value = g
    }

    private fun mkeSql(sql: String): org.json.JSONArray {
        val url = "https://data.milwaukee.gov/api/3/action/datastore_search_sql?sql=" + URLEncoder.encode(sql, "UTF-8")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 60_000
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (conn.responseCode != 200) error("Мілвокі: сервер відповів ${conn.responseCode}")
            val o = org.json.JSONObject(conn.inputStream.bufferedReader().readText())
            return o.getJSONObject("result").getJSONArray("records")
        } finally {
            conn.disconnect()
        }
    }

    private fun open(params: Map<String, String>): HttpURLConnection {
        val q = params.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        return (URL("$ENDPOINT?$q").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Encoding", "gzip")
        }
    }

    private fun stream(conn: HttpURLConnection) =
        if (conn.contentEncoding == "gzip") java.util.zip.GZIPInputStream(conn.inputStream) else conn.inputStream

    private fun fetchCount(where: String): Int = runCatching {
        val conn = open(mapOf("\$select" to "count(*)", "\$where" to where))
        try {
            val text = stream(conn).bufferedReader().readText()
            Regex("\"count\"\\s*:\\s*\"?(\\d+)").find(text)?.groupValues?.get(1)?.toInt() ?: 0
        } finally { conn.disconnect() }
    }.getOrDefault(0)

    /** Кількість крадіжок по клітинках (сервер сам групує, відповідь ~90 КБ) — мірило людності. */
    private fun fetchCrowd(since: LocalDate, b: RiskGridBuilder) {
        val conn = open(
            mapOf(
                "\$select" to "floor(latitude::number*250) as r, floor(longitude::number*200) as c, count(*) as n",
                "\$where" to "date > '${since}T00:00:00' AND primary_type='THEFT' AND latitude IS NOT NULL",
                "\$group" to "r,c",
                "\$limit" to "50000",
            )
        )
        try {
            if (conn.responseCode != 200) error("Сервер відповів ${conn.responseCode}")
            var cells = 0
            JsonReader(InputStreamReader(stream(conn), Charsets.UTF_8)).use { r ->
                r.beginArray()
                while (r.hasNext()) {
                    var row = Int.MIN_VALUE; var col = Int.MIN_VALUE; var n = 0
                    r.beginObject()
                    while (r.hasNext()) {
                        when (r.nextName()) {
                            "r" -> row = r.scalar().toDoubleOrNull()?.toInt() ?: Int.MIN_VALUE
                            "c" -> col = r.scalar().toDoubleOrNull()?.toInt() ?: Int.MIN_VALUE
                            "n" -> n = r.scalar().toIntOrNull() ?: 0
                            else -> r.skipValue()
                        }
                    }
                    r.endObject()
                    if (row != Int.MIN_VALUE && col != Int.MIN_VALUE) {
                        b.addCrowd(row - RiskGrid.ROW0, col - RiskGrid.COL0, n); cells++
                    }
                }
                r.endArray()
            }
            if (cells < 500) error("Замало даних про людність ($cells)")
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchPage(where: String, offset: Int, b: RiskGridBuilder, today: LocalDate): Int {
        val conn = open(
            mapOf(
                "\$select" to "date,primary_type,description,domestic,latitude,longitude,community_area",
                "\$where" to where,
                "\$order" to ":id",
                "\$limit" to PAGE.toString(),
                "\$offset" to offset.toString(),
            )
        )
        try {
            if (conn.responseCode != 200) error("Сервер відповів ${conn.responseCode}")
            var n = 0
            JsonReader(InputStreamReader(stream(conn), Charsets.UTF_8)).use { r ->
                r.beginArray()
                while (r.hasNext()) {
                    var date = ""; var type = ""; var desc = ""; var domestic = false
                    var lat = Double.NaN; var lon = Double.NaN; var area = 0
                    r.beginObject()
                    while (r.hasNext()) {
                        when (r.nextName()) {
                            "date" -> date = r.scalar()
                            "primary_type" -> type = r.scalar()
                            "description" -> desc = r.scalar()
                            "domestic" -> domestic = r.scalar().toBoolean()
                            "latitude" -> lat = r.scalar().toDoubleOrNull() ?: Double.NaN
                            "longitude" -> lon = r.scalar().toDoubleOrNull() ?: Double.NaN
                            "community_area" -> area = r.scalar().toIntOrNull() ?: 0
                            else -> r.skipValue()
                        }
                    }
                    r.endObject()
                    n++
                    if (lat.isNaN() || lon.isNaN()) continue
                    val age = runCatching {
                        ChronoUnit.DAYS.between(LocalDateTime.parse(date.take(19)).toLocalDate(), today)
                    }.getOrDefault(90L)
                    val w = CrimeWeights.weight(type, desc, domestic, age)
                    if (w > 0f) b.add(lat, lon, w, CrimeWeights.isNightTime(date), area, date.take(10))
                }
                r.endArray()
            }
            return n
        } finally {
            conn.disconnect()
        }
    }

    /** Читає будь-яке просте значення (рядок, число, true/false, null) як рядок. */
    private fun JsonReader.scalar(): String = when (peek()) {
        android.util.JsonToken.BOOLEAN -> nextBoolean().toString()
        android.util.JsonToken.NULL -> { nextNull(); "" }
        android.util.JsonToken.STRING, android.util.JsonToken.NUMBER -> nextString()
        else -> { skipValue(); "" }
    }
}

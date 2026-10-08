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
 * Дані про злочинність з офіційного порталу Чикаго (Chicago Data Portal, датасет ijzp-q8t2).
 * Безкоштовно, без ключа. Завантажуємо останні 180 днів раз на добу і зберігаємо на телефоні.
 */
object CrimeRepository {
    private const val TAG = "CrimeRepo"
    private const val DAYS = 180L
    private const val PAGE = 25_000
    private const val ENDPOINT = "https://data.cityofchicago.org/resource/ijzp-q8t2.json"
    val CHICAGO: ZoneId = ZoneId.of("America/Chicago")

    private val _grid = MutableStateFlow<RiskGrid?>(null)
    val grid: StateFlow<RiskGrid?> = _grid

    /** Прогрес завантаження 0..1, або null коли нічого не завантажується. */
    private val _progress = MutableStateFlow<Float?>(null)
    val progress: StateFlow<Float?> = _progress

    private val mutex = Mutex()

    private fun file(ctx: Context) = File(ctx.filesDir, "risk_grid.bin")

    /** Завантажує збережену карту з телефону (швидко, без інтернету). */
    suspend fun load(ctx: Context): RiskGrid? = withContext(Dispatchers.IO) {
        _grid.value?.let { return@withContext it }
        val f = file(ctx)
        if (!f.exists()) return@withContext null
        runCatching {
            DataInputStream(BufferedInputStream(f.inputStream())).use { RiskGrid.read(it) }
        }.onFailure { Log.w(TAG, "load failed", it) }.getOrNull()?.also { _grid.value = it }
    }

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
                val builder = RiskGridBuilder()
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
                val f = file(ctx)
                val tmp = File(f.path + ".tmp")
                DataOutputStream(BufferedOutputStream(tmp.outputStream())).use { grid.write(it) }
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
                _grid.value = grid
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

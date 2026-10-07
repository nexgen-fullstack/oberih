package com.uberbro.oberih.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File

data class JournalEntry(
    val time: Long,
    val app: String,
    val level: Level,
    val fare: Double?,
    val perHour: Double?,
    val profit: String,
    val pickup: String?,
    val dropoff: String?,
    val reason: String,
) {
    fun toJson(): String = JSONObject().apply {
        put("t", time); put("app", app); put("lvl", level.name)
        fare?.let { put("fare", it) }; perHour?.let { put("ph", it) }
        put("profit", profit); put("p", pickup ?: ""); put("d", dropoff ?: ""); put("r", reason)
    }.toString()

    companion object {
        fun fromJson(s: String): JournalEntry? = runCatching {
            val o = JSONObject(s)
            JournalEntry(
                o.getLong("t"), o.optString("app"),
                runCatching { Level.valueOf(o.getString("lvl")) }.getOrDefault(Level.UNKNOWN),
                if (o.has("fare")) o.getDouble("fare") else null,
                if (o.has("ph")) o.getDouble("ph") else null,
                o.optString("profit"), o.optString("p").ifBlank { null }, o.optString("d").ifBlank { null },
                o.optString("r"),
            )
        }.getOrNull()
    }
}

/** Журнал замовлень, які бачив Оберіг (останні 500). Зберігається тільки на телефоні. */
object Journal {
    private const val MAX = 500
    private val _entries = MutableStateFlow<List<JournalEntry>>(emptyList())
    val entries: StateFlow<List<JournalEntry>> = _entries
    private var loaded = false

    private fun file(ctx: Context) = File(ctx.filesDir, "journal.jsonl")

    @Synchronized
    fun load(ctx: Context): List<JournalEntry> {
        if (!loaded) {
            val f = file(ctx)
            _entries.value = if (f.exists()) f.readLines().mapNotNull { JournalEntry.fromJson(it) }.takeLast(MAX).reversed() else emptyList()
            loaded = true
        }
        return _entries.value
    }

    @Synchronized
    fun add(ctx: Context, e: JournalEntry) {
        val list = (listOf(e) + load(ctx)).take(MAX)
        _entries.value = list
        file(ctx).writeText(list.reversed().joinToString("\n") { it.toJson() })
    }

    @Synchronized
    fun clear(ctx: Context) {
        _entries.value = emptyList()
        file(ctx).delete()
    }
}

/**
 * Зразки екранів Uber/Lyft (текст), щоб покращувати розпізнавання.
 * Лежать тільки на телефоні; водій сам вирішує, чи надіслати їх кнопкою «Поділитися».
 */
object ScreenSamples {
    private const val MAX = 40

    private fun file(ctx: Context) = File(ctx.filesDir, "screen_samples.txt")

    @Synchronized
    fun add(ctx: Context, app: String, recognized: Boolean, text: String) {
        val f = file(ctx)
        val old = if (f.exists()) f.readText().split(SEP).filter { it.isNotBlank() } else emptyList()
        if (old.any { it.substringAfter('\n').trim() == text.trim() }) return
        val header = "[${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}] " +
            "$app — ${if (recognized) "розпізнано" else "НЕ розпізнано"}"
        val all = (old + "$header\n$text").takeLast(MAX)
        f.writeText(all.joinToString(SEP))
    }

    fun count(ctx: Context): Int {
        val f = file(ctx)
        return if (f.exists()) f.readText().split(SEP).count { it.isNotBlank() } else 0
    }

    fun read(ctx: Context): String = file(ctx).takeIf { it.exists() }?.readText().orEmpty()

    fun clear(ctx: Context) { file(ctx).delete() }

    private const val SEP = "\n\n=====\n"
}

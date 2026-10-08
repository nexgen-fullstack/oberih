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
 * Зразки екранів Uber/Lyft/Waze: текст + скріншоти, щоб покращувати розпізнавання.
 * Лежать тільки на телефоні; водій сам вирішує, чи надіслати їх (кнопка «Надіслати у WhatsApp»).
 */
object ScreenSamples {
    private const val MAX_TEXT = 40
    private const val MAX_IMAGES = 30
    private const val SEP = "\n\n=====\n"

    private fun textFile(ctx: Context) = File(ctx.filesDir, "screen_samples.txt")
    fun imagesDir(ctx: Context) = File(ctx.filesDir, "samples").apply { mkdirs() }

    private fun stamp(pattern: String) =
        java.text.SimpleDateFormat(pattern, java.util.Locale.US).format(java.util.Date())

    @Synchronized
    fun add(ctx: Context, app: String, recognized: Boolean, text: String) {
        val f = textFile(ctx)
        val old = if (f.exists()) f.readText().split(SEP).filter { it.isNotBlank() } else emptyList()
        if (old.any { it.substringAfter('\n').trim() == text.trim() }) return
        val header = "[${stamp("yyyy-MM-dd HH:mm:ss")}] $app — ${if (recognized) "розпізнано" else "НЕ розпізнано"}"
        val all = (old + "$header\n$text").takeLast(MAX_TEXT)
        f.writeText(all.joinToString(SEP))
    }

    /** Зберігає скріншот (зменшений, JPEG) і видаляє найстаріші понад 30 штук. */
    @Synchronized
    fun addImage(ctx: Context, bmp: android.graphics.Bitmap, tag: String) {
        val dir = imagesDir(ctx)
        val safe = tag.replace(Regex("[^A-Za-z0-9_-]"), "_").take(30)
        File(dir, "${stamp("yyyyMMdd_HHmmss")}_$safe.jpg").outputStream().use {
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 72, it)
        }
        images(ctx).dropLast(MAX_IMAGES).forEach { it.delete() }
    }

    /** Від найновішого до найстарішого. */
    fun images(ctx: Context): List<File> =
        imagesDir(ctx).listFiles { f -> f.name.endsWith(".jpg") }?.sortedByDescending { it.name }.orEmpty()

    fun count(ctx: Context): Int {
        val f = textFile(ctx)
        return if (f.exists()) f.readText().split(SEP).count { it.isNotBlank() } else 0
    }

    fun read(ctx: Context): String = textFile(ctx).takeIf { it.exists() }?.readText().orEmpty()

    @Synchronized
    fun clear(ctx: Context) {
        textFile(ctx).delete()
        imagesDir(ctx).listFiles()?.forEach { it.delete() }
    }

    /**
     * Готує все до відправки: скріншоти + текстовий файл. Відкриває WhatsApp (або вибір програми).
     * Повертає false, якщо надсилати нічого.
     */
    fun share(ctx: Context, preferWhatsApp: Boolean): Boolean {
        val dir = imagesDir(ctx)
        val uris = ArrayList<android.net.Uri>()
        val auth = ctx.packageName + ".files"
        images(ctx).take(MAX_IMAGES).forEach { uris += androidx.core.content.FileProvider.getUriForFile(ctx, auth, it) }
        val text = read(ctx)
        if (text.isNotBlank()) {
            val tf = File(dir, "oberih_screens.txt").apply { writeText(text) }
            uris += androidx.core.content.FileProvider.getUriForFile(ctx, auth, tf)
        }
        if (uris.isEmpty()) return false
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
            type = if (text.isBlank()) "image/jpeg" else "*/*"
            putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, uris)
            putExtra(android.content.Intent.EXTRA_TEXT, "Оберіг: зразки екранів для покращення (${uris.size} файлів)")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (preferWhatsApp) {
            for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
                val ok = runCatching {
                    ctx.startActivity(android.content.Intent(intent).setPackage(pkg).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }.isSuccess
                if (ok) return true
            }
        }
        ctx.startActivity(android.content.Intent.createChooser(intent, "Надіслати зразки").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}

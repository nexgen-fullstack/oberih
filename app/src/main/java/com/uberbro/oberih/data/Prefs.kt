package com.uberbro.oberih.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

enum class FlashMode(val label: String) { FULL("Весь екран"), FRAME("Тільки рамка") }
enum class VoiceLang(val label: String) { UK("Українська"), EN("English") }
enum class DistanceUnit(val label: String) { KM("Кілометри"), MI("Милі") }
data class SosContact(val name: String, val phone: String)

/** Усі налаштування програми. Значення за замовчуванням підібрані для Чикаго. */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("oberih", Context.MODE_PRIVATE)

    var voiceOn: Boolean
        get() = sp.getBoolean("voiceOn", true)
        set(v) = sp.edit { putBoolean("voiceOn", v) }

    var voiceLang: VoiceLang
        get() = enumOr(sp.getString("voiceLang", null), VoiceLang.UK)
        set(v) = sp.edit { putString("voiceLang", v.name) }

    var speakProfit: Boolean
        get() = sp.getBoolean("speakProfit", true)
        set(v) = sp.edit { putBoolean("speakProfit", v) }

    var flashMode: FlashMode
        get() = enumOr(sp.getString("flashMode", null), FlashMode.FULL)
        set(v) = sp.edit { putString("flashMode", v.name) }

    var sensitivity: Sensitivity
        get() = enumOr(sp.getString("sensitivity", null), Sensitivity.NORMAL)
        set(v) = sp.edit { putString("sensitivity", v.name) }

    /** Миль на галон. */
    var mpg: Float
        get() = sp.getFloat("mpg", 30f)
        set(v) = sp.edit { putFloat("mpg", v) }

    /** Ціна галона бензину, $. */
    var gasPrice: Float
        get() = sp.getFloat("gasPrice", 3.70f)
        set(v) = sp.edit { putFloat("gasPrice", v) }

    /** Знос, шини, масло тощо — $ на милю. */
    var wearPerMile: Float
        get() = sp.getFloat("wearPerMile", 0.10f)
        set(v) = sp.edit { putFloat("wearPerMile", v) }

    /** Мінімальний чистий заробіток за годину, який вважаємо «вигідно». */
    var targetPerHour: Float
        get() = sp.getFloat("targetPerHour", 25f)
        set(v) = sp.edit { putFloat("targetPerHour", v) }

    /** Попередження про поліцію з екрана Waze / Google Maps. */
    var policeAlerts: Boolean
        get() = sp.getBoolean("policeAlerts", true)
        set(v) = sp.edit { putBoolean("policeAlerts", v) }

    /** Живі зони під час поїздки (за GPS). */
    var liveZones: Boolean
        get() = sp.getBoolean("liveZones", true)
        set(v) = sp.edit { putBoolean("liveZones", v) }

    /** Попередження про аварії, перекриття, небезпеки попереду . */
    var roadAlerts: Boolean
        get() = sp.getBoolean("roadAlerts", true)
        set(v) = sp.edit { putBoolean("roadAlerts", v) }

    /** За скільки метрів до місця повторити попередження. */
    var nearMeters: Int
        get() = sp.getInt("nearMeters", 500)
        set(v) = sp.edit { putInt("nearMeters", v) }

    var distanceUnit: DistanceUnit
        get() = enumOr(sp.getString("distanceUnit", null), DistanceUnit.KM)
        set(v) = sp.edit { putString("distanceUnit", v.name) }

    /** Автоматичні скріншоти екранів Uber/Lyft/Waze для покращення розпізнавання. */
    var collectScreens: Boolean
        get() = sp.getBoolean("collectScreens", true)
        set(v) = sp.edit { putBoolean("collectScreens", v) }

    /** Люди для SOS (рідні, друзі поруч). Старий одиночний номер переноситься автоматично. */
    var sosContacts: List<SosContact>
        get() {
            val raw = sp.getString("sosContacts", null)
            if (raw == null) {
                val old = sp.getString("sosNumber", "").orEmpty().trim()
                return if (old.isNotEmpty()) listOf(SosContact("Контакт", old)) else emptyList()
            }
            return runCatching {
                val arr = org.json.JSONArray(raw)
                (0 until arr.length()).map { arr.getJSONObject(it).let { o -> SosContact(o.optString("name"), o.optString("phone")) } }
            }.getOrDefault(emptyList())
        }
        set(v) = sp.edit {
            val arr = org.json.JSONArray()
            v.filter { it.phone.isNotBlank() }.forEach { arr.put(org.json.JSONObject().put("name", it.name.trim()).put("phone", it.phone.trim())) }
            putString("sosContacts", arr.toString())
        }

    /** Ім'я водія для повідомлення SOS. */
    var driverName: String
        get() = sp.getString("driverName", "") ?: ""
        set(v) = sp.edit { putString("driverName", v.trim()) }

    /** Майстер першого налаштування вже пройдено. */
    var setupDone: Boolean
        get() = sp.getBoolean("setupDone", false)
        set(v) = sp.edit { putBoolean("setupDone", v) }

    var lastUpdateCheck: Long
        get() = sp.getLong("lastUpdateCheck", 0)
        set(v) = sp.edit { putLong("lastUpdateCheck", v) }

    var latestVersion: String?
        get() = sp.getString("latestVersion", null)
        set(v) = sp.edit { putString("latestVersion", v) }

    var latestApkUrl: String?
        get() = sp.getString("latestApkUrl", null)
        set(v) = sp.edit { putString("latestApkUrl", v) }

    var latestNotes: String?
        get() = sp.getString("latestNotes", null)
        set(v) = sp.edit { putString("latestNotes", v) }

    var lastDataError: String?
        get() = sp.getString("lastDataError", null)
        set(v) = sp.edit { putString("lastDataError", v) }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, def: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: def
}

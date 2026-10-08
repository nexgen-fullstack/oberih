package com.uberbro.oberih.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.uberbro.oberih.data.CrimeRepository
import com.uberbro.oberih.data.Level
import com.uberbro.oberih.data.Prefs
import com.uberbro.oberih.data.RiskMap
import com.uberbro.oberih.data.VoiceLang
import com.uberbro.oberih.util.SunTimes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/**
 * «Живі зони»: поки працює Uber чи Lyft, Оберіг стежить за GPS і заздалегідь (~400 м)
 * попереджає кольором і голосом, коли машина в'їжджає в жовту / помаранчеву / червону зону.
 * Не залежить від навігатора — працює з навігацією Uber, Lyft чи будь-якою іншою.
 */
class LiveZones(
    private val ctx: Context,
    private val overlay: AlertOverlay,
    private val speaker: Speaker,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
) : LocationListener {
    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var tracking = false
    private var lastDriverAppAt = 0L
    private var map: RiskMap? = null
    private var mapLoadedAt = 0L

    /** Останній рівень, про який попередили, і коли. */
    private var announced: Level = Level.UNKNOWN
    private var announcedArea = ""
    private var announcedAt = 0L
    private var calmSince = 0L
    private val lastByArea = HashMap<String, Long>()

    /** Викликається на кожну подію з Uber/Lyft — означає, що водій на зміні. */
    fun onDriverAppActive() {
        lastDriverAppAt = SystemClock.elapsedRealtime()
        if (!tracking) start()
    }

    fun hasPermission(): Boolean {
        val fine = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val bg = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine && bg
    }

    @SuppressLint("MissingPermission")
    private fun start() {
        if (!prefs.liveZones || !hasPermission()) return
        runCatching {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 4_000L, 20f, this, Looper.getMainLooper())
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10_000L, 50f, this, Looper.getMainLooper())
            }
            tracking = true
            Log.i(TAG, "live zones: start")
        }.onFailure { Log.w(TAG, "live zones start", it) }
    }

    fun stop() {
        if (!tracking) return
        runCatching { lm.removeUpdates(this) }
        tracking = false
        Log.i(TAG, "live zones: stop")
    }

    override fun onLocationChanged(loc: Location) {
        val now = SystemClock.elapsedRealtime()
        // Uber/Lyft не було видно 20 хвилин — зміна закінчилась, GPS вимикаємо (батарея).
        if (now - lastDriverAppAt > 20 * 60_000L || !prefs.liveZones) { stop(); return }
        if (loc.hasAccuracy() && loc.accuracy > 120f) return
        val m = map
        if (m == null || now - mapLoadedAt > 60 * 60_000L) {
            mapLoadedAt = now
            scope.launch { map = CrimeRepository.map(ctx) }
            if (m == null) return
        }
        evaluate(m, loc, now)
    }

    private fun evaluate(m: RiskMap, loc: Location, now: Long) {
        val night = SunTimes.isNight()
        val sens = prefs.sensitivity
        val here = m.levelAt(loc.latitude, loc.longitude, night, sens)
        // Точка попереду за напрямком руху: ~400 м (на швидкості — до ~700 м), щоб попередити заздалегідь.
        var ahead = here
        var aheadLat = loc.latitude
        var aheadLon = loc.longitude
        if (loc.hasBearing() && loc.hasSpeed() && loc.speed > 3f) {
            val dist = (400.0 + loc.speed * 15.0).coerceAtMost(700.0)
            val b = Math.toRadians(loc.bearing.toDouble())
            aheadLat = loc.latitude + dist * cos(b) / 111_320.0
            aheadLon = loc.longitude + dist * sin(b) / (111_320.0 * cos(Math.toRadians(loc.latitude)))
            ahead = m.levelAt(aheadLat, aheadLon, night, sens)
        }
        val worst = Level.worst(listOf(here, ahead))
        if (worst == Level.UNKNOWN) return

        val area = m.areaName(aheadLat, aheadLon) ?: m.areaName(loc.latitude, loc.longitude) ?: ""
        // Попереджаємо, коли зона стала небезпечнішою АБО почався інший небезпечний район.
        if (worst.severity >= Level.YELLOW.severity && (worst.severity > announced.severity || (area != announcedArea && now - announcedAt > 60_000L))) {
            val key = "$area|${worst.name}"
            // Ту саму зону не повторюємо частіше ніж раз на 5 хвилин.
            if (now - (lastByArea[key] ?: 0L) < 5 * 60_000L) { announced = worst; announcedArea = area; return }
            lastByArea[key] = now
            announced = worst; announcedArea = area; announcedAt = now; calmSince = 0L
            val sub = listOf(if (here == worst) "ви в зоні" else "попереду", area).filter { it.isNotBlank() }.joinToString(" · ")
            overlay.showZone(worst, sub, night, prefs.flashMode)
            if (prefs.voiceOn) speak(Phrases.zoneAhead(worst, here == worst, speaker.effectiveLang(prefs.voiceLang)))
        } else if (worst.severity < announced.severity) {
            // Виїхали з небезпечної зони: після ~40 с спокою — коротке «зелена зона».
            if (calmSince == 0L) calmSince = now
            if (now - calmSince > 40_000L) {
                val wasDanger = announced.severity >= Level.ORANGE.severity
                announced = worst; announcedArea = ""; calmSince = 0L
                if (wasDanger && worst == Level.GREEN) {
                    overlay.showZone(Level.GREEN, m.areaName(loc.latitude, loc.longitude) ?: "", night, prefs.flashMode)
                    if (prefs.voiceOn) speak(Phrases.zoneAhead(Level.GREEN, true, speaker.effectiveLang(prefs.voiceLang)))
                }
            }
        } else {
            calmSince = 0L
        }
    }

    private fun speak(text: String) = speaker.speak(text, speaker.effectiveLang(prefs.voiceLang))

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    companion object { private const val TAG = "OberihLive" }
}

/** Фрази для живих зон (окремо, щоб не плутати з фразами замовлення). */
fun Phrases.zoneAhead(level: Level, inside: Boolean, lang: VoiceLang): String = when (lang) {
    VoiceLang.UK -> when (level) {
        Level.RED -> if (inside) "Увага! Ви в червоній зоні." else "Увага! Попереду червона зона."
        Level.ORANGE -> if (inside) "Увага! Нічна небезпечна зона." else "Увага! Попереду нічна небезпечна зона."
        Level.YELLOW -> if (inside) "Жовта зона. Будь уважний." else "Попереду жовта зона."
        Level.GREEN -> "Зелена зона."
        Level.UNKNOWN -> ""
    }
    VoiceLang.EN -> when (level) {
        Level.RED -> if (inside) "Warning! You are in a red zone." else "Warning! Red zone ahead."
        Level.ORANGE -> "Warning! Night danger zone ahead."
        Level.YELLOW -> "Yellow zone ahead."
        Level.GREEN -> "Green zone."
        Level.UNKNOWN -> ""
    }
}

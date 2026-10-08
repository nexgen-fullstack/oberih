package com.uberbro.oberih.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder
import kotlin.coroutines.resume

/** SOS: одним натисканням відкрити WhatsApp (або SMS) з повідомленням і точкою на карті. */
object Sos {

    @SuppressLint("MissingPermission")
    suspend fun location(ctx: Context): Location? {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused")
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        val last = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < 2 * 60_000) return last
        val fresh = withTimeoutOrNull(8_000) {
            suspendCancellableCoroutine<Location?> { cont ->
                val provider = providers.firstOrNull() ?: run { cont.resume(null); return@suspendCancellableCoroutine }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val cancel = CancellationSignal()
                    cont.invokeOnCancellation { cancel.cancel() }
                    lm.getCurrentLocation(provider, cancel, ctx.mainExecutor) { if (cont.isActive) cont.resume(it) }
                } else {
                    @Suppress("DEPRECATION")
                    lm.requestSingleUpdate(provider, { if (cont.isActive) cont.resume(it) }, android.os.Looper.getMainLooper())
                }
            }
        }
        return fresh ?: last
    }

    fun message(loc: Location?): String {
        val where = loc?.let { "https://maps.google.com/?q=${"%.6f".format(java.util.Locale.US, it.latitude)},${"%.6f".format(java.util.Locale.US, it.longitude)}" }
            ?: "(не вдалося визначити місце)"
        return "🆘 Мені потрібна допомога! Моє місце зараз: $where"
    }

    fun send(ctx: Context, number: String, loc: Location?) {
        val text = message(loc)
        val digits = number.filter { it.isDigit() }
        val intents = buildList {
            if (digits.length >= 7) {
                add(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits?text=" + URLEncoder.encode(text, "UTF-8")))
                    .setPackage("com.whatsapp"))
                add(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).putExtra("sms_body", text))
            }
            add(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Надіслати SOS"))
        }
        for (i in intents) {
            if (runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
    }
}

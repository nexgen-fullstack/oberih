package com.uberbro.oberih.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.telephony.SmsManager
import android.util.Log
import com.uberbro.oberih.data.SosContact
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder
import kotlin.coroutines.resume

/** SOS: повідомлення з точкою на карті кільком людям (SMS усім одразу, WhatsApp, дзвінок 911). */
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

    fun message(loc: Location?, driver: String = ""): String {
        val where = loc?.let {
            "https://maps.google.com/?q=${"%.6f".format(java.util.Locale.US, it.latitude)},${"%.6f".format(java.util.Locale.US, it.longitude)}"
        } ?: "(не вдалося визначити місце)"
        val who = if (driver.isNotBlank()) "$driver: " else ""
        return "🆘 ${who}Мені потрібна допомога! Моє місце зараз: $where"
    }

    private fun digits(phone: String) = phone.filter { it.isDigit() }

    /** Надсилає SMS усім одразу, без додаткових вікон. Повертає скільком вдалося. */
    fun smsAll(ctx: Context, contacts: List<SosContact>, text: String): Int {
        @Suppress("DEPRECATION")
        val sms = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
        var ok = 0
        for (c in contacts) {
            val to = c.phone.filter { it.isDigit() || it == '+' }
            if (to.length < 7) continue
            runCatching {
                sms.sendMultipartTextMessage(to, null, sms.divideMessage(text), null, null)
                ok++
            }.onFailure { Log.w("Sos", "sms to $to", it) }
        }
        return ok
    }

    fun whatsApp(ctx: Context, contact: SosContact, text: String) {
        val url = "https://wa.me/${digits(contact.phone)}?text=" + URLEncoder.encode(text, "UTF-8")
        val tries = listOf(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("com.whatsapp"),
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("com.whatsapp.w4b"),
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${contact.phone}")).putExtra("sms_body", text),
        )
        for (i in tries) if (runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }

    fun call911(ctx: Context) {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:911")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun shareAny(ctx: Context, text: String) {
        ctx.startActivity(
            Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Надіслати SOS")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

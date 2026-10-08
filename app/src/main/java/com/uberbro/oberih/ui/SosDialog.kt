package com.uberbro.oberih.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uberbro.oberih.data.Prefs
import com.uberbro.oberih.util.Sos

/** Вікно SOS: SMS усім одразу, WhatsApp кожному, дзвінок 911. */
@Composable
fun SosDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val contacts = remember { prefs.sosContacts }
    var text by remember { mutableStateOf<String?>(null) }

    fun granted(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    var permResolved by remember { mutableStateOf(granted(Manifest.permission.ACCESS_FINE_LOCATION)) }
    val locPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permResolved = true }
    LaunchedEffect(Unit) {
        if (!permResolved) locPerm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    LaunchedEffect(permResolved) {
        if (!permResolved) return@LaunchedEffect
        val loc = runCatching { Sos.location(ctx) }.getOrNull()
        text = Sos.message(loc, prefs.driverName)
    }

    fun sendSms() {
        val n = Sos.smsAll(ctx, contacts, text ?: Sos.message(null, prefs.driverName))
        Toast.makeText(ctx, if (n > 0) "SOS надіслано: $n" else "Не вдалося надіслати SMS", Toast.LENGTH_LONG).show()
    }
    val smsPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) sendSms() else Toast.makeText(ctx, "Без дозволу на SMS — надішли через WhatsApp нижче", Toast.LENGTH_LONG).show()
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("🆘 SOS", fontWeight = FontWeight.Black, fontSize = 24.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text ?: "Визначаю місце…", fontSize = 14.sp)
                if (contacts.isNotEmpty()) {
                    Button(
                        onClick = {
                            if (granted(Manifest.permission.SEND_SMS)) sendSms() else smsPerm.launch(Manifest.permission.SEND_SMS)
                        },
                        enabled = text != null,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828), contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) { Text("📩 SMS усім (${contacts.size}) — одразу", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
                    contacts.forEach { c ->
                        OutlinedButton(onClick = { Sos.whatsApp(ctx, c, text ?: Sos.message(null, prefs.driverName)) },
                            modifier = Modifier.fillMaxWidth()) {
                            Text("WhatsApp: ${c.name.ifBlank { c.phone }}")
                        }
                    }
                } else {
                    Hint("Людей для SOS ще не додано — додай їх у Налаштуваннях → SOS. Поки що можна надіслати будь-кому:")
                }
                OutlinedButton(onClick = { Sos.shareAny(ctx, text ?: Sos.message(null, prefs.driverName)) },
                    modifier = Modifier.fillMaxWidth()) { Text("Надіслати іншим способом") }
                Button(
                    onClick = { Sos.call911(ctx) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E64FF), contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("📞 Подзвонити 911", fontWeight = FontWeight.Bold) }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Закрити") } },
    )
}

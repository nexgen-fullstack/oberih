package com.uberbro.oberih.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uberbro.oberih.data.Journal
import com.uberbro.oberih.data.Level
import com.uberbro.oberih.data.ScreenSamples
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun JournalScreen(modifier: Modifier) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { Journal.load(ctx) }
    val entries by Journal.entries.collectAsState()
    var samples by remember { mutableIntStateOf(ScreenSamples.count(ctx)) }
    var shots by remember { mutableIntStateOf(ScreenSamples.images(ctx).size) }
    var sizeMb by remember { mutableStateOf(ScreenSamples.sizeBytes(ctx) / 1_048_576.0) }
    var askClear by remember { mutableStateOf(false) }
    fun clearSamples() { ScreenSamples.clear(ctx); samples = 0; shots = 0; sizeMb = 0.0 }
    if (askClear) AlertDialog(
        onDismissRequest = { askClear = false },
        title = { Text("Надіслав?") },
        text = { Text("Очистити папку зі скріншотами, щоб не займала пам'ять телефону (${"%.1f".format(sizeMb)} МБ)?") },
        confirmButton = { Button(onClick = { clearSamples(); askClear = false }) { Text("Так, очистити") } },
        dismissButton = { TextButton(onClick = { askClear = false }) { Text("Ні, залишити") } },
    )
    val startOfDay = remember {
        Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis
    }
    val today = entries.filter { it.time >= startOfDay }
    val tf = remember { SimpleDateFormat("dd.MM HH:mm", Locale("uk")) }

    LazyColumn(modifier.padding(horizontal = 16.dp), contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Журнал замовлень", fontSize = 24.sp, fontWeight = FontWeight.Bold) }
        item {
            SectionCard {
                Text("Сьогодні", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                val danger = today.count { it.level == Level.RED || it.level == Level.ORANGE }
                val ph = today.mapNotNull { it.perHour }
                Hint("Замовлень побачено: ${today.size}\nНебезпечних (червоні/помаранчеві): $danger" +
                    (if (ph.isNotEmpty()) "\nСередньо: $${ph.average().roundToInt()}/год" else ""))
            }
        }
        if (entries.isEmpty()) item { Hint("Тут з'являтимуться замовлення, які перевірив Оберіг.") }
        items(entries.take(200)) { e ->
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LevelDot(e.level, 18); Spacer(Modifier.width(10.dp))
                    Text("${tf.format(Date(e.time))} · ${e.app} · ${e.level.word}", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
                val money = listOfNotNull(e.fare?.let { "$${"%.2f".format(it)}" }, e.perHour?.let { "$${it.roundToInt()}/год" },
                    e.profit.ifBlank { null }).joinToString(" · ")
                if (money.isNotBlank()) Text(money, fontSize = 15.sp)
                e.pickup?.let { Text("Звідки: $it", fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                e.dropoff?.let { Text("Куди: $it", fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (e.reason.isNotBlank()) Hint(e.reason)
            }
        }
        item {
            SectionCard {
                Text("Зразки екранів для покращення", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Hint("Скріншотів: $shots · текстових зразків: $samples · займає ${"%.1f".format(sizeMb)} МБ (не більше ~1,5 МБ: старі видаляються самі).\nОберіг сам знімає тільки екрани замовлень Uber/Lyft. Раз на кілька днів надсилай їх розробнику — так програма краще розпізнаватиме замовлення. На скріншотах можуть бути адреси пасажирів, тому надсилай тільки своїм.")
                Button(onClick = {
                    if (!ScreenSamples.share(ctx, preferWhatsApp = true))
                        android.widget.Toast.makeText(ctx, "Зразків поки немає — вони з'являться під час роботи", android.widget.Toast.LENGTH_LONG).show()
                    else askClear = true
                }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366), contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth()) { Text("Надіслати у WhatsApp", fontWeight = FontWeight.Bold) }
                Button(onClick = { clearSamples() }, enabled = shots + samples > 0,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF455A64), contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth()) { Text("🗑 Очистити папку зі скріншотами") }
                OutlinedButton(onClick = { if (ScreenSamples.share(ctx, preferWhatsApp = false)) askClear = true }) { Text("Надіслати іншим способом") }
            }
        }
        if (entries.isNotEmpty()) item {
            OutlinedButton(onClick = { Journal.clear(ctx) }, modifier = Modifier.fillMaxWidth()) { Text("Очистити журнал") }
        }
    }
}

package com.uberbro.oberih.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uberbro.oberih.BuildConfig
import com.uberbro.oberih.data.FlashMode
import com.uberbro.oberih.data.Prefs
import com.uberbro.oberih.data.Sensitivity
import com.uberbro.oberih.data.VoiceLang
import com.uberbro.oberih.util.UpdateChecker
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(modifier: Modifier) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()
    var voiceOn by remember { mutableStateOf(prefs.voiceOn) }
    var lang by remember { mutableStateOf(prefs.voiceLang) }
    var speakProfit by remember { mutableStateOf(prefs.speakProfit) }
    var flash by remember { mutableStateOf(prefs.flashMode) }
    var sens by remember { mutableStateOf(prefs.sensitivity) }

    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Налаштування", fontSize = 24.sp, fontWeight = FontWeight.Bold)

        SectionTitle("ГОЛОС")
        SectionCard {
            SwitchRow("Голосові підказки", voiceOn) { voiceOn = it; prefs.voiceOn = it }
            SwitchRow("Казати заробіток за годину", speakProfit) { speakProfit = it; prefs.speakProfit = it }
            Text("Мова голосу")
            Segments(VoiceLang.entries, lang, { it.label }) { lang = it; prefs.voiceLang = it }
        }

        SectionTitle("СИГНАЛ НА ЕКРАНІ")
        SectionCard {
            Segments(FlashMode.entries, flash, { it.label }) { flash = it; prefs.flashMode = it }
            Hint("«Весь екран» — 3 кольорові спалахи, потім рамка. «Тільки рамка» — менше відволікає. Вночі спалах автоматично м'якший. Дотики завжди проходять крізь сигнал.")
        }

        SectionTitle("НАСКІЛЬКИ СУВОРО ФАРБУВАТИ ЗОНИ")
        SectionCard {
            Segments(Sensitivity.entries, sens, { it.label }) { sens = it; prefs.sensitivity = it }
            Hint(when (sens) {
                Sensitivity.STRICT -> "Суворо: ~16% кварталів міста червоні, ще ~24% жовті. Більше попереджень."
                Sensitivity.NORMAL -> "Звичайно: ~12% найнебезпечніших кварталів червоні, ще ~18% жовті."
                Sensitivity.LENIENT -> "М'яко: червоні лише ~8% найгірших кварталів, жовті ~14%."
            })
        }

        SectionTitle("ВИГІДНІСТЬ ПОЇЗДКИ")
        SectionCard {
            NumberField("Скільки миль проїжджає машина на 1 галоні (MPG)", prefs.mpg) { prefs.mpg = it }
            NumberField("Ціна галона бензину, $", prefs.gasPrice) { prefs.gasPrice = it }
            NumberField("Знос (шини, масло, ремонт), $ на милю", prefs.wearPerMile) { prefs.wearPerMile = it }
            NumberField("Мінімум «вигідно», $ чистими за годину", prefs.targetPerHour) { prefs.targetPerHour = it }
            Hint("Чистий заробіток = ціна замовлення − бензин − знос, поділено на весь час (дорога до пасажира + поїздка).")
        }

        SectionTitle("ПРО ПРОГРАМУ")
        SectionCard {
            Text("Оберіг ${BuildConfig.VERSION_NAME}")
            Hint("Дані про злочини: офіційний портал міста Чикаго (Chicago Data Portal), за останні 6 місяців, із затримкою ~7 днів. Це статистика, а не гарантія — завжди довіряй своїм очам.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        val u = runCatching { UpdateChecker.check(ctx, force = true) }
                        val msg = when {
                            u.isFailure -> "Не вдалося перевірити (немає інтернету?)"
                            u.getOrNull() == null -> "У тебе найновіша версія"
                            else -> "Є версія ${u.getOrNull()!!.version} — дивись Головну"
                        }
                        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                    }
                }) { Text("Перевірити оновлення") }
                OutlinedButton(onClick = {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${BuildConfig.UPDATE_REPO}/releases")))
                }) { Text("Сторінка версій") }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 16.sp)
        Switch(value, onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Segments(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, o ->
            SegmentedButton(selected = o == selected, onClick = { onSelect(o) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size)) { Text(label(o), fontSize = 13.sp) }
        }
    }
}

@Composable
private fun NumberField(label: String, initial: Float, onValid: (Float) -> Unit) {
    var text by remember { mutableStateOf(trim(initial)) }
    val valid = text.replace(',', '.').toFloatOrNull()?.let { it > 0f } == true
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t
            t.replace(',', '.').toFloatOrNull()?.takeIf { it > 0f }?.let(onValid)
        },
        label = { Text(label, fontSize = 13.sp) },
        isError = !valid,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun trim(f: Float): String = if (f == f.toInt().toFloat()) f.toInt().toString() else "%.2f".format(java.util.Locale.US, f).trimEnd('0')

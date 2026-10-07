package com.uberbro.oberih.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uberbro.oberih.data.Level
import com.uberbro.oberih.offer.OfferAnalyzer
import com.uberbro.oberih.offer.ParsedOffer
import com.uberbro.oberih.offer.Profit
import com.uberbro.oberih.offer.Verdict
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Ручна перевірка: ввести (або «Поділитися» з іншої програми) адресу і дізнатися колір зони. */
@Composable
fun CheckScreen(modifier: Modifier, shared: String?, consumeShared: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val kb = LocalSoftwareKeyboardController.current
    var from by remember { mutableStateOf("") }
    var to by remember { mutableStateOf("") }
    var fare by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Verdict?>(null) }

    fun run() {
        if (to.isBlank() && from.isBlank()) return
        kb?.hide()
        busy = true; result = null
        scope.launch {
            val offer = ParsedOffer("Ручна", fare.replace(',', '.').toDoubleOrNull(), null, null, null, null,
                from.ifBlank { null }, to.ifBlank { null })
            result = OfferAnalyzer.analyze(ctx, offer)
            busy = false
        }
    }

    LaunchedEffect(shared) {
        if (shared != null) { to = shared.trim(); from = ""; consumeShared(); run() }
    }

    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Перевірити адресу", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Hint("Введи адресу або назву району. Можна також «Поділитися» адресою з Google Maps чи месенджера → Оберіг.")
        OutlinedTextField(to, { to = it }, label = { Text("Куди (адреса висадки)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(from, { from = it }, label = { Text("Звідки (необов'язково)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(fare, { fare = it }, label = { Text("Ціна поїздки, $ (необов'язково)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        Button(onClick = { run() }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            if (busy) CircularProgressIndicator(Modifier.width(22.dp).height(22.dp), strokeWidth = 2.dp)
            else Text("Перевірити", fontSize = 17.sp)
        }
        result?.let { VerdictCard(it) }
    }
}

@Composable
fun VerdictCard(v: Verdict) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = v.level.color())) {
        Column(Modifier.padding(18.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val c = v.level.onColor()
            Text(v.level.word, fontSize = 28.sp, fontWeight = FontWeight.Black, color = c)
            Text(v.level.title + if (v.isNight) " · зараз ніч" else "", fontSize = 16.sp, color = c)
            if (v.reason.isNotBlank()) Text(v.reason, fontSize = 15.sp, color = c)
            Spacer(Modifier.height(4.dp))
            @Composable fun line(label: String, l: Level, area: String?) {
                if (l == Level.UNKNOWN && area == null) return
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LevelDot(l); Spacer(Modifier.width(8.dp))
                    Text("$label: ${l.title}${area?.let { " ($it)" } ?: ""}", color = c, fontSize = 14.sp)
                }
            }
            line("Подача", v.pickupLevel, v.pickupArea)
            line("Висадка", v.dropoffLevel, v.dropoffArea)
            if (v.routeWorst != Level.UNKNOWN) line("Найгірше на маршруті", v.routeWorst, null)
            v.netPerHour?.let {
                Text("≈ $${it.roundToInt()}/год чистими · ${v.profit.word}" +
                    (v.perMile?.let { pm -> " · $${"%.2f".format(pm)}/миля" } ?: ""), color = c, fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold)
            }
            if (v.profit == Profit.UNKNOWN && v.fare != null) Text("Щоб порахувати вигідність, потрібні обидві адреси.", color = c, fontSize = 13.sp)
        }
    }
}

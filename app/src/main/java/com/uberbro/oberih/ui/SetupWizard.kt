package com.uberbro.oberih.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uberbro.oberih.data.CrimeRepository
import com.uberbro.oberih.data.Prefs
import com.uberbro.oberih.service.OberihAccessibilityService
import com.uberbro.oberih.service.Speaker
import kotlinx.coroutines.delay

private enum class Step(val title: String) {
    DATA("Карта небезпечних зон"),
    A11Y("Дозвіл «Спеціальні можливості»"),
    RESTRICTED("Дозволити обмежені налаштування"),
    BATTERY("Робота без перерв"),
    NOTIFY("Сповіщення про оновлення"),
    LOCATION("Місце для SOS і живих зон"),
    BG_LOCATION("Місце: «Дозволяти завжди»"),
    SMS("SMS для SOS"),
    VOICE("Український голос"),
    DONE("Готово!"),
}

private fun granted(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

/** Відкриває список Спеціальних можливостей і (де Android це вміє) підсвічує в ньому Оберіг. */
fun openAccessibility(ctx: Context) {
    val cn = ComponentName(ctx, OberihAccessibilityService::class.java).flattenToString()
    val highlight = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
        putExtra(":settings:fragment_args_key", cn)
        putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", cn) })
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { ctx.startActivity(highlight) }
        .onFailure { runCatching { ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
}

fun openAppDetails(ctx: Context) {
    runCatching {
        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * Майстер «Налаштувати все автоматично»: сам по черзі відкриває кожен потрібний екран чи запит дозволу.
 * Водієві лишається тільки натиснути «Дозволити» або поставити повзунок.
 */
@Composable
fun SetupWizard(resumeTick: Int, resumed: Boolean, onFinish: (openDemo: Boolean, openSos: Boolean) -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val running by OberihAccessibilityService.running.collectAsState()
    val grid by CrimeRepository.grid.collectAsState()
    val progress by CrimeRepository.progress.collectAsState()
    var step by remember { mutableStateOf(Step.DATA) }
    var a11yTries by remember { mutableIntStateOf(0) }
    var dataError by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var restrictedOpened by remember { mutableStateOf(false) }
    val speaker = remember { Speaker(ctx) }
    DisposableEffect(Unit) { onDispose { speaker.shutdown() } }

    fun next() {
        step = when (step) {
            Step.DATA -> Step.A11Y
            Step.A11Y -> if (running) Step.BATTERY else Step.RESTRICTED
            Step.RESTRICTED -> Step.A11Y
            Step.BATTERY -> if (Build.VERSION.SDK_INT >= 33) Step.NOTIFY else Step.LOCATION
            Step.NOTIFY -> Step.LOCATION
            Step.LOCATION -> if (Build.VERSION.SDK_INT >= 29) Step.BG_LOCATION else Step.SMS
            Step.BG_LOCATION -> Step.SMS
            Step.SMS -> Step.VOICE
            Step.VOICE -> Step.DONE
            Step.DONE -> Step.DONE
        }
    }

    fun done(s: Step): Boolean = when (s) {
        Step.DATA -> grid != null
        Step.A11Y, Step.RESTRICTED -> running
        Step.BATTERY -> isIgnoringBattery(ctx)
        Step.NOTIFY -> Build.VERSION.SDK_INT < 33 || granted(ctx, Manifest.permission.POST_NOTIFICATIONS)
        Step.LOCATION -> granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
        Step.BG_LOCATION -> Build.VERSION.SDK_INT < 29 || granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        Step.SMS -> granted(ctx, Manifest.permission.SEND_SMS) ||
            !ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        Step.VOICE -> speaker.ukrainianMissing == false
        Step.DONE -> false
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { next() }

    fun act(s: Step) {
        when (s) {
            Step.DATA -> {}
            Step.A11Y -> { a11yTries++; openAccessibility(ctx) }
            Step.RESTRICTED -> { restrictedOpened = true; openAppDetails(ctx) }
            Step.BATTERY -> runCatching {
                ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
            }.onFailure { next() }
            Step.NOTIFY -> permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            Step.LOCATION -> permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            Step.BG_LOCATION -> permLauncher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
            Step.SMS -> permLauncher.launch(arrayOf(Manifest.permission.SEND_SMS))
            Step.VOICE -> runCatching { ctx.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) }.onFailure { next() }
            Step.DONE -> {}
        }
    }

    // Дані: завантажуються самі.
    LaunchedEffect(retry) {
        CrimeRepository.load(ctx)
        dataError = CrimeRepository.refreshIfStale(ctx)
    }

    // Кожен новий крок: якщо вже зроблено — пропускаємо; інакше через 2 с самі відкриваємо потрібний екран.
    LaunchedEffect(step, resumed) {
        if (step == Step.VOICE) repeat(25) { if (speaker.ukrainianMissing == null) delay(200) }
        if (step != Step.DONE && step != Step.RESTRICTED && done(step)) { next(); return@LaunchedEffect }
        if (step == Step.DATA || step == Step.DONE) return@LaunchedEffect
        // Наступний екран відкриваємо тільки коли водій повернувся в Оберіг і встиг прочитати підказку.
        if (!resumed) return@LaunchedEffect
        delay(2_500)
        act(step)
    }
    // Повернулись з налаштувань — перевіряємо, чи вийшло.
    LaunchedEffect(resumeTick, running, grid) {
        when (step) {
            Step.DATA -> if (grid != null) next()
            Step.A11Y -> if (running) next()
                else if (a11yTries in 1..2 && Build.VERSION.SDK_INT >= 33) { restrictedOpened = false; step = Step.RESTRICTED }
            Step.RESTRICTED -> if (running) step = Step.BATTERY else if (restrictedOpened) step = Step.A11Y
            Step.BATTERY -> if (isIgnoringBattery(ctx)) next()
            Step.BG_LOCATION -> if (granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)) next()
            Step.VOICE -> if (speaker.ukrainianMissing == false) next()
            else -> {}
        }
    }

    val all = Step.entries.filter { it != Step.RESTRICTED && (it != Step.NOTIFY || Build.VERSION.SDK_INT >= 33) }
    val index = all.indexOf(if (step == Step.RESTRICTED) Step.A11Y else step) + 1

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onSurface) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("🛡 Налаштування Оберегу", fontSize = 26.sp, fontWeight = FontWeight.Black)
        if (step != Step.DONE) {
            Text("Крок $index з ${all.size - 1}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            LinearProgressIndicator(progress = { (index - 1f) / (all.size - 1) }, modifier = Modifier.fillMaxWidth())
        }
        Text(step.title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            when (step) {
                Step.DATA -> if (progress != null) "Завантажую дані поліції Чикаго й Мілвокі… ${((progress ?: 0f) * 100).toInt()}%"
                else dataError ?: "Готую карту…"
                Step.A11Y -> "Зараз відкриється список «Спеціальні можливості».\n\n" +
                    "1. Знайди «Оберіг» (буває в розділі «Встановлені програми» / «Завантажені програми»).\n" +
                    "2. Увімкни повзунок.\n3. Натисни «Дозволити».\n4. Повернись сюди кнопкою «Назад»."
                Step.RESTRICTED -> "Схоже, повзунок був сірий («Обмежене налаштування») — Android так захищає програми не з Google Play.\n\n" +
                    "Зараз відкриється сторінка Оберегу:\n1. Натисни ⋮ (три крапки) угорі справа.\n" +
                    "2. «Дозволити обмежені налаштування» (підтвердь відбитком/PIN).\n3. Повернись сюди — я знову відкрию Спеціальні можливості."
                Step.BATTERY -> "Зараз з'явиться запит — натисни «Дозволити». Тоді телефон не вимикатиме Оберіг під час зміни."
                Step.NOTIFY -> "Зараз з'явиться запит — натисни «Дозволити». Так телефон скаже, коли вийде нова версія."
                Step.LOCATION -> "Зараз з'явиться запит — обери «Під час використання програми». Місце потрібне для SOS і для живих зон під час поїздки."
                Step.BG_LOCATION -> "Зараз відкриється сторінка дозволу місця — обери «Дозволяти завжди» (Allow all the time) і повернись кнопкою «Назад».\n\n" +
                    "Це потрібно, щоб під час поїздки в Uber чи Lyft Оберіг бачив, куди ти їдеш, і заздалегідь попереджав про червону чи жовту зону. " +
                    "GPS працює тільки коли відкритий Uber або Lyft."
                Step.SMS -> "Зараз з'явиться запит — натисни «Дозволити». Тоді SOS одним натисканням надішле SMS усім твоїм людям."
                Step.VOICE -> "На телефоні немає українського голосу. Зараз відкриється налаштування голосу: обери «Українська» і завантаж. " +
                    "(Можна пропустити — тоді підказки будуть англійською.)"
                Step.DONE -> "Усе налаштовано ✅\n\nТепер:\n• додай людей для SOS (рідні, друзі поруч);\n• натисни «Перевірити» — побачиш і почуєш, як працює Оберіг."
            },
            fontSize = 17.sp, lineHeight = 24.sp,
        )
        if (step == Step.DATA && progress != null) LinearProgressIndicator(progress = { progress ?: 0f }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        when (step) {
            Step.DONE -> {
                Button(onClick = { prefs.setupDone = true; onFinish(false, true) }, modifier = Modifier.fillMaxWidth().height(54.dp)) {
                    Text("🆘 Додати людей для SOS", fontSize = 17.sp)
                }
                Button(onClick = { prefs.setupDone = true; onFinish(true, false) }, modifier = Modifier.fillMaxWidth().height(54.dp)) {
                    Text("▶ Перевірити (імітація замовлення і Waze)", fontSize = 16.sp)
                }
                TextButton(onClick = { prefs.setupDone = true; onFinish(false, false) }) { Text("На головну") }
            }
            Step.DATA -> {
                if (dataError != null) OutlinedButton(onClick = { dataError = null; retry++ }) { Text("Спробувати ще раз") }
                TextButton(onClick = { next() }) { Text("Пропустити (завантажиться пізніше)") }
            }
            else -> {
                Button(onClick = { act(step) }, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Відкрити зараз", fontSize = 17.sp) }
                TextButton(onClick = { if (step == Step.RESTRICTED || step == Step.A11Y) step = Step.BATTERY else next() }) { Text("Пропустити цей крок") }
            }
        }
    }
}
}

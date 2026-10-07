package com.uberbro.oberih.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uberbro.oberih.BuildConfig
import com.uberbro.oberih.data.CrimeRepository
import com.uberbro.oberih.data.Level
import com.uberbro.oberih.data.Prefs
import com.uberbro.oberih.service.OberihAccessibilityService
import com.uberbro.oberih.service.Speaker
import com.uberbro.oberih.util.UpdateChecker
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val tab = mutableIntStateOf(0)
    private val sharedText = mutableStateOf<String?>(null)
    private val resumeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            OberihTheme {
                App(tab.intValue, { tab.intValue = it }, sharedText.value, { sharedText.value = null }, resumeTick.intValue)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            i.getStringExtra(Intent.EXTRA_TEXT)?.let { sharedText.value = it; tab.intValue = 1 }
        }
    }
}

@Composable
private fun App(tab: Int, setTab: (Int) -> Unit, shared: String?, consumeShared: () -> Unit, resumeTick: Int) {
    val items = listOf("Головна" to Icons.Filled.Home, "Перевірка" to Icons.Filled.Search,
        "Журнал" to Icons.Filled.List, "Налаштування" to Icons.Filled.Settings)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF161C22)) {
                items.forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(selected = tab == i, onClick = { setTab(i) },
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label, fontSize = 11.sp) })
                }
            }
        },
    ) { pad ->
        val m = Modifier.padding(pad).fillMaxSize()
        when (tab) {
            0 -> HomeScreen(m, resumeTick)
            1 -> CheckScreen(m, shared, consumeShared)
            2 -> JournalScreen(m)
            else -> SettingsScreen(m)
        }
    }
}

fun isIgnoringBattery(ctx: Context): Boolean =
    (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)

fun fmtDate(ms: Long): String = SimpleDateFormat("dd.MM HH:mm", Locale("uk")).format(Date(ms))

@Composable
fun HomeScreen(modifier: Modifier, resumeTick: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val running by OberihAccessibilityService.running.collectAsState()
    val grid by CrimeRepository.grid.collectAsState()
    val progress by CrimeRepository.progress.collectAsState()
    var dataError by remember { mutableStateOf(Prefs(ctx).lastDataError) }
    val battery = remember(resumeTick) { isIgnoringBattery(ctx) }
    val notifOk = remember(resumeTick) {
        Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    var update by remember { mutableStateOf(UpdateChecker.cached(Prefs(ctx))) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    // Тестовий голос працює навіть без увімкненого сервісу.
    val testSpeaker = remember { Speaker(ctx) }
    DisposableEffect(Unit) { onDispose { testSpeaker.shutdown() } }
    var missing by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(resumeTick) {
        repeat(50) { if (testSpeaker.ukrainianMissing != null) return@repeat; kotlinx.coroutines.delay(200) }
        missing = testSpeaker.ukrainianMissing
    }

    LaunchedEffect(Unit) {
        val g = CrimeRepository.load(ctx)
        if (CrimeRepository.isStale(g)) { dataError = CrimeRepository.refresh(ctx) }
        update = runCatching { UpdateChecker.check(ctx) }.getOrNull()
    }

    val ready = running && grid != null
    LazyColumn(modifier.padding(horizontal = 16.dp), contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(
                containerColor = if (ready) Color(0xFF1B5E20) else Color(0xFF8E1B1B))) {
                Column(Modifier.padding(18.dp).fillMaxWidth()) {
                    Text(if (ready) "🛡 Оберіг працює" else "⚠ Оберіг ще не готовий", fontSize = 24.sp,
                        fontWeight = FontWeight.Bold, color = Color.White)
                    Text(
                        if (ready) "Просто працюй в Uber чи Lyft. Коли прийде замовлення — екран блимне кольором, і ти почуєш підказку."
                        else "Виконай кроки нижче (з червоним знаком). Це потрібно зробити лише один раз.",
                        color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        update?.let { u ->
            item {
                SectionCard {
                    Text("Є нова версія ${u.version}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Hint("Натисни, завантаж файл і встанови поверх старої версії. Налаштування збережуться.")
                    Button(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u.apkUrl))) },
                        modifier = Modifier.fillMaxWidth()) { Text("Завантажити оновлення") }
                }
            }
        }
        item {
            SectionCard {
                StepRow(grid != null, "1. Карта небезпечних зон")
                when {
                    progress != null -> {
                        Hint("Завантажую дані поліції Чикаго… ${((progress ?: 0f) * 100).toInt()}%")
                        LinearProgressIndicator(progress = { progress ?: 0f }, modifier = Modifier.fillMaxWidth())
                    }
                    grid != null -> Hint("Оновлено ${fmtDate(grid!!.generatedAt)} · ${"%,d".format(grid!!.incidentCount).replace(',', ' ')} подій за 6 місяців · останні дані за ${grid!!.newestIncident}. Оновлюється саме раз на добу.")
                    else -> Hint(dataError ?: "Потрібен інтернет, щоб завантажити дані (≈3 МБ, один раз на добу).")
                }
                if (progress == null) OutlinedButton(onClick = { scope.launch { dataError = CrimeRepository.refresh(ctx) } }) {
                    Text(if (grid == null) "Завантажити зараз" else "Оновити зараз")
                }
            }
        }
        item {
            SectionCard {
                StepRow(running, "2. Дозвіл «Спеціальні можливості»")
                if (!running) {
                    Hint("Натисни кнопку → «Встановлені програми» (або «Завантажені») → «Оберіг» → увімкни перемикач → «Дозволити».")
                    Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                        modifier = Modifier.fillMaxWidth()) { Text("Відкрити Спеціальні можливості") }
                    Hint("Якщо перемикач сірий і пише «Обмежене налаштування»: натисни кнопку нижче → три крапки ⋮ угорі справа → «Дозволити обмежені налаштування» → повернись і увімкни ще раз.")
                    OutlinedButton(onClick = {
                        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                    }, modifier = Modifier.fillMaxWidth()) { Text("Відкрити сторінку програми") }
                } else Hint("Готово. Оберіг бачить тільки екрани Uber Driver і Lyft Driver.")
            }
        }
        item {
            SectionCard {
                StepRow(battery, "3. Робота без перерв")
                if (!battery) {
                    Hint("Щоб телефон не «присипляв» Оберіг під час зміни.")
                    Button(onClick = {
                        runCatching {
                            ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                        }.onFailure { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Дозволити роботу у фоні") }
                } else Hint("Готово.")
            }
        }
        item {
            SectionCard {
                StepRow(missing == false, "4. Голос", optional = true)
                Hint(when (missing) {
                    true -> "Українського голосу на телефоні немає — поки що підказки будуть англійською. Щоб додати: кнопка нижче → Google → «Встановити голосові дані» → Українська."
                    false -> "Український голос є."
                    null -> "Перевіряю голос…"
                })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val p = Prefs(ctx)
                        val lang = testSpeaker.effectiveLang(p.voiceLang)
                        testSpeaker.speak(com.uberbro.oberih.service.Phrases.levelPhrase(Level.GREEN, lang), lang)
                    }) { Text("Перевірити голос") }
                    if (missing == true) OutlinedButton(onClick = {
                        runCatching { ctx.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) }
                    }) { Text("Додати голос") }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) item {
            SectionCard {
                StepRow(notifOk, "5. Сповіщення про оновлення", optional = true)
                if (!notifOk) {
                    Hint("Необов'язково. Тоді телефон сам скаже, коли вийде нова версія Оберегу.")
                    OutlinedButton(onClick = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Дозволити") }
                } else Hint("Готово.")
            }
        }
        item {
            SectionCard {
                Text("Перевірити сигнал", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Hint("Натисни колір — побачиш і почуєш, як це буде під час роботи.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(Level.RED, Level.ORANGE, Level.YELLOW, Level.GREEN).forEach { l ->
                        Button(onClick = {
                            val s = OberihAccessibilityService.instance
                            if (s != null) s.demo(l, night = l == Level.ORANGE)
                            else Toast.makeText(ctx, "Спершу виконай крок 2 (Спеціальні можливості)", Toast.LENGTH_LONG).show()
                        }, colors = ButtonDefaults.buttonColors(containerColor = l.color(), contentColor = l.onColor()),
                            modifier = Modifier.weight(1f).height(52.dp), contentPadding = PaddingValues(2.dp)) {
                            Text(when (l) { Level.RED -> "Черв."; Level.ORANGE -> "Помар."; Level.YELLOW -> "Жовт."; else -> "Зел." },
                                fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Button(onClick = { ctx.startActivity(Intent(ctx, DemoOfferActivity::class.java)) },
                    modifier = Modifier.fillMaxWidth()) { Text("Повна перевірка: імітація замовлення") }
                Hint("Відкриє екран, схожий на замовлення Uber, і Оберіг відреагує на нього як на справжнє.")
            }
        }
        item {
            SectionCard {
                Text("Порада: навігація", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Hint("У Uber Driver: Меню → Account → App Settings → Navigation → Waze.\nУ Lyft Driver: Меню → Settings → Navigation → Waze.\nУ Waze: Settings → Alerts & reports → Police — увімкнено. Тоді Waze сам попереджатиме про поліцію, аварії й перекриття.")
            }
        }
        item { Hint("Оберіг ${BuildConfig.VERSION_NAME} · дані: Chicago Data Portal") }
    }
}

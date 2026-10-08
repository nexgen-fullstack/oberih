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
import com.uberbro.oberih.hazard.HazardType
import com.uberbro.oberih.hazard.Stage
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
    private val wizard = mutableStateOf(false)
    private val resumed = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        // Перший запуск після встановлення — одразу майстер налаштування.
        if (!Prefs(this).setupDone) wizard.value = true
        setContent {
            OberihTheme {
                if (wizard.value) {
                    SetupWizard(resumeTick.intValue, resumed.value) { openDemo, openSos ->
                        wizard.value = false
                        if (openSos) tab.intValue = 3
                        if (openDemo) startActivity(Intent(this, DemoOfferActivity::class.java))
                    }
                } else {
                    App(tab.intValue, { tab.intValue = it }, sharedText.value, { sharedText.value = null }, resumeTick.intValue,
                        startWizard = { wizard.value = true })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        resumed.value = true
        resumeTick.intValue++
    }

    override fun onPause() {
        resumed.value = false
        super.onPause()
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            i.getStringExtra(Intent.EXTRA_TEXT)?.let { sharedText.value = it; tab.intValue = 1 }
        }
    }
}

@Composable
private fun App(tab: Int, setTab: (Int) -> Unit, shared: String?, consumeShared: () -> Unit, resumeTick: Int, startWizard: () -> Unit) {
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
            0 -> HomeScreen(m, resumeTick, startWizard)
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
fun HomeScreen(modifier: Modifier, resumeTick: Int, startWizard: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val running by OberihAccessibilityService.running.collectAsState()
    val grid by CrimeRepository.grid.collectAsState()
    val progress by CrimeRepository.progress.collectAsState()
    val mke by CrimeRepository.milwaukee.collectAsState()
    val townCount = remember { CrimeRepository.towns(ctx).towns.size }
    var dataError by remember { mutableStateOf(Prefs(ctx).lastDataError) }
    val battery = remember(resumeTick) { isIgnoringBattery(ctx) }
    val notifOk = remember(resumeTick) {
        Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    var update by remember { mutableStateOf(UpdateChecker.cached(Prefs(ctx))) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    var sosOpen by remember { mutableStateOf(false) }
    if (sosOpen) SosDialog { sosOpen = false }

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
        CrimeRepository.map(ctx) // підвантажує й Мілвокі
        if (CrimeRepository.isStale(g)) { dataError = CrimeRepository.refreshIfStale(ctx) }
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
                        if (ready) "Просто працюй в Uber чи Lyft. Коли прийде замовлення — екран блимне кольором, і ти почуєш підказку. Коли Waze покаже поліцію чи аварію — увімкнеться мигалка і голос."
                        else "Виконай кроки нижче (з червоним знаком). Це потрібно зробити лише один раз.",
                        color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        if (!ready || !battery) item {
            Button(
                onClick = startWizard,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32), contentColor = Color.White),
                modifier = Modifier.fillMaxWidth().height(64.dp), shape = RoundedCornerShape(18.dp),
            ) { Text("⚙ Налаштувати все автоматично", fontSize = 19.sp, fontWeight = FontWeight.Bold) }
        }
        item {
            Button(
                onClick = { sosOpen = true },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828), contentColor = Color.White),
                modifier = Modifier.fillMaxWidth().height(60.dp), shape = RoundedCornerShape(18.dp),
            ) {
                Text("🆘 SOS — надіслати своє місце", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            if (Prefs(ctx).sosContacts.isEmpty()) Hint("Людей для SOS додай у Налаштуваннях → SOS (рідні, друзі поруч).")
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
                    grid != null -> Hint(
                        "Чикаго: ${"%,d".format(grid!!.incidentCount).replace(',', ' ')} подій за 6 місяців (до ${grid!!.newestIncident}).\n" +
                            (mke?.let { "Мілвокі: ${"%,d".format(it.incidentCount).replace(',', ' ')} подій (до ${it.newestIncident}).\n" } ?: "") +
                            "Інші міста Іллінойсу й Вісконсину до Мілвокі: $townCount (дані ФБР за рік).\n" +
                            "Оновлено ${fmtDate(grid!!.generatedAt)}, далі — саме раз на добу.",
                    )
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
                } else Hint("Готово. Оберіг бачить тільки екрани Uber Driver, Lyft Driver, Waze і Google Maps.")
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = {
                        OberihAccessibilityService.instance?.demoHazard(HazardType.POLICE, 1800.0, Stage.FAR)
                            ?: Toast.makeText(ctx, "Спершу виконай крок 2 (Спеціальні можливості)", Toast.LENGTH_LONG).show()
                    }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E64FF), contentColor = Color.White),
                        modifier = Modifier.weight(1f).height(52.dp)) { Text("🚨 Поліція", fontWeight = FontWeight.Bold) }
                    Button(onClick = {
                        OberihAccessibilityService.instance?.demoHazard(HazardType.CRASH, 700.0, Stage.FAR)
                            ?: Toast.makeText(ctx, "Спершу виконай крок 2 (Спеціальні можливості)", Toast.LENGTH_LONG).show()
                    }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB300), contentColor = Color.Black),
                        modifier = Modifier.weight(1f).height(52.dp)) { Text("⚠ Аварія", fontWeight = FontWeight.Bold) }
                }
                Button(onClick = { ctx.startActivity(Intent(ctx, DemoOfferActivity::class.java)) },
                    modifier = Modifier.fillMaxWidth()) { Text("Повна перевірка: імітація Uber і Waze") }
                Hint("Відкриє екрани, схожі на замовлення Uber і попередження Waze, — Оберіг відреагує на них як на справжні.")
            }
        }
        item {
            SectionCard {
                Text("Waze: поліція й аварії", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Hint("Оберіг бере попередження про поліцію й аварії з екрана Waze (або Google Maps) і повторює їх мигалкою та голосом: одразу, як Waze їх покаже, і ще раз ближче до місця.\n\n" +
                    "Щоб це працювало:\n" +
                    "• У Uber Driver: Меню → Account → App Settings → Navigation → Waze.\n" +
                    "• У Lyft Driver: Меню → Settings → Navigation → Waze.\n" +
                    "• У Waze: Settings → Alerts & reports → Police, Crash, Hazards — увімкнено.\n" +
                    "• Waze має бути відкритий на екрані телефону (з Android Auto в машині Оберіг попереджень не бачить).")
            }
        }
        item { Hint("Оберіг ${BuildConfig.VERSION_NAME} · дані: Chicago Data Portal") }
    }
}

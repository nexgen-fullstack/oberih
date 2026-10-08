package com.uberbro.oberih.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uberbro.oberih.service.OberihAccessibilityService

/**
 * Імітація картки замовлення Uber і попередження Waze — для повної перевірки, що дозволи працюють.
 * Оберіг реагує на них так само, як на справжні (за спеціальними позначками).
 */
class DemoOfferActivity : ComponentActivity() {
    private data class Offer(val name: String, val fare: String, val pickupEta: String, val pickup: String, val trip: String, val dropoff: String)
    private data class Nav(val name: String, val title: String, val distance: String, val note: String)

    private val offers = listOf(
        Offer("Uber: West Loop → Englewood", "$18.42", "6 mins (1.9 mi) away", "1201 W Madison St, Chicago, IL",
            "22 mins (8.4 mi) trip", "6300 S Halsted St, Chicago, IL"),
        Offer("Uber: Loop → Lincoln Park", "$14.10", "4 mins (1.2 mi) away", "233 S Wacker Dr, Chicago, IL",
            "15 mins (4.6 mi) trip", "2430 N Cannon Dr, Chicago, IL"),
        Offer("Uber: Pilsen → Austin", "$11.30", "7 mins (2.1 mi) away", "1800 S Blue Island Ave, Chicago, IL",
            "24 mins (9.2 mi) trip", "5100 W Madison St, Chicago, IL"),
    )
    private val navs = listOf(
        Nav("Waze: поліція 1.2 mi", "Police", "1.2 mi", "Reported 6 min ago"),
        Nav("Waze: поліція 0.2 mi", "Police", "0.2 mi", "Reported 7 min ago"),
        Nav("Waze: аварія 0.6 mi", "Crash", "0.6 mi", "Reported 3 min ago"),
    )

    @OptIn(ExperimentalLayoutApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OberihTheme {
                // 0..2 — замовлення Uber, 3..5 — попередження Waze
                var i by remember { mutableIntStateOf(0) }
                val isNav = i >= offers.size
                Column(
                    Modifier.fillMaxSize().background(Color(0xFF2B3138))
                        .semantics {
                            contentDescription = if (isNav) OberihAccessibilityService.DEMO_NAV_MARKER
                            else OberihAccessibilityService.DEMO_MARKER
                        }
                        .padding(16.dp),
                ) {
                    // Підписи приховані від Оберегу (clearAndSetSemantics), щоб він читав тільки саму «картку».
                    Column(Modifier.clearAndSetSemantics { }) {
                    Text("Повна перевірка", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (OberihAccessibilityService.instance == null) "⚠ Оберіг вимкнений у Спеціальних можливостях — сигналу не буде."
                        else "Обери варіант: через секунду екран блимне і прозвучить підказка. Для поліції спершу «1.2 mi», потім «0.2 mi» — почуєш друге попередження.",
                        color = Color(0xFFCFD8DC), fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 10.dp)) {
                        (offers.map { it.name } + navs.map { it.name }).forEachIndexed { k, name ->
                            OutlinedButton(onClick = { i = k }) { Text(name, fontSize = 12.sp) }
                        }
                    }
                    }
                    Spacer(Modifier.weight(1f))
                    if (!isNav) UberCard(offers[i]) else WazeCard(navs[i - offers.size])
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun UberCard(d: Offer) {
        Column(
            Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(20.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("UberX · Exclusive", color = Color(0xFF3B5BDB), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(d.fare, color = Color.Black, fontSize = 40.sp, fontWeight = FontWeight.Black)
            Text("★ 4.92", color = Color.DarkGray, fontSize = 14.sp)
            Text(d.pickupEta, color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(d.pickup, color = Color.DarkGray, fontSize = 15.sp)
            Text(d.trip, color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(d.dropoff, color = Color.DarkGray, fontSize = 15.sp)
            Spacer(Modifier.height(8.dp))
            Button(onClick = { finish() }, modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White)) {
                Text("Accept", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun WazeCard(n: Nav) {
        Column(
            Modifier.fillMaxWidth().background(Color(0xFF1B2B3A), RoundedCornerShape(20.dp)).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.size(36.dp).background(if (n.title == "Police") Color(0xFF1E64FF) else Color(0xFFFFB300), CircleShape))
                Column(Modifier.padding(start = 12.dp)) {
                    Text(n.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(n.distance, color = Color(0xFF8FD3FF), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Text(n.note, color = Color(0xFFB0BEC5), fontSize = 14.sp)
            OutlinedButton(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("Thanks") }
        }
    }
}

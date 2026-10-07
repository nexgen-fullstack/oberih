package com.uberbro.oberih.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uberbro.oberih.service.OberihAccessibilityService

/**
 * Імітація картки замовлення в стилі Uber — для повної перевірки, що дозволи працюють.
 * Оберіг розпізнає її так само, як справжню (за спеціальною позначкою).
 */
class DemoOfferActivity : ComponentActivity() {
    private data class Demo(val name: String, val fare: String, val pickupEta: String, val pickup: String, val trip: String, val dropoff: String)

    private val demos = listOf(
        Demo("West Loop → Englewood", "$18.42", "6 mins (1.9 mi) away", "1201 W Madison St, Chicago, IL",
            "22 mins (8.4 mi) trip", "6300 S Halsted St, Chicago, IL"),
        Demo("Loop → Lincoln Park", "$14.10", "4 mins (1.2 mi) away", "233 S Wacker Dr, Chicago, IL",
            "15 mins (4.6 mi) trip", "2430 N Cannon Dr, Chicago, IL"),
        Demo("Pilsen → Austin", "$11.30", "7 mins (2.1 mi) away", "1800 S Blue Island Ave, Chicago, IL",
            "24 mins (9.2 mi) trip", "5100 W Madison St, Chicago, IL"),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OberihTheme {
                var i by remember { mutableIntStateOf(0) }
                val d = demos[i]
                Column(
                    Modifier.fillMaxSize().background(Color(0xFF2B3138))
                        .semantics { contentDescription = OberihAccessibilityService.DEMO_MARKER }
                        .padding(16.dp),
                ) {
                    Text("Імітація замовлення", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (OberihAccessibilityService.instance == null) "⚠ Оберіг вимкнений у Спеціальних можливостях — сигналу не буде."
                        else "Через секунду екран має блимнути і прозвучить підказка. Перемикай варіанти кнопками.",
                        color = Color(0xFFCFD8DC), fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 10.dp)) {
                        demos.indices.forEach { k ->
                            OutlinedButton(onClick = { i = k }) { Text("Варіант ${k + 1}", fontSize = 12.sp) }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    // Картка у стилі Uber Driver
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
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Button(onClick = { finish() }, modifier = Modifier.fillMaxWidth().height(56.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White)) {
                                Text("Accept", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Text(d.name, color = Color(0xFF90A4AE), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}

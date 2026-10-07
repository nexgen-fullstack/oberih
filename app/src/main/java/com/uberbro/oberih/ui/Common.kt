package com.uberbro.oberih.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uberbro.oberih.data.Level

val OberihColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF64B5F6),
    onPrimary = Color(0xFF0B1E33),
    secondary = Color(0xFF80CBC4),
    background = Color(0xFF101418),
    surface = Color(0xFF101418),
    surfaceVariant = Color(0xFF1C232B),
    onSurface = Color(0xFFE8EDF2),
    onSurfaceVariant = Color(0xFFB4BEC8),
    error = Color(0xFFEF5350),
)

@Composable
fun OberihTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = OberihColors, content = content)
}

fun Level.color(): Color = Color(argb)
fun Level.onColor(): Color = if (this == Level.YELLOW) Color.Black else Color.White

@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, start = 4.dp))
}

/** Крок налаштування з галочкою. */
@Composable
fun StepRow(done: Boolean, title: String, optional: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(26.dp).clip(CircleShape)
                .background(if (done) Color(0xFF43A047) else if (optional) Color(0xFF546E7A) else Color(0xFFE53935)),
            contentAlignment = Alignment.Center,
        ) { Text(if (done) "✓" else "!", color = Color.White, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(12.dp))
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun LevelDot(level: Level, size: Int = 14) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(level.color())
        .border(2.dp, Color.White.copy(alpha = 0.85f), CircleShape))
}

@Composable
fun Hint(text: String) {
    Text(text, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 19.sp)
}

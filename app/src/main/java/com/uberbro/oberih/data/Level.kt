package com.uberbro.oberih.data

/** Рівень небезпеки зони. [severity] — для порівняння «що гірше». */
enum class Level(val severity: Int, val argb: Long, val word: String, val title: String) {
    UNKNOWN(-1, 0xFF78909C, "НЕМАЄ ДАНИХ", "Немає даних"),
    GREEN(0, 0xFF43A047, "ДОЗВОЛЕНО", "Зелена зона"),
    YELLOW(1, 0xFFFDD835, "НЕ РЕКОМЕНДУЄМО", "Жовта зона"),
    ORANGE(2, 0xFFE65100, "ЗАБОРОНЕНО", "Нічна небезпечна зона"),
    RED(3, 0xFFE53935, "ЗАБОРОНЕНО", "Червона зона");

    val colorInt: Int get() = argb.toInt()

    companion object {
        fun worst(levels: Iterable<Level>): Level =
            levels.filter { it != UNKNOWN }.maxByOrNull { it.severity } ?: UNKNOWN
    }
}

/** Наскільки суворо фарбувати зони: частка районів міста, що стають червоними/жовтими. */
enum class Sensitivity(val label: String, val redPct: Double, val yellowPct: Double) {
    STRICT("Суворо", 0.84, 0.60),
    NORMAL("Звичайно", 0.88, 0.70),
    LENIENT("М'яко", 0.92, 0.78),
}

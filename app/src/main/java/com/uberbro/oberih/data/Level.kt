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

/**
 * Наскільки суворо фарбувати зони: частка кварталів Чикаго/Мілвокі, що стають червоними/жовтими,
 * і пороги для інших міст (насильницьких злочинів на 100 000 жителів за рік; середнє по США ≈ 380).
 */
enum class Sensitivity(val label: String, val redPct: Double, val yellowPct: Double, val townRed: Int, val townYellow: Int) {
    STRICT("Суворо", 0.84, 0.60, 650, 280),
    NORMAL("Звичайно", 0.88, 0.70, 900, 400),
    LENIENT("М'яко", 0.92, 0.78, 1300, 550),
}

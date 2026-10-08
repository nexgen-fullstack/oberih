package com.uberbro.oberih.hazard

import com.uberbro.oberih.offer.ScreenText

/** Що попереду на дорозі. */
enum class HazardType(val title: String) {
    POLICE("ПОЛІЦІЯ"),
    CRASH("АВАРІЯ"),
    CLOSURE("ПЕРЕКРИТТЯ"),
    HAZARD("НЕБЕЗПЕКА"),
}

/** Одне попередження, побачене на екрані навігатора. [meters] — відстань, якщо навігатор її показує. */
data class HazardSighting(val type: HazardType, val meters: Double?)

enum class Stage { FAR, NEAR }

data class HazardAlert(val type: HazardType, val meters: Double?, val stage: Stage)

/**
 * Читає з екрана Waze / Google Maps їхні власні попередження («Police 1.2 mi», «Crash ahead»).
 * Ми НЕ беремо дані з серверів Waze — тільки те, що Waze уже показав водієві на його телефоні.
 */
object HazardParser {
    private val KEYWORDS: List<Pair<HazardType, Regex>> = listOf(
        HazardType.POLICE to Regex(
            "\\bpolice\\b|speed trap|\\bpolicia\\b|поліці|полици|\\bcops?\\b",
            RegexOption.IGNORE_CASE,
        ),
        HazardType.CRASH to Regex("\\bcrash\\b|\\baccident\\b|collision|аварі|дтп|авари", RegexOption.IGNORE_CASE),
        HazardType.CLOSURE to Regex("road closed|lane closed|closure|\\bclosed\\b|перекрит|закрит", RegexOption.IGNORE_CASE),
        HazardType.HAZARD to Regex(
            "\\bhazard\\b|object on road|stopped vehicle|vehicle stopped|car stopped|pothole|construction|" +
                "\\bflood|\\bice\\b|\\bfog\\b|debris|небезпек|ремонт дороги",
            RegexOption.IGNORE_CASE,
        ),
    )
    private val DIST = Regex(
        "(\\d{1,4}(?:[.,]\\d{1,2})?)\\s*(mi|miles?|ft|feet|km|m|м|км)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val AHEAD = Regex("ahead|reported|in \\d|попереду|впереди", RegexOption.IGNORE_CASE)

    /** Після проїзду Waze питає «Still there?» — це вже запізно, такі ігноруємо. */
    private val AFTER_PASS = Regex("still there|not there|is it still|ще там|все ще", RegexOption.IGNORE_CASE)

    /** Максимальна відстань, яку вважаємо попередженням (а не, наприклад, довжиною всього маршруту). */
    private const val MAX_METERS = 5_000.0

    fun metersOf(m: MatchResult): Double? {
        val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        return when (m.groupValues[2].lowercase()) {
            "mi", "mile", "miles" -> v * 1609.34
            "ft", "feet" -> v * 0.3048
            "km", "км" -> v * 1000
            else -> v
        }
    }

    fun parse(items: List<ScreenText>): List<HazardSighting> {
        val texts = items.map { it.text.replace(' ', ' ').trim() }.filter { it.isNotEmpty() && it.length < 160 }
        if (texts.isEmpty()) return emptyList()
        val full = texts.joinToString("\n")
        // Меню «Повідомити» у Waze показує всі типи разом — це не попередження.
        val typesOnScreen = KEYWORDS.count { (_, re) -> re.containsMatchIn(full) }
        if (typesOnScreen >= 3) return emptyList()

        val out = LinkedHashMap<HazardType, HazardSighting>()
        texts.forEachIndexed { i, t ->
            for ((type, re) in KEYWORDS) {
                if (!re.containsMatchIn(t) || type in out) continue
                // Відстань шукаємо в тому ж рядку або в сусідніх (у Waze назва і відстань — окремі написи).
                val window = (maxOf(0, i - 2)..minOf(texts.size - 1, i + 2)).map { texts[it] }
                val near = window.joinToString(" ")
                val meters = window.asSequence()
                    .flatMap { DIST.findAll(it) }
                    .mapNotNull { metersOf(it) }
                    .filter { it <= MAX_METERS }
                    .minOrNull()
                if (meters == null) {
                    if (AFTER_PASS.containsMatchIn(near)) continue
                    if (!AHEAD.containsMatchIn(near)) continue
                }
                out[type] = HazardSighting(type, meters)
            }
        }
        return out.values.toList()
    }

    fun mentionsHazard(full: String): Boolean = KEYWORDS.any { (_, re) -> re.containsMatchIn(full) }
}

/**
 * Вирішує, коли говорити: перше попередження одразу, як Waze його показав,
 * і друге — коли до місця лишилося [nearMeters] або менше.
 */
class HazardTracker(private val nearMeters: () -> Double) {
    private class State(var lastSeen: Long, var lastMeters: Double?, var nearDone: Boolean)

    private val states = HashMap<HazardType, State>()

    fun onSightings(list: List<HazardSighting>, now: Long): List<HazardAlert> {
        val out = ArrayList<HazardAlert>()
        val near = nearMeters()
        for (s in list) {
            val st = states[s.type]
            val isNew = st == null || now - st.lastSeen > FORGET_MS ||
                (s.meters != null && st.lastMeters != null && s.meters > st.lastMeters!! + 400)
            if (isNew) {
                val close = s.meters != null && s.meters <= near
                states[s.type] = State(now, s.meters, nearDone = close)
                out += HazardAlert(s.type, s.meters, if (close) Stage.NEAR else Stage.FAR)
            } else {
                st!!.lastSeen = now
                if (s.meters != null) {
                    if (!st.nearDone && s.meters <= near) {
                        st.nearDone = true
                        out += HazardAlert(s.type, s.meters, Stage.NEAR)
                    }
                    st.lastMeters = s.meters
                }
            }
        }
        return out
    }

    companion object {
        /** Якщо попередження не видно 4 хвилини — наступне таке ж вважаємо новим. */
        const val FORGET_MS = 4 * 60_000L
    }
}

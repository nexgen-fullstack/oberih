package com.uberbro.oberih.offer

import com.uberbro.oberih.data.CommunityAreas

/** Один шматок тексту з екрана: сам текст, верхній край і висота (у пікселях). */
data class ScreenText(val text: String, val top: Int = 0, val height: Int = 0)

data class ParsedOffer(
    val app: String,
    val fare: Double?,
    val pickupMin: Double?,
    val pickupMi: Double?,
    val tripMin: Double?,
    val tripMi: Double?,
    val pickupText: String?,
    val dropoffText: String?,
) {
    /** Однакові замовлення не аналізуємо двічі. */
    val signature: String get() = "$app|$fare|$pickupText|$dropoffText|$tripMi|$tripMin"
}

/**
 * Розпізнає екран нового замовлення Uber Driver / Lyft Driver з тексту, який бачить
 * сервіс спеціальних можливостей. Працює на звичайних правилах (регулярні вирази),
 * тому не залежить від інтернету і спрацьовує за мілісекунди.
 */
object OfferParser {
    private val ACCEPT = Regex(
        "\\b(accept|tap to accept|match|aceptar)\\b|прийняти|принять",
        RegexOption.IGNORE_CASE,
    )
    private val MONEY = Regex("(?<![+\\d])\\$\\s?(\\d{1,3}(?:,\\d{3})*(?:\\.\\d{1,2})?)")
    private val MONEY_LINE = Regex("^\\s*(?:est\\.?\\s*)?\\$\\s?\\d{1,3}(?:,\\d{3})*(?:\\.\\d{1,2})?\\s*(?:est\\.?)?\\s*$", RegexOption.IGNORE_CASE)
    private val NOT_FARE = Regex("included|incl\\.?|boost|surge|bonus|tip|promo|/\\s?hr|/\\s?mi|per\\s|earned|today|this week|balance|cash out", RegexOption.IGNORE_CASE)

    private const val TIME =
        "(?:(\\d{1,2})\\s*(?:h|hr|hrs|hour|hours)\\b\\s*(?:(\\d{1,2})\\s*(?:min|mins|minute|minutes)\\b)?|(\\d{1,3})\\s*(?:min|mins|minute|minutes)\\b)"
    private const val DIST = "(\\d{1,3}(?:\\.\\d{1,2})?)\\s*(?:mi|mile|miles)\\b"
    private val TIME_DIST = Regex("$TIME[^\\d$]{0,14}?$DIST", RegexOption.IGNORE_CASE)
    private val DIST_TIME = Regex("$DIST[^\\d$]{0,14}?$TIME", RegexOption.IGNORE_CASE)
    private val TIME_ONLY = Regex(TIME, RegexOption.IGNORE_CASE)
    private val PICKUP_WORDS = Regex("away|pick\\s?-?up|pickup|to rider|to passenger|to pickup", RegexOption.IGNORE_CASE)
    private val TRIP_WORDS = Regex("\\btrip\\b|\\bride\\b|drop\\s?-?off|destination|to drop", RegexOption.IGNORE_CASE)

    private const val SUFFIX =
        "(?:St|Street|Ave|Av|Avenue|Blvd|Boulevard|Rd|Road|Dr|Drive|Pkwy|Parkway|Ln|Lane|Ct|Court|Pl|Place|Hwy|Highway|Way|Ter|Terrace|Expy|Expressway|Sq|Square|Cir|Circle|Plz|Plaza|Row|Trl|Trail|Walk|Broadway)"
    private val STREET = Regex("\\b$SUFFIX\\b\\.?", RegexOption.IGNORE_CASE)
    private val HOUSE_NUMBER = Regex("^\\d{1,6}[A-Za-z]?\\s+[A-Za-z]")
    private val CITY_MARK = Regex(",\\s*IL\\b|\\b6\\d{4}\\b|\\bChicago\\b", RegexOption.IGNORE_CASE)
    private val PLACE_WORDS = Regex("airport|o'?hare|midway|terminal|station|hospital|hotel|mall|university|college|stadium|casino", RegexOption.IGNORE_CASE)
    private val INTERSECTION = Regex("[A-Za-z]\\s*&\\s*[A-Za-z]")
    private val CITY_ONLY = Regex("^[A-Za-z .'-]{2,30},\\s*IL(?:\\s+\\d{5})?$|^IL\\s+\\d{5}$|^\\d{5}$", RegexOption.IGNORE_CASE)
    private val RATING = Regex("^[★☆]?\\s*\\d\\.\\d{1,2}\\s*[★☆]?$")
    private val UI_WORDS = Regex(
        "^(uberx|uber ?xl|comfort|black|exclusive|shared|priority|lyft|lyft xl|standard|wait & save|verified|new rider|" +
            "decline|reject|accept|pickup|pick up|drop-?off|destination|from|to|trip|ride request|reserve|scheduled)$",
        RegexOption.IGNORE_CASE,
    )
    private val ADDRESS_IN_TEXT = Regex(
        "\\d{1,6}\\s+(?:[NSEW]\\.?\\s+)?[A-Za-z0-9.' -]{2,40}?\\s$SUFFIX\\b\\.?(?:,\\s*[A-Za-z '-]{1,30}[A-Za-z])?(?:,\\s*IL)?(?:\\s+\\d{5})?",
        RegexOption.IGNORE_CASE,
    )

    private data class Leg(val minutes: Double?, val miles: Double?, val label: Int) // 1=подача, 2=поїздка, 0=?

    fun looksLikeOfferCandidate(full: String): Boolean =
        MONEY.containsMatchIn(full) && (ACCEPT.containsMatchIn(full) || TIME_DIST.containsMatchIn(full))

    fun parse(app: String, items: List<ScreenText>, screenHeight: Int): ParsedOffer? {
        val texts = normalize(items)
        if (texts.isEmpty()) return null
        val full = texts.joinToString("\n") { it.text }
        val hasAccept = ACCEPT.containsMatchIn(full)

        val fare = findFare(texts, screenHeight)
        val legs = texts.flatMap { findLegs(it.text) }
        val (pickupLeg, tripLeg) = assignLegs(legs)
        val (pickup, dropoff) = findAddresses(texts, full)

        val addrCount = listOfNotNull(pickup, dropoff).size
        val isOffer = fare != null && (
            (hasAccept && (legs.isNotEmpty() || addrCount >= 1)) ||
                (legs.size >= 2 && addrCount >= 2)
            )
        if (!isOffer) return null
        return ParsedOffer(
            app = app,
            fare = fare,
            pickupMin = pickupLeg?.minutes, pickupMi = pickupLeg?.miles,
            tripMin = tripLeg?.minutes, tripMi = tripLeg?.miles,
            pickupText = pickup, dropoffText = dropoff,
        )
    }

    /** Розбиває багаторядкові тексти, чистить пробіли, прибирає повтори (текст + опис кнопки). */
    private fun normalize(items: List<ScreenText>): List<ScreenText> {
        val seen = HashSet<String>()
        val out = ArrayList<ScreenText>()
        for (it in items) {
            for (part in it.text.split('\n')) {
                val t = part.replace(' ', ' ').replace(' ', ' ').replace(Regex("\\s+"), " ").trim()
                if (t.isEmpty()) continue
                if (seen.add(t.lowercase())) out += ScreenText(t, it.top, it.height)
            }
        }
        return out
    }

    private fun findFare(texts: List<ScreenText>, screenHeight: Int): Double? {
        data class Cand(val value: Double, val standalone: Boolean, val height: Int, val topFrac: Float, val idx: Int)
        val cands = ArrayList<Cand>()
        texts.forEachIndexed { idx, st ->
            if (NOT_FARE.containsMatchIn(st.text)) return@forEachIndexed
            MONEY.findAll(st.text).firstOrNull()?.let { m ->
                val v = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return@let
                if (v < 2.0 || v > 800.0) return@let
                val frac = if (screenHeight > 0) st.top.toFloat() / screenHeight else 0.5f
                cands += Cand(v, MONEY_LINE.matches(st.text), st.height, frac, idx)
            }
        }
        if (cands.isEmpty()) return null
        // Плашка «зароблено сьогодні» зазвичай угорі екрана — відкидаємо її, якщо є інші варіанти.
        val pool = cands.filter { it.topFrac >= 0.12f }.ifEmpty { cands }
        return pool.sortedWith(
            compareByDescending<Cand> { it.standalone }.thenByDescending { it.height }.thenBy { it.idx }
        ).first().value
    }

    private fun minutesOf(m: MatchResult, base: Int): Double? {
        val h = m.groupValues[base].toIntOrNull()
        val hm = m.groupValues[base + 1].toIntOrNull()
        val mm = m.groupValues[base + 2].toIntOrNull()
        return when {
            h != null -> h * 60.0 + (hm ?: 0)
            mm != null -> mm.toDouble()
            else -> null
        }
    }

    private fun labelOf(line: String): Int = when {
        PICKUP_WORDS.containsMatchIn(line) -> 1
        TRIP_WORDS.containsMatchIn(line) -> 2
        else -> 0
    }

    private fun findLegs(line: String): List<Leg> {
        val label = labelOf(line)
        val out = ArrayList<Leg>()
        TIME_DIST.findAll(line).forEach { m ->
            out += Leg(minutesOf(m, 1), m.groupValues[4].toDoubleOrNull(), label)
        }
        if (out.isEmpty()) DIST_TIME.findAll(line).forEach { m ->
            out += Leg(minutesOf(m, 2), m.groupValues[1].toDoubleOrNull(), label)
        }
        if (out.isEmpty() && label != 0 && !line.contains('$')) {
            TIME_ONLY.find(line)?.let { out += Leg(minutesOf(it, 1), null, label) }
        }
        // Якщо в одному рядку дві пари («5 min (1 mi) · 20 min (8 mi)») — підписи за порядком.
        if (out.size == 2 && label == 0) return listOf(out[0].copy(label = 1), out[1].copy(label = 2))
        return out
    }

    private fun assignLegs(legs: List<Leg>): Pair<Leg?, Leg?> {
        var pickup = legs.firstOrNull { it.label == 1 }
        var trip = legs.firstOrNull { it.label == 2 }
        val unlabeled = legs.filter { it.label == 0 }.toMutableList()
        if (pickup == null && trip == null) {
            pickup = unlabeled.getOrNull(0); trip = unlabeled.getOrNull(1)
            if (trip == null && pickup != null) { /* одна пара без підпису — швидше за все це сама поїздка */
                trip = pickup; pickup = null
            }
        } else {
            if (pickup == null) pickup = unlabeled.firstOrNull()
            if (trip == null) trip = unlabeled.firstOrNull { it !== pickup }
        }
        return pickup to trip
    }

    fun isAddressLike(t: String): Boolean {
        if (t.length < 4 || t.length > 140) return false
        if (t.contains('$') || TIME_DIST.containsMatchIn(t) || RATING.matches(t) || UI_WORDS.matches(t)) return false
        if (ACCEPT.containsMatchIn(t) && t.length < 25) return false
        if (TIME_ONLY.containsMatchIn(t) && !STREET.containsMatchIn(t)) return false
        return STREET.containsMatchIn(t) || HOUSE_NUMBER.containsMatchIn(t) || CITY_MARK.containsMatchIn(t) ||
            INTERSECTION.containsMatchIn(t) || PLACE_WORDS.containsMatchIn(t) || CommunityAreas.isKnownPlace(t)
    }

    private fun findAddresses(texts: List<ScreenText>, full: String): Pair<String?, String?> {
        var pickup: String? = null
        var dropoff: String? = null
        var nextSlot = 0 // 1 — наступна адреса це подача, 2 — висадка
        var lastAddrIdx = -10
        var lastSlot = 0
        texts.forEachIndexed { i, st ->
            val t = st.text
            val low = t.lowercase().trimEnd(':')
            when {
                low in setOf("pickup", "pick up", "pick-up", "from") -> { nextSlot = 1; return@forEachIndexed }
                low in setOf("dropoff", "drop-off", "drop off", "destination", "to") -> { nextSlot = 2; return@forEachIndexed }
            }
            if (CITY_ONLY.matches(t) && i == lastAddrIdx + 1) {
                if (lastSlot == 1) pickup = "$pickup, $t" else if (lastSlot == 2) dropoff = "$dropoff, $t"
                lastAddrIdx = i
                return@forEachIndexed
            }
            if (!isAddressLike(t)) return@forEachIndexed
            val slot = when {
                nextSlot == 1 && pickup == null -> 1
                nextSlot == 2 && dropoff == null -> 2
                pickup == null -> 1
                dropoff == null -> 2
                else -> 0
            }
            if (slot == 1) pickup = t else if (slot == 2) dropoff = t
            if (slot != 0) { lastAddrIdx = i; lastSlot = slot; nextSlot = 0 }
        }
        if (pickup == null || dropoff == null) {
            // Запасний варіант: весь текст картки злитий в один рядок — шукаємо адреси всередині.
            val found = ADDRESS_IN_TEXT.findAll(full.replace('\n', ' ')).map { it.value.trim().trimEnd('.', ',', ' ') }.distinct().toList()
            if (pickup == null && dropoff == null) {
                pickup = found.getOrNull(0); dropoff = found.getOrNull(1)
            } else if (dropoff == null) {
                dropoff = found.firstOrNull { it != pickup && !pickup!!.contains(it) }
            } else {
                pickup = found.firstOrNull { it != dropoff && !dropoff!!.contains(it) }
            }
        }
        return pickup to dropoff
    }
}

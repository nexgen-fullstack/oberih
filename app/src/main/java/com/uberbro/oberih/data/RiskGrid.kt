package com.uberbro.oberih.data

import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Місто з детальною картою кварталів. Клітинка = floor(lat·250) × floor(lon·200) ≈ 445 × 415 м
 * (так само групує і сервер міста, тому дані про людність лягають точно в ті самі клітинки).
 */
enum class Region(val title: String, val row0: Int, val col0: Int, val rows: Int, val cols: Int, val file: String) {
    // floor(41.632·250)=10408, floor(-87.950·200)=-17590
    CHICAGO("Чикаго", 10408, -17590, 100, 86, "risk_grid.bin"),
    // floor(42.900·250)=10725, floor(-88.080·200)=-17616
    MILWAUKEE("Мілвокі", 10725, -17616, 76, 47, "risk_grid_mke.bin");

    val cells: Int get() = rows * cols
    fun rowOf(lat: Double): Int = kotlin.math.floor(lat * 250).toInt() - row0
    fun colOf(lon: Double): Int = kotlin.math.floor(lon * 200).toInt() - col0
    fun index(lat: Double, lon: Double): Int {
        val r = rowOf(lat); val c = colOf(lon)
        return if (r in 0 until rows && c in 0 until cols) r * cols + c else -1
    }
}

/**
 * Карта ризику одного міста: для кожної клітинки — «бал небезпеки» за весь день і окремо за ніч
 * (вже згладжений із сусідами, щоб межі зон не були рваними).
 */
class RiskGrid(
    val region: Region,
    val all: FloatArray,
    val night: FloatArray,
    val covered: BooleanArray,
    val areaLat: DoubleArray,
    val areaLon: DoubleArray,
    val generatedAt: Long,
    val newestIncident: String,
    val incidentCount: Int,
) {
    private val thresholdCache = HashMap<Sensitivity, FloatArray>()

    /** Пороги [жовтий, червоний] для денного і нічного балу. */
    private fun thresholds(s: Sensitivity): FloatArray = synchronized(thresholdCache) {
        thresholdCache.getOrPut(s) {
            floatArrayOf(
                percentile(all, s.yellowPct), percentile(all, s.redPct),
                percentile(night, s.yellowPct), percentile(night, s.redPct),
            )
        }
    }

    private fun percentile(values: FloatArray, p: Double): Float {
        val v = values.filterIndexed { i, _ -> covered[i] }.sorted()
        if (v.isEmpty()) return Float.MAX_VALUE
        // Нульові клітинки (парки, порожні квартали) ніколи не мають ставати жовтими.
        return maxOf(v[((v.size - 1) * p).toInt()], 0.01f)
    }

    fun cellIndex(lat: Double, lon: Double): Int = region.index(lat, lon)

    fun covers(lat: Double, lon: Double): Boolean = cellIndex(lat, lon).let { it >= 0 && covered[it] }

    /** Рівень у точці. Вночі жовта зона стає темно-помаранчевою. */
    fun levelAt(lat: Double, lon: Double, isNight: Boolean, s: Sensitivity): Level {
        val i = cellIndex(lat, lon)
        if (i < 0 || !covered[i]) return Level.UNKNOWN
        val t = thresholds(s)
        var level = classify(all[i], t[0], t[1])
        if (isNight) {
            val n = classify(night[i], t[2], t[3])
            if (n.severity > level.severity) level = n
            if (level == Level.YELLOW) level = Level.ORANGE
        }
        return level
    }

    private fun classify(v: Float, yellow: Float, red: Float) = when {
        v >= red -> Level.RED
        v >= yellow -> Level.YELLOW
        else -> Level.GREEN
    }

    /** Назва найближчого району Чикаго (community area), якщо точка в межах міста. */
    fun areaName(lat: Double, lon: Double): String? {
        if (region != Region.CHICAGO) return if (covers(lat, lon)) region.title else null
        var best = -1
        var bestD = Double.MAX_VALUE
        for (a in 1..77) {
            if (areaLat[a] == 0.0) continue
            val d = distanceKm(lat, lon, areaLat[a], areaLon[a])
            if (d < bestD) { bestD = d; best = a }
        }
        return if (best > 0 && bestD < 3.5) CommunityAreas.names[best] else null
    }

    fun areaCenter(area: Int): Pair<Double, Double>? =
        if (area in 1..77 && areaLat[area] != 0.0) areaLat[area] to areaLon[area] else null

    fun write(out: DataOutputStream) {
        out.writeInt(MAGIC); out.writeInt(VERSION); out.writeInt(region.ordinal)
        out.writeLong(generatedAt); out.writeUTF(newestIncident); out.writeInt(incidentCount)
        for (i in 0 until region.cells) { out.writeFloat(all[i]); out.writeFloat(night[i]); out.writeBoolean(covered[i]) }
        for (a in 0..77) { out.writeDouble(areaLat[a]); out.writeDouble(areaLon[a]) }
    }

    companion object {
        // Скорочення для Чикаго (основне місто).
        val ROW0 get() = Region.CHICAGO.row0
        val COL0 get() = Region.CHICAGO.col0
        val ROWS get() = Region.CHICAGO.rows
        val COLS get() = Region.CHICAGO.cols
        fun rowOf(lat: Double) = Region.CHICAGO.rowOf(lat)
        fun colOf(lon: Double) = Region.CHICAGO.colOf(lon)
        fun index(lat: Double, lon: Double) = Region.CHICAGO.index(lat, lon)

        private const val MAGIC = 0x0BE21600
        private const val VERSION = 3

        fun read(inp: DataInputStream): RiskGrid {
            require(inp.readInt() == MAGIC && inp.readInt() == VERSION) { "Старий формат файлу" }
            val region = Region.entries[inp.readInt()]
            val gen = inp.readLong(); val newest = inp.readUTF(); val cnt = inp.readInt()
            val n = region.cells
            val all = FloatArray(n); val night = FloatArray(n); val cov = BooleanArray(n)
            for (i in 0 until n) { all[i] = inp.readFloat(); night[i] = inp.readFloat(); cov[i] = inp.readBoolean() }
            val aLat = DoubleArray(78); val aLon = DoubleArray(78)
            for (a in 0..77) { aLat[a] = inp.readDouble(); aLon[a] = inp.readDouble() }
            return RiskGrid(region, all, night, cov, aLat, aLon, gen, newest, cnt)
        }

        fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dy = (lat2 - lat1) * 111.32
            val dx = (lon2 - lon1) * 111.32 * cos(Math.toRadians((lat1 + lat2) / 2))
            return sqrt(dx * dx + dy * dy)
        }
    }
}

/** Збирає інциденти в сітку. Чистий Kotlin — перевіряється юніт-тестами. */
class RiskGridBuilder(val region: Region = Region.CHICAGO) {
    private val n = region.cells
    private val rawAll = FloatArray(n)
    private val rawNight = FloatArray(n)
    private val hits = IntArray(n)
    private val crowd = FloatArray(n)
    private val aLat = DoubleArray(78)
    private val aLon = DoubleArray(78)
    private val aCnt = IntArray(78)
    var count = 0
        private set
    var newest = ""
        private set

    fun add(lat: Double, lon: Double, weight: Float, atNight: Boolean, area: Int, date: String) {
        if (area in 1..77) { aLat[area] += lat; aLon[area] += lon; aCnt[area]++ }
        val i = region.index(lat, lon)
        if (i < 0) return
        rawAll[i] += weight
        if (atNight) rawNight[i] += weight
        hits[i]++
        count++
        if (date > newest) newest = date
    }

    /**
     * Скільки в клітинці крадіжок — це мірило того, скільки там людей (центр, бари, вокзали).
     * Без нього людні безпечні райони (Loop, River North) виглядали б «червоними» просто через натовп.
     */
    fun addCrowd(row: Int, col: Int, count: Int) {
        if (row in 0 until region.rows && col in 0 until region.cols) {
            val i = row * region.cols + col
            crowd[i] += count.toFloat()
            hits[i] += count
        }
    }

    fun build(generatedAt: Long): RiskGrid {
        val rows = region.rows; val cols = region.cols
        val covered = BooleanArray(n)
        for (r in 0 until rows) for (c in 0 until cols) {
            var any = false
            loop@ for (dr in -2..2) for (dc in -2..2) {
                val rr = r + dr; val cc = c + dc
                if (rr in 0 until rows && cc in 0 until cols && hits[rr * cols + cc] > 0) {
                    any = true; break@loop
                }
            }
            covered[r * cols + c] = any
        }
        for (a in 1..77) if (aCnt[a] > 0) { aLat[a] /= aCnt[a]; aLon[a] /= aCnt[a] }
        // Небезпека на одну людину, а не загальна кількість: бал ÷ (1 + людність/50). Підібрано на даних Чикаго.
        val people = smooth(crowd)
        val all = smooth(rawAll); val night = smooth(rawNight)
        for (i in 0 until n) {
            val k = 1f + people[i] / CROWD_SCALE
            all[i] /= k; night[i] /= k
        }
        return RiskGrid(region, all, night, covered, aLat, aLon, generatedAt, newest, count)
    }

    private companion object { const val CROWD_SCALE = 50f }

    /** Згладжування: клітинка + половина сусідів по сторонах + третина по діагоналях. */
    private fun smooth(src: FloatArray): FloatArray {
        val rows = region.rows; val cols = region.cols
        val out = FloatArray(n)
        for (r in 0 until rows) for (c in 0 until cols) {
            var s = 0f
            for (dr in -1..1) for (dc in -1..1) {
                val rr = r + dr; val cc = c + dc
                if (rr !in 0 until rows || cc !in 0 until cols) continue
                val w = when {
                    dr == 0 && dc == 0 -> 1f
                    dr == 0 || dc == 0 -> 0.5f
                    else -> 0.33f
                }
                s += src[rr * cols + cc] * w
            }
            out[r * cols + c] = s
        }
        return out
    }
}

/**
 * Наскільки кожен тип злочину важливий саме для водія таксі.
 * Головне — стрілянина, вбивства, викрадення авто з водієм (carjacking) і збройні пограбування.
 * Дрібні бійки й сварки майже не враховуються: їх багато в людних, але безпечних місцях.
 */
object CrimeWeights {
    val TYPES = listOf(
        "HOMICIDE", "ROBBERY", "WEAPONS VIOLATION", "NARCOTICS", "KIDNAPPING", "CRIMINAL SEXUAL ASSAULT",
    )

    /** Умова для сервера: потрібні типи + тільки «тяжкі» (AGG…) бійки й напади. */
    val WHERE_TYPES: String =
        "(primary_type in(${TYPES.joinToString(",") { "'$it'" }}) OR " +
            "(primary_type in('BATTERY','ASSAULT') AND description like 'AGG%'))"

    /** Коди NIBRS (Мілвокі), які беремо: вбивство, пограбування, тяжкий напад, зброя, наркотики, зґвалтування, викрадення. */
    val NIBRS_CODES = listOf("09A", "120", "13A", "520", "35A", "11A", "100")

    fun weight(type: String, description: String, domestic: Boolean, ageDays: Long): Float {
        val d = description.uppercase()
        val gun = "HANDGUN" in d || "FIREARM" in d || "GUN" in d
        // Бійки з поліцейськими/охороною без зброї — не загроза для водія.
        if (!gun && ("P.O." in d || "PROTECTED EMPLOYEE" in d || "PRO.EMP" in d)) return 0f
        var w = when (type) {
            "HOMICIDE" -> 15f
            "ROBBERY" -> when {
                "HIJACKING" in d -> 14f // викрадення авто з водієм — головна загроза для таксиста
                "ARMED" in d && gun -> 2.5f
                "ARMED" in d -> 1f
                else -> 0.3f
            }
            "WEAPONS VIOLATION" -> 3f
            "BATTERY" -> if ("AGG" in d) (if (gun) 8f else 0.8f) else 0f // AGG + HANDGUN = стрілянина з пораненням
            "ASSAULT" -> if ("AGG" in d) (if (gun) 3f else 0.3f) else 0f
            "NARCOTICS" -> 1f // відкриті «точки» продажу наркотиків
            "KIDNAPPING" -> 1.5f
            "CRIMINAL SEXUAL ASSAULT" -> 0.5f
            else -> 0f
        }
        // Домашні конфлікти рідко загрожують людині на вулиці.
        if (domestic) w *= 0.35f
        return w * recency(ageDays)
    }

    /** Те саме для Мілвокі, де злочини записані кодами NIBRS (напр. «120;240», зброя «HANDGUN»). */
    fun nibrsWeight(codes: String, weapon: String?, ageDays: Long): Float {
        val set = codes.split(';', ',').map { it.trim() }.toSet()
        val wpn = (weapon ?: "").uppercase()
        val gun = "GUN" in wpn || "FIREARM" in wpn || "RIFLE" in wpn
        var w = 0f
        for (c in set) {
            val x = when (c) {
                "09A" -> 15f
                "120" -> when {
                    "240" in set -> 14f // пограбування + викрадення авто = carjacking
                    gun -> 2.5f
                    else -> 0.5f
                }
                "13A" -> if (gun) 5f else 0.6f
                "520" -> 3f
                "35A" -> 1f
                "100" -> 1.5f
                "11A" -> 0.5f
                else -> 0f
            }
            if (x > w) w = x
        }
        return w * recency(ageDays)
    }

    private fun recency(ageDays: Long) = when {
        ageDays <= 30 -> 1.3f
        ageDays <= 90 -> 1.0f
        else -> 0.7f
    }

    /** Ніч для статистики: 20:00–05:59. Час рівно 00:00:00 часто означає «невідомо» — не рахуємо. */
    fun isNightTime(isoDate: String): Boolean {
        if (isoDate.length < 19) return false
        val hh = isoDate.substring(11, 13).toIntOrNull() ?: return false
        if (isoDate.substring(11, 19) == "00:00:00") return false
        return hh >= 20 || hh < 6
    }
}

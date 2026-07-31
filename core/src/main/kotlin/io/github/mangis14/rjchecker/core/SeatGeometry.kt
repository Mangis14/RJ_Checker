package io.github.mangis14.rjchecker.core

import kotlin.math.abs

/**
 * Topologia vozna odvodena z SVG layoutu.
 *
 * API neposiela o miestach ziadne suradnice ani topologiu - `Seat` ma len
 * `index`, `seatClass`, `seatConstraint` a `seatNotes`. "Vedla" a "oproti" sa
 * preto daju ziskat jedine z obrazku layoutu vozna.
 */
class CoachLayout internal constructor(
    internal val positions: Map<Int, Pair<Double, Double>>,
    val columns: List<Double>,
    val rows: List<Double>,
    val aisleAfterColumn: Int?,
    val baySource: BaySource,
    internal val seatBay: Map<Int, List<Int>>,
    internal val seatRow: Map<Int, Int>,
    internal val seatColumn: Map<Int, Int>,
    internal val lengthAxisIsY: Boolean,
) {
    fun contains(seat: Int): Boolean = seat in positions

    private fun cross(seat: Int): Double =
        positions.getValue(seat).let { if (lengthAxisIsY) it.first else it.second }

    /** 0 / 1 podla strany ulicky; null ak vozen ulicku medzi miestami nema. */
    fun sideOf(seat: Int): Int? {
        val aisle = aisleAfterColumn ?: return null
        return if (seatColumn.getValue(seat) <= aisle) 0 else 1
    }

    /**
     * Miesta na tej istej lavici / v tom istom rade.
     *
     * V kupe je hranicou oddiel, nie ulicka - kupe ziadnu ulicku vnutri nema,
     * chodbicka vedie vedla neho. Pri radovom sedeni oddeluje dvojice ulicka.
     */
    fun nextTo(seat: Int): List<Int> {
        if (seat !in positions) return emptyList()
        val row = seatRow.getValue(seat)
        val members = seatBay[seat]
        val pool = if (members != null) {
            members.filter { it != seat && seatRow[it] == row }
        } else {
            val side = sideOf(seat)
            positions.keys.filter {
                it != seat && seatRow[it] == row && (side == null || sideOf(it) == side)
            }
        }
        return pool.sortedBy { abs(cross(it) - cross(seat)) }
    }

    /** Miesto priamo oproti; null znamena "neexistuje alebo nezname". */
    fun facing(seat: Int): Int? {
        val members = seatBay[seat] ?: return null
        val row = seatRow.getValue(seat)
        return members.filter { seatRow[it] != row }.minByOrNull { abs(cross(it) - cross(seat)) }
    }

    /** Cely oddiel vratane seba; pri radovom sedeni len dvojica. */
    fun bay(seat: Int): List<Int> =
        seatBay[seat] ?: (listOf(seat) + nextTo(seat)).sorted()

    fun neighbours(seat: Int): Neighbours =
        neighboursOrNull(seat) ?: error("miesto $seat nie je vo vozni")

    fun neighboursOrNull(seat: Int): Neighbours? {
        if (seat !in positions) return null
        return Neighbours(seat = seat, bay = bay(seat), nextTo = nextTo(seat), facing = facing(seat))
    }

    /**
     * Radove sedenie bez najdenej ulicky znamena, ze layout sa neda precitat
     * spolahlivo. Vtedy je lepsie priznat viac kandidatov ako tvrdit jedno
     * nespravne miesto.
     */
    fun confidence(seat: Int): Confidence = when {
        seatBay[seat] != null -> Confidence.CERTAIN
        aisleAfterColumn == null && nextTo(seat).size > 1 -> Confidence.UNCERTAIN
        else -> Confidence.CERTAIN
    }
}

object SeatGeometry {

    private const val MIN_OVERLAP = 0.80
    private const val JITTER_FRACTION = 0.25
    private const val ALTERNATION = 0.80
    private const val AISLE_RATIO = 1.4

    private val elementRegex = Regex("""<([a-zA-Z][\w:-]*)((?:"[^"]*"|'[^']*'|[^>"'])*)>""")

    // Illustrator pri duplicitnych id pridava priponu _N_ (napr. a12_1_)
    private val idRegex = Regex("""\bid="([A-Za-z_-]*?)(\d+)(?:_\d+_)?"""")

    private val cxRegex = Regex("""\bcx="(-?[\d.]+)"""")
    private val cyRegex = Regex("""\bcy="(-?[\d.]+)"""")
    private val matrixRegex = Regex(
        """\btransform="matrix\(\s*[-\d.eE]+\s+[-\d.eE]+\s+[-\d.eE]+\s+""" +
            """[-\d.eE]+\s+(-?[\d.eE]+)\s+(-?[\d.eE]+)\s*\)"""",
    )
    private val translateRegex = Regex("""\btransform="translate\(\s*(-?[\d.eE]+)[,\s]+(-?[\d.eE]+)""")
    private val pathRegex = Regex("""\bd="[Mm]\s*(-?[\d.]+)[,\s]+(-?[\d.]+)""")
    private val xRegex = Regex("""\bx="(-?[\d.]+)"""")
    private val yRegex = Regex("""\by="(-?[\d.]+)"""")

    /** Poloha prvku - layouty RegioJetu pouzivaju viacero konvencii. */
    private fun elementXy(attrs: String): Pair<Double, Double>? {
        val cx = cxRegex.find(attrs)
        val cy = cyRegex.find(attrs)
        if (cx != null && cy != null) {
            return cx.groupValues[1].toDouble() to cy.groupValues[1].toDouble()
        }
        matrixRegex.find(attrs)?.let {
            return it.groupValues[1].toDouble() to it.groupValues[2].toDouble()
        }
        translateRegex.find(attrs)?.let {
            return it.groupValues[1].toDouble() to it.groupValues[2].toDouble()
        }
        pathRegex.find(attrs)?.let {
            return it.groupValues[1].toDouble() to it.groupValues[2].toDouble()
        }
        val x = xRegex.find(attrs)
        val y = yRegex.find(attrs)
        if (x != null && y != null) {
            return x.groupValues[1].toDouble() to y.groupValues[1].toDouble()
        }
        return null
    }

    /**
     * Cislo miesta -> pozicia. Layouty pouzivaju rozne konvencie id ("a32"
     * z Figmy, "c32"/"n32"/"s32" z Illustratora), preto sa vyberie ta skupina
     * prefixov, ktora najlepsie pokryva miesta hlasene API. Presna zhoda sa
     * nevyzaduje - niektore layouty su o verziu starsie ako realne cislovanie.
     */
    private fun seatPositions(svg: String, apiSeats: Set<Int>): Map<Int, Pair<Double, Double>> {
        val groups = HashMap<String, MutableMap<Int, Pair<Double, Double>>>()
        for (match in elementRegex.findAll(svg)) {
            val attrs = match.groupValues[2]
            val id = idRegex.find(attrs) ?: continue
            val xy = elementXy(attrs) ?: continue
            groups.getOrPut(id.groupValues[1]) { LinkedHashMap() }
                .putIfAbsent(id.groupValues[2].toInt(), xy)
        }
        var best: Map<Int, Pair<Double, Double>> = emptyMap()
        var bestScore = 0.0
        for (candidate in groups.values) {
            val score = candidate.keys.count { it in apiSeats }.toDouble() / apiSeats.size
            val fewerExtras = candidate.keys.count { it !in apiSeats } < best.keys.count { it !in apiSeats }
            if (score > bestScore || (score == bestScore && score > 0 && fewerExtras)) {
                best = candidate
                bestScore = score
            }
        }
        if (bestScore < MIN_OVERLAP) return emptyMap()
        return best.filterKeys { it in apiSeats }
    }

    /**
     * Zoskupi pozicie do radov / stlpcov. Zlucuju sa VYLUCNE zakmity - to iste
     * sedadlo je casto zakreslene viacerymi prvkami s odchylkou desatin bodu,
     * kym skutocny rozostup je desiatky bodov. Mierka sa odvodi od najvacsich
     * medzier, takze nezavisi na mierke konkretneho layoutu.
     */
    private fun autoCluster(values: List<Double>): List<Double> {
        val vals = values.map { Math.round(it * 100.0) / 100.0 }.distinct().sorted()
        if (vals.size <= 1) return vals
        val gaps = vals.zipWithNext { a, b -> b - a }
        val top = gaps.sorted().takeLast(maxOf(1, gaps.size / 4))
        val tol = JITTER_FRACTION * top.average()

        val groups = mutableListOf(mutableListOf(vals.first()))
        for (i in 1 until vals.size) {
            if (gaps[i - 1] > tol) groups.add(mutableListOf())
            groups.last().add(vals[i])
        }
        return groups.map { it.average() }
    }

    private fun nearest(value: Double, centers: List<Double>): Int =
        centers.indices.minByOrNull { abs(centers[it] - value) } ?: 0

    /**
     * Oddiely podla cislovania miest. RegioJet cisluje kupe po desiatkach
     * (1-6, 11-16, 21-26 ...), takze diery v cislach padnu presne tam, kde kupe
     * konci. Je to nezavisly a spolahlivejsi signal ako geometria.
     */
    private fun numberingBays(seats: List<Int>, rowOf: Map<Int, Int>): List<List<Int>>? {
        val groups = seats.groupBy { it / 10 }
        val sizes = groups.values.map { it.size }.distinct()
        if (sizes.size != 1) return null
        val size = sizes.first()
        if (size < 4 || size % 2 != 0) return null
        // kazdy oddiel musi lezat presne v dvoch radoch - dve lavice k sebe
        if (groups.values.any { g -> g.mapNotNull { rowOf[it] }.distinct().size != 2 }) return null
        return groups.toSortedMap().values.map { it.sorted() }
    }

    /**
     * Spari rady otocene k sebe, ked sa odstupy radov pravidelne STRIEDAJU.
     *
     * Samotna bimodalita by nestacila - velkopriestorovy vozen ma tiez jednu
     * vycnievajucu medzeru (predstavok v strede), a ta z neho oddielovy vozen
     * nerobi. Ktora skupina odstupov je "vnutri oddielu" sa nehada: miesta
     * otocene k sebe potrebuju priestor na nohy pre dvoch, kym dve lavice
     * chrbtami k sebe oddeluje len tenka opierka, takze vnutri oddielu je
     * odstup VACSI.
     */
    private fun alternatingBays(rows: List<Double>): List<List<Int>>? {
        if (rows.size < 4) return null
        val gaps = rows.zipWithNext { a, b -> b - a }
        val even = gaps.filterIndexed { i, _ -> i % 2 == 0 }
        val odd = gaps.filterIndexed { i, _ -> i % 2 == 1 }
        if (even.isEmpty() || odd.isEmpty()) return null
        val meanEven = even.average()
        val meanOdd = odd.average()
        val ratio = maxOf(meanEven, meanOdd) / maxOf(minOf(meanEven, meanOdd), 1e-6)
        if (ratio < 1.10) return null

        val boundary = (maxOf(meanEven, meanOdd) + minOf(meanEven, meanOdd)) / 2.0
        val big = if (meanEven > meanOdd) even else odd
        val small = if (meanEven > meanOdd) odd else even
        val consistent = (big.count { it > boundary } + small.count { it <= boundary }).toDouble() / gaps.size
        if (consistent < ALTERNATION) return null

        val phase = if (meanEven > meanOdd) 0 else 1     // faza VACSICH odstupov
        val bays = mutableListOf<List<Int>>()
        var i = 0
        while (i < rows.size) {
            if (i < gaps.size && i % 2 == phase) {
                bays.add(listOf(i, i + 1))
                i += 2
            } else {
                bays.add(listOf(i))
                i += 1
            }
        }
        return bays
    }

    fun parse(svg: String, apiSeats: List<Int>): CoachLayout? {
        val wanted = apiSeats.toSet()
        if (wanted.isEmpty()) return null
        val positions = seatPositions(svg, wanted)
        if (positions.isEmpty()) return null

        val xs = positions.values.map { it.first }
        val ys = positions.values.map { it.second }
        val lengthAxisIsY = (ys.max() - ys.min()) >= (xs.max() - xs.min())

        fun lengthOf(p: Pair<Double, Double>) = if (lengthAxisIsY) p.second else p.first
        fun crossOf(p: Pair<Double, Double>) = if (lengthAxisIsY) p.first else p.second

        val columns = autoCluster(positions.values.map { crossOf(it) })
        val rows = autoCluster(positions.values.map { lengthOf(it) })

        // Ulicka = vyrazne najvacsia medzera medzi stlpcami. Kupejovy vozen
        // ulicku medzi miestami nema, preto sa hlada az od 4 stlpcov.
        var aisleAfter: Int? = null
        if (columns.size >= 4) {
            val gaps = columns.zipWithNext { a, b -> b - a }
            val maxGap = gaps.max()
            if (maxGap > AISLE_RATIO * gaps.average()) aisleAfter = gaps.indexOf(maxGap)
        }

        val seatRow = positions.mapValues { (_, p) -> nearest(lengthOf(p), rows) }
        val seatColumn = positions.mapValues { (_, p) -> nearest(crossOf(p), columns) }

        val seatBay = HashMap<Int, List<Int>>()
        var source = BaySource.NONE
        val byNumbering = numberingBays(positions.keys.sorted(), seatRow)
        if (byNumbering != null) {
            byNumbering.forEach { group -> group.forEach { seatBay[it] = group } }
            source = BaySource.SEAT_NUMBERING
        } else {
            alternatingBays(rows)?.let { rowBays ->
                for (bayRows in rowBays) {
                    if (bayRows.size < 2) continue
                    val members = positions.keys.filter { seatRow[it] in bayRows }.sorted()
                    members.forEach { seatBay[it] = members }
                }
                if (seatBay.isNotEmpty()) source = BaySource.ROW_GEOMETRY
            }
        }

        return CoachLayout(
            positions = positions,
            columns = columns,
            rows = rows,
            aisleAfterColumn = aisleAfter,
            baySource = source,
            seatBay = seatBay,
            seatRow = seatRow,
            seatColumn = seatColumn,
            lengthAxisIsY = lengthAxisIsY,
        )
    }
}

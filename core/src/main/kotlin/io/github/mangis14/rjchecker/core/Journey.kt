package io.github.mangis14.rjchecker.core

/** Jedna zastavka s obsadenostou pre usek <zastavka> -> ciel. */
data class JourneyStop(
    val stationId: Long,
    val stationName: String,
    val departure: String,
    val order: Int,
    val section: FreeSeatsSection,
)

data class NeighbourInfo(
    val seat: Int,
    val relation: String,
    val freeWholeWay: Boolean,
    val freesAt: JourneyStop?,
    val flags: Set<SeatFlag> = emptySet(),
)

data class SeatAnalysis(
    val coach: Int,
    val coachName: String,
    val seat: Int,
    val bay: List<Int>,
    val baySource: BaySource,
    val confidence: Confidence,
    val neighbours: List<NeighbourInfo>,
    val freeInCoach: Int,
    val coachTotal: Int,
)

data class SeatPick(
    val coach: Int,
    val coachName: String,
    val seat: Int,
    val bay: List<Int>,
    val score: Double,
    val freeNeighbours: List<Int>,
    val takenNeighbours: List<Int>,
    val emptyFrom: JourneyStop?,
    val flags: Set<SeatFlag>,
    val isCompartment: Boolean,
)

/**
 * Cesta = zastavky v geografickom poradi, kazda s obsadenostou pre usek do ciela.
 *
 * Kluc k spravnemu citaniu dat: obsadenost plati pre USEK. Miesto volne pre usek
 * <vychodzia> -> ciel je volne po celu cestu, kratsi usek ho uz obsadit nemoze.
 * Preto sa "pokoj" pocita zo stavu na zaciatku a prechod zastavkami odpoveda na
 * opacnu otazku: kde sa OBSADENE miesto uvolni.
 */
class Journey(val stops: List<JourneyStop>) {

    /** Layouty voznov dodava volajuci - core modul sam po sieti nesiaha. */
    var layoutProvider: (Deck) -> CoachLayout? = { null }

    private val layoutCache = HashMap<String, CoachLayout?>()

    private fun layoutOf(deck: Deck): CoachLayout? =
        layoutCache.getOrPut(deck.layoutUrl ?: deck.name) { layoutProvider(deck) }

    private fun firstStop(): JourneyStop? = stops.minByOrNull { it.order }

    /** Prva zastavka, od ktorej je miesto volne az do ciela; null ak nikdy. */
    override fun toString(): String = "Journey(${stops.size} zastavok)"

    fun freesAt(coach: Int, seat: Int): JourneyStop? =
        stops.sortedBy { it.order }
            .firstOrNull { it.section.vehicle(coach)?.decks?.firstOrNull()?.seat(seat)?.free == true }

    fun analyseSeat(coach: Int, seat: Int): SeatAnalysis? {
        val start = firstStop() ?: return null
        val vehicle = start.section.vehicle(coach) ?: return null
        val deck = vehicle.decks.firstOrNull() ?: return null
        if (deck.seat(seat) == null) return null

        val layout = layoutOf(deck)
        val n = layout?.neighboursOrNull(seat)
        val bay = n?.bay ?: listOf(seat)
        val ordered = n?.ordered ?: emptyList()

        val neighbours = ordered.map { other ->
            val relation = when {
                n != null && other in n.nextTo -> "vedla"
                n != null && other == n.facing -> "oproti"
                else -> "v oddiele"
            }
            val freeNow = deck.seat(other)?.free == true
            NeighbourInfo(
                seat = other,
                relation = relation,
                freeWholeWay = freeNow,
                freesAt = if (freeNow) null else freesAt(coach, other),
                flags = deck.seat(other)?.flags ?: emptySet(),
            )
        }

        return SeatAnalysis(
            coach = coach,
            coachName = deck.name,
            seat = seat,
            bay = bay,
            baySource = layout?.baySource ?: BaySource.NONE,
            confidence = layout?.confidence(seat) ?: Confidence.UNCERTAIN,
            neighbours = neighbours,
            freeInCoach = deck.seats.count { it.free },
            coachTotal = deck.seats.size,
        )
    }

    /**
     * Navrhne pokojnejsie miesta. Skore stavia na tom, co je overitelne:
     * prazdny sused a prazdny oddiel po celej ceste, bonus za tiche kupe, malus
     * za detske. Kazdy oddiel je v zozname len raz a z jedneho vozna najviac
     * dva navrhy - osem takmer rovnakych miest z jedneho vozna vyzera ako vyber,
     * ale ziadny nie je.
     */
    fun recommend(seatClass: String?, limit: Int = 6): List<SeatPick> {
        val start = firstStop() ?: return emptyList()
        val picks = mutableListOf<SeatPick>()
        val seenBays = HashSet<Pair<Int, Set<Int>>>()

        for (vehicle in start.section.vehicles) {
            if (seatClass != null && seatClass !in vehicle.seatClasses) continue
            val deck = vehicle.decks.firstOrNull() ?: continue
            val layout = layoutOf(deck) ?: continue

            for (seat in deck.seats.filter { it.free }.map { it.index }.sorted()) {
                val n = layout.neighboursOrNull(seat) ?: continue
                val key = vehicle.number to n.bay.toSet()
                if (!seenBays.add(key)) continue

                val others = n.bay.filter { it != seat }
                val free = others.filter { deck.seat(it)?.free == true }
                val taken = others.filter { deck.seat(it)?.free != true }
                val flags = deck.seat(seat)?.flags ?: emptySet()

                // kedy je oddiel konecne cely prazdny; null = niekto tam zostane
                var emptyFrom: JourneyStop? = null
                var unresolved = false
                for (t in taken) {
                    val at = freesAt(vehicle.number, t)
                    if (at == null) {
                        unresolved = true
                        break
                    }
                    if (emptyFrom == null || at.order > emptyFrom.order) emptyFrom = at
                }
                if (unresolved) emptyFrom = null

                var score = 0.0
                if (others.isNotEmpty()) {
                    score += 4.0 * free.size / others.size
                    if (taken.isEmpty()) {
                        score += 1.5 + 0.4 * others.size    // prazdny oddiel celu cestu
                    } else if (emptyFrom != null) {
                        score += 1.0                        // aspon sa vyprazdni po ceste
                    }
                }
                if (SeatFlag.QUIET in flags) score += 2.5
                if (SeatFlag.CHILDREN in flags) score -= 3.0

                picks.add(
                    SeatPick(
                        coach = vehicle.number,
                        coachName = deck.name,
                        seat = seat,
                        bay = n.bay,
                        score = score,
                        freeNeighbours = free,
                        takenNeighbours = taken,
                        emptyFrom = emptyFrom,
                        flags = flags,
                        isCompartment = layout.seatBay[seat] != null,
                    ),
                )
            }
        }

        val perCoach = HashMap<Int, Int>()
        val result = mutableListOf<SeatPick>()
        for (pick in picks.sortedWith(compareByDescending<SeatPick> { it.score }
                .thenBy { it.coach }.thenBy { it.seat })) {
            if ((perCoach[pick.coach] ?: 0) >= 2) continue
            perCoach[pick.coach] = (perCoach[pick.coach] ?: 0) + 1
            result.add(pick)
            if (result.size >= limit) break
        }
        return result
    }
}

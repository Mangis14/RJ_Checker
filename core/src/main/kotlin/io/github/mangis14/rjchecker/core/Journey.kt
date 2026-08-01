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
    /** samostatne / dvojica / stvorica so stolikom / kupe */
    val kind: SeatKind = SeatKind.UNKNOWN,
    /** UNCERTAIN = layout vozna sa necita spolahlivo, typ neuvadzat */
    val confidence: Confidence = Confidence.CERTAIN,
)

/**
 * Zhrnutie pohodlia pre cely vlak - pouziva sa uz pri vybere spoja.
 *
 * Vystaci s obsadenostou jedneho useku, teda s jednym volanim API, takze sa da
 * ukazat hned v zozname spojov bez cakania na prechod zastavkami.
 */
data class ComfortSummary(
    val freeSeats: Int,
    /** kupe, v ktorych je volne kazde miesto */
    val emptyCompartments: Int,
    /** dvojice vo velkopriestorovom vozni, kde su volne obe miesta */
    val emptyPairs: Int,
    val best: SeatPick?,
    /** volne miesta po triedach (Relax, Low cost, Standard, Business) */
    val byClass: List<ClassAvailability> = emptyList(),
)

/**
 * Volne miesta jednej triedy, rozpadnute podla typu sedadla.
 *
 * Zobrazuje sa pri vybere spoja: "Relax 12 volnych (4 samostatne, 8 dvojice)"
 * povie viac ako samotne cislo.
 */
data class ClassAvailability(
    val seatClass: String,
    val freeSeats: Int,
    val byKind: Map<SeatKind, Int>,
) {
    /** Vypredana trieda - prave tu ma zmysel sledovat. */
    val soldOut: Boolean get() = freeSeats == 0
}

/**
 * Volne miesto danej triedy.
 *
 * @param comfortable je volne aj miesto vedla. Neslúzi na filtrovanie
 *   upozorneni (pri vypredanej triede clovek chce vediet o kazdom mieste), ale
 *   na to, aby sa v texte dalo rozlisit, ktore z uvolnenych stoji za presun.
 */
data class FreeClassSeat(val coach: Int, val seat: Int, val comfortable: Boolean)

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

    /**
     * Volne miesta rozpadnute na triedy a typy sedadiel.
     *
     * Trieda ide z API (vehicle.seatClasses), typ sedadla z topologie vozna.
     * Kde sa layout nepodari precitat, miesta sa do sumy triedy zapocitaju,
     * ale bez typu - radsej chybajuci detail ako vymysleny.
     */
    fun availabilityByClass(): List<ClassAvailability> {
        val start = firstStop() ?: return emptyList()
        val free = HashMap<String, Int>()
        val kinds = HashMap<String, MutableMap<SeatKind, Int>>()

        // Vsetky triedy vo vlaku sa zavedu s nulou, aj ked su vypredane.
        // Prave vypredanu triedu ma zmysel sledovat - tu, kde je volno, si
        // clovek jednoducho kupi.
        for (vehicle in start.section.vehicles) {
            vehicle.seatClasses.forEach { free.putIfAbsent(it, 0) }
        }

        for (vehicle in start.section.vehicles) {
            val deck = vehicle.decks.firstOrNull() ?: continue
            // vozen moze hlasit viac tried; miesto sa priradi tej svojej, inak prvej
            val fallback = vehicle.seatClasses.firstOrNull() ?: continue
            val layout = layoutOf(deck)
            for (seat in deck.seats.filter { it.free }) {
                val cls = seat.seatClass?.takeIf { it in vehicle.seatClasses } ?: fallback
                free[cls] = (free[cls] ?: 0) + 1
                val kind = layout?.seatKind(seat.index) ?: SeatKind.UNKNOWN
                if (kind != SeatKind.UNKNOWN) {
                    val perKind = kinds.getOrPut(cls) { HashMap() }
                    perKind[kind] = (perKind[kind] ?: 0) + 1
                }
            }
        }
        return free.map { (cls, count) ->
            ClassAvailability(cls, count, kinds[cls]?.toMap() ?: emptyMap())
        }.sortedByDescending { it.freeSeats }
    }

    /**
     * Kolko pokoja vlak nabizi. Ratane zo stavu na zaciatku, takze staci jedno
     * volanie API - miesto volne pre usek <vychodzia> -> ciel je volne po celu
     * cestu.
     */
    fun comfortSummary(seatClass: String?): ComfortSummary {
        val start = firstStop() ?: return ComfortSummary(0, 0, 0, null)
        var free = 0
        var compartments = 0
        var pairs = 0
        val seen = HashSet<Pair<Int, Set<Int>>>()

        for (vehicle in start.section.vehicles) {
            if (seatClass != null && seatClass !in vehicle.seatClasses) continue
            val deck = vehicle.decks.firstOrNull() ?: continue
            free += deck.seats.count { it.free }
            val layout = layoutOf(deck) ?: continue
            for (seat in deck.seats.filter { it.free }.map { it.index }) {
                val n = layout.neighboursOrNull(seat) ?: continue
                if (!seen.add(vehicle.number to n.bay.toSet())) continue
                if (n.bay.all { deck.seat(it)?.free == true }) {
                    if (layout.seatBay[seat] != null) compartments++ else pairs++
                }
            }
        }
        return ComfortSummary(
            freeSeats = free,
            emptyCompartments = compartments,
            emptyPairs = pairs,
            best = recommend(seatClass, limit = 1).firstOrNull(),
            byClass = availabilityByClass(),
        )
    }

    private val layoutCache = HashMap<String, CoachLayout?>()

    private fun layoutOf(deck: Deck): CoachLayout? =
        layoutCache.getOrPut(deck.layoutUrl ?: deck.name) { layoutProvider(deck) }

    private fun firstStop(): JourneyStop? = stops.minByOrNull { it.order }

    override fun toString(): String = "Journey(${stops.size} zastavok)"

    /**
     * Volne miesta danej triedy v celom vlaku, ako dvojice (vozen, miesto).
     *
     * @param onlyComfortable hlasit len miesta, kde je volne aj miesto vedla.
     *   Jedno volne miesto medzi dvoma obsadenymi je sice volne, ale pokoj
     *   neprinesie - a prave o pokoj v tejto appke ide. Kde sa topologia necita
     *   spolahlivo, miesto sa zaradi (radsej upozornit navyse ako zamlcat).
     */
    fun freeSeatsInClass(seatClass: String, onlyComfortable: Boolean = false): List<FreeClassSeat> {
        val start = firstStop() ?: return emptyList()
        val out = mutableListOf<FreeClassSeat>()
        for (vehicle in start.section.vehicles) {
            if (seatClass !in vehicle.seatClasses) continue
            val deck = vehicle.decks.firstOrNull() ?: continue
            val layout = layoutOf(deck)
            for (seat in deck.seats.filter { it.free }) {
                val n = layout?.neighboursOrNull(seat.index)
                // bez suseda (samostatne miesto) je pokoj automaticky; ked sa
                // topologia necita, berie sa ako pohodlne - radsej upozornit
                // navyse ako zamlcat
                val comfortable = n == null ||
                    n.nextTo.isEmpty() ||
                    n.nextTo.any { deck.seat(it)?.free == true }
                if (onlyComfortable && !comfortable) continue
                out.add(FreeClassSeat(vehicle.number, seat.index, comfortable))
            }
        }
        return out.sortedWith(compareBy({ it.coach }, { it.seat }))
    }

    /**
     * Oddiely vo vozni, v ktorych je volne kazde miesto.
     *
     * Kazdy oddiel je zapisany ako cisla miest oddelene ciarkou, aby sa dal
     * ulozit a porovnat medzi kolami sledovania. "Cele kupe je prazdne" je
     * silnejsi signal ako jednotlive uvolnene miesto.
     */
    fun emptyBaysInCoach(coach: Int): Set<String> {
        val start = firstStop() ?: return emptySet()
        val deck = start.section.vehicle(coach)?.decks?.firstOrNull() ?: return emptySet()
        val layout = layoutOf(deck) ?: return emptySet()
        val out = LinkedHashSet<String>()
        for (seat in deck.seats.map { it.index }) {
            val bay = layout.neighboursOrNull(seat)?.bay ?: continue
            if (bay.size < 2) continue                        // samostatne miesto nie je oddiel
            if (bay.all { deck.seat(it)?.free == true }) out.add(bay.joinToString(","))
        }
        return out
    }

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
                        kind = layout.seatKind(seat),
                        confidence = layout.confidence(seat),
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

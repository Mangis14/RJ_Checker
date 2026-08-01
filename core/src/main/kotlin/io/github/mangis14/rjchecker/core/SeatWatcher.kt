package io.github.mangis14.rjchecker.core

enum class SeatChange { FREED, TAKEN }

data class SeatAlert(val seat: Int, val change: SeatChange)

/**
 * Stav v jednom okamihu - vstup pre porovnanie medzi kolami.
 *
 * @param seats obsadenost susedov sledovaneho miesta
 * @param coachFreeSeats vsetky volne miesta vo vozni. Drzi sa cely zoznam, nie
 *   len pocet, aby notifikacia vedela povedat KTORE miesto sa uvolnilo - samotny
 *   pocet cloveku nepovie, kam si ma sadnut.
 */
data class SeatSnapshot(
    val seats: Map<Int, Boolean>,
    val freeInCoach: Int = 0,
    val coachFreeSeats: Set<Int> = emptySet(),
    /** oddiely s volnym kazdym miestom, zapisane ako "31,32,33,34,35,36" */
    val emptyBays: Set<String> = emptySet(),
    /** volne miesta sledovanej triedy v celom vlaku, zapisane ako "5-32" */
    val classFreeSeats: Set<String> = emptySet(),
) {
    companion object {
        fun of(
            analysis: SeatAnalysis,
            coachFreeSeats: Set<Int> = emptySet(),
            emptyBays: Set<String> = emptySet(),
        ): SeatSnapshot = SeatSnapshot(
            seats = analysis.neighbours.associate { it.seat to it.freeWholeWay },
            freeInCoach = analysis.freeInCoach,
            coachFreeSeats = coachFreeSeats,
            emptyBays = emptyBays,
        )
    }
}

/**
 * Porovnanie dvoch kol sledovania - z toho vznikaju notifikacie v appke.
 *
 * Plati jedno pravidlo: prve kolo nesmie ohlasit nic. Inak by appka pri kazdom
 * spusteni vypalila notifikaciu o zmene, ktora sa nestala.
 */
object SeatWatcher {

    fun diff(previous: SeatSnapshot?, current: SeatSnapshot): List<SeatAlert> {
        if (previous == null) return emptyList()
        return current.seats.mapNotNull { (seat, free) ->
            val before = previous.seats[seat] ?: return@mapNotNull null   // nove miesto nie je zmena
            when {
                before == free -> null
                free -> SeatAlert(seat, SeatChange.FREED)
                else -> SeatAlert(seat, SeatChange.TAKEN)
            }
        }.sortedBy { it.seat }
    }

    /** Vypredany spoj, v ktorom sa nieco uvolnilo (storno). */
    fun soldOutOpenedUp(previous: SeatSnapshot, current: SeatSnapshot): Boolean =
        previous.freeInCoach == 0 && current.freeInCoach > 0

    /**
     * Ktore miesta vo vozni sa oproti minulemu kolu uvolnili.
     *
     * Bez predchadzajuceho stavu nevraca nic - inak by prve kolo ohlasilo ako
     * "uvolnene" vsetky volne miesta vo vozni.
     */
    fun coachFreed(previous: SeatSnapshot?, current: SeatSnapshot): List<Int> {
        if (previous == null) return emptyList()
        return (current.coachFreeSeats - previous.coachFreeSeats).sorted()
    }

    /**
     * Oddiely, ktore sa oproti minulemu kolu uplne vyprazdnili.
     *
     * Toto je najsilnejsi signal: cele prazdne kupe alebo stolik znamena, ze sa
     * da presunut a cestovat sam. Ma preto prednost pred jednotlivymi miestami.
     */
    fun baysBecameEmpty(previous: SeatSnapshot?, current: SeatSnapshot): List<String> {
        if (previous == null) return emptyList()
        return (current.emptyBays - previous.emptyBays).sorted()
    }

    /**
     * Miesta sledovanej triedy, ktore sa oproti minulemu kolu uvolnili.
     *
     * Vracia zapisy "vozen-miesto", zoradene, aby sa dali priamo vypisat.
     */
    fun classSeatsFreed(previous: SeatSnapshot?, current: SeatSnapshot): List<String> {
        if (previous == null) return emptyList()
        return (current.classFreeSeats - previous.classFreeSeats).sortedWith(
            compareBy(
                { it.substringBefore('-').toIntOrNull() ?: 0 },
                { it.substringAfter('-').toIntOrNull() ?: 0 },
            ),
        )
    }

    /** "5-32" -> "vozeň 5, miesto 32" */
    fun describeClassSeats(keys: List<String>, max: Int = 4): String {
        if (keys.isEmpty()) return ""
        val shown = keys.take(max).joinToString("; ") { key ->
            val coach = key.substringBefore('-')
            val seat = key.substringAfter('-')
            "vozeň $coach, miesto $seat"
        }
        return if (keys.size <= max) shown else "$shown +${keys.size - max} ďalších"
    }

    /** Cisla miest do textu notifikacie, dlhy zoznam sa skrati. */
    fun describeSeats(seats: List<Int>, max: Int = 6): String {
        if (seats.isEmpty()) return ""
        if (seats.size <= max) return seats.joinToString(", ")
        return seats.take(max).joinToString(", ") + " +${seats.size - max} ďalších"
    }
}

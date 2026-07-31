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
) {
    companion object {
        fun of(analysis: SeatAnalysis, coachFreeSeats: Set<Int> = emptySet()): SeatSnapshot =
            SeatSnapshot(
                seats = analysis.neighbours.associate { it.seat to it.freeWholeWay },
                freeInCoach = analysis.freeInCoach,
                coachFreeSeats = coachFreeSeats,
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

    /** Cisla miest do textu notifikacie, dlhy zoznam sa skrati. */
    fun describeSeats(seats: List<Int>, max: Int = 6): String {
        if (seats.isEmpty()) return ""
        if (seats.size <= max) return seats.joinToString(", ")
        return seats.take(max).joinToString(", ") + " +${seats.size - max} ďalších"
    }
}

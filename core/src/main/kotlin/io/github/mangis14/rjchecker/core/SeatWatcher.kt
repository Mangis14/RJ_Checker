package io.github.mangis14.rjchecker.core

enum class SeatChange { FREED, TAKEN }

data class SeatAlert(val seat: Int, val change: SeatChange)

/** Stav susedstva v jednom okamihu - vstup pre porovnanie medzi kolami. */
data class SeatSnapshot(
    val seats: Map<Int, Boolean>,
    val freeInCoach: Int = 0,
) {
    companion object {
        fun of(analysis: SeatAnalysis): SeatSnapshot = SeatSnapshot(
            seats = analysis.neighbours.associate { it.seat to it.freeWholeWay },
            freeInCoach = analysis.freeInCoach,
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
}

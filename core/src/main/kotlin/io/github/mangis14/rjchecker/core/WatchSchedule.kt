package io.github.mangis14.rjchecker.core

/** Co sa ma stat pri tomto prebudeni workera. */
enum class WatchDecision {
    /** siahnut na siet a porovnat stav */
    CHECK,

    /** teraz netreba - prebudenie sa preskoci bez volania API */
    SKIP,

    /** cesta uz skoncila, sledovanie zrusit */
    STOP,
}

/**
 * Rozhoduje, ci sa ma kontrola na pozadi vobec spustit.
 *
 * Jedna kontrola stiahne cca 223 kB a server neposiela gzip, takze pri
 * prebudeni kazdych 15 minut by to bolo cca 21 MB denne. Dve pravidla to
 * stlacia na zlomok:
 *
 *  - po skonceni cesty sa sledovanie ukonci. Bez toho by worker tahal data
 *    naveky, aj tyzdne po ceste.
 *  - kym je odjazd dalej ako den, staci kontrolovat priblizne raz za hodinu.
 *    Blizko odjazdu a pocas cesty sa kontroluje kazde prebudenie, lebo vtedy
 *    je informacia o susedovi najcennejsia.
 */
object WatchSchedule {

    /** Po tomto case od odjazdu sa uz cesta povazuje za skoncenu. */
    const val JOURNEY_LENGTH_MINUTES = 10L * 60

    /** Do tejto vzdialenosti od odjazdu sa kontroluje kazde prebudenie. */
    const val NEAR_DEPARTURE_MINUTES = 24L * 60

    /** Kolko prebudeni sa preskoci, kym je odjazd daleko. */
    const val FAR_EVERY_NTH = 4

    /**
     * @param nowMinutes teraz, v minutach od epochy
     * @param departureMinutes odjazd, v minutach od epochy; null = nezname
     * @param tick poradove cislo prebudenia, drzi si ho volajuci
     */
    fun decide(nowMinutes: Long, departureMinutes: Long?, tick: Int): WatchDecision {
        // Neznamy cas odjazdu radsej kontrolovat, ako tichu prestat - pouzivatel
        // ceka notifikacie a mlcanie by vypadalo ako "nic sa nedeje".
        if (departureMinutes == null) return WatchDecision.CHECK

        val sinceDeparture = nowMinutes - departureMinutes
        // hranica vratane: po uplynuti tohto casu je cesta povazovana za skoncenu
        // (Praha - Kosice trva 7,3 az 8,5 h, takze 10 h je bezpecny odstup)
        if (sinceDeparture >= JOURNEY_LENGTH_MINUTES) return WatchDecision.STOP

        val untilDeparture = -sinceDeparture
        if (untilDeparture > NEAR_DEPARTURE_MINUTES) {
            return if (tick % FAR_EVERY_NTH == 0) WatchDecision.CHECK else WatchDecision.SKIP
        }
        return WatchDecision.CHECK
    }
}

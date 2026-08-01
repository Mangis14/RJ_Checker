package io.github.mangis14.rjchecker.core

/**
 * Spoj a miesto, ktore chce uzivatel sledovat.
 *
 * Je to cisty datovy typ v core (nie v Android module) prave preto, aby sa
 * serializacia zoznamu dala testovat bez Androidu.
 */
data class WatchedTrip(
    val date: String,
    val fromId: Long,
    val toId: Long,
    val fromName: String,
    val toName: String,
    val routeId: String,
    val departure: String,
    /** 0 pri sledovani celej triedy */
    val coach: Int,
    /** 0 pri sledovani celej triedy */
    val seat: Int,
    /**
     * Ked je vyplnene, nesleduje sa konkretne miesto, ale cela trieda v spoji -
     * "daj vediet, ked sa uvolni hocijaky Relax".
     */
    val seatClass: String? = null,
    /**
     * Pri sledovani triedy hlasit len miesta, ktore stoja za presun, teda kde je
     * volne aj miesto vedla. Jedno volne miesto medzi dvoma obsadenymi je sice
     * volne, ale pokoj neprinesie.
     */
    val onlyComfortable: Boolean = false,
) {
    /** true = sleduje sa cela trieda, nie konkretne miesto */
    val isClassWatch: Boolean get() = seatClass != null

    /** Stabilny kluc - podla neho sa uklada snapshot a rusi sledovanie. */
    val id: String get() =
        if (isClassWatch) "$routeId-class-$seatClass" else "$routeId-$coach-$seat"

    /** Kratky popis pre zoznam sledovanych a pre notifikaciu. */
    val label: String get() =
        if (isClassWatch) "trieda $seatClass" else "vozeň $coach, miesto $seat"
}

/**
 * Prevod zoznamu sledovanych spojov na jeden text a spat.
 *
 * Zamerne bez JSON kniznice: hodnoty su kratke a bez struktury, takze ulozisko
 * (SharedPreferences) zostava trivialne. Oddelovace su citatelne ASCII znaky a
 * v hodnotach sa escapuju - nazvy stanic ich neobsahuju, ale escapovanie robi
 * format bezpecnym aj keby sa to zmenilo.
 */
object WatchedTripCodec {

    private const val FIELD = "|"
    private const val RECORD = "\n"
    private const val ESCAPED_FIELD = "%7C"
    private const val ESCAPED_RECORD = "%0A"
    private const val ESCAPED_PERCENT = "%25"

    private fun esc(value: String) = value
        .replace("%", ESCAPED_PERCENT)
        .replace(FIELD, ESCAPED_FIELD)
        .replace(RECORD, ESCAPED_RECORD)

    private fun unesc(value: String) = value
        .replace(ESCAPED_RECORD, RECORD)
        .replace(ESCAPED_FIELD, FIELD)
        .replace(ESCAPED_PERCENT, "%")

    /** Zoznam spojov do jedneho retazca pre SharedPreferences. */
    fun encode(trips: List<WatchedTrip>): String = trips.joinToString(RECORD) { t ->
        listOf(
            t.date, t.fromId.toString(), t.toId.toString(),
            esc(t.fromName), esc(t.toName), t.routeId, t.departure,
            t.coach.toString(), t.seat.toString(),
            t.seatClass.orEmpty(), if (t.onlyComfortable) "1" else "0",
        ).joinToString(FIELD)
    }

    /**
     * Starsie zaznamy maju len 9 poli (bez triedy) - nacitaju sa ako sledovanie
     * konkretneho miesta, aby uzivatel po aktualizacii neprisiel o sledovanie.
     */
    fun decode(raw: String?): List<WatchedTrip> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(RECORD).mapNotNull { record ->
            if (record.isBlank()) return@mapNotNull null
            val f = record.split(FIELD)
            if (f.size < 9) return@mapNotNull null
            WatchedTrip(
                date = f[0],
                fromId = f[1].toLongOrNull() ?: return@mapNotNull null,
                toId = f[2].toLongOrNull() ?: return@mapNotNull null,
                fromName = unesc(f[3]),
                toName = unesc(f[4]),
                routeId = f[5],
                departure = f[6],
                coach = f[7].toIntOrNull() ?: return@mapNotNull null,
                seat = f[8].toIntOrNull() ?: return@mapNotNull null,
                seatClass = f.getOrNull(9)?.takeIf { it.isNotBlank() },
                onlyComfortable = f.getOrNull(10) == "1",
            )
        }
    }
}

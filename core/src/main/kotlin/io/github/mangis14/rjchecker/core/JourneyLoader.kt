package io.github.mangis14.rjchecker.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** Zastavka spoja pred nacitanim obsadenosti. */
data class PlannedStop(
    val stationId: Long,
    val order: Int,
    val departure: String,
    /** false = cas je zo zakladneho poriadku, spoj moze byt pretrasovany */
    val exactTime: Boolean,
    /** false = z tejto stanice sa do spoja neda nastupit (ale vlak tam zastavuje) */
    val bookable: Boolean,
)

/**
 * Zlozi [Journey] pre konkretny spoj - zastavky, realne casy a obsadenost.
 *
 * Jedno volanie freeSeats vracia VSETKY vozne naraz, takze jeden prechod
 * zastavkami staci na analyzu vlastneho miesta aj na odporucanie miesta v celom
 * vlaku.
 */
class JourneyLoader(
    private val client: RjClient,
    private val stationNames: Map<Long, String> = emptyMap(),
) {
    private var timetableCache: JsonArray? = null

    /**
     * Zastavky spoja z cestovneho poriadku, v geografickom poradi.
     *
     * Beru sa len zaznamy s casom odjazdu - polozky so symbolom "<" a bez casu
     * su navazujuce spoje, nie zastavky tohto vlaku.
     */
    fun plannedStops(lineCode: String?, fromStationId: Long, toStationId: Long, date: String, departure: String): List<PlannedStop> {
        val timetables = timetableCache ?: client.timetables().also { timetableCache = it }
        var best: Triple<Int, JsonArray, Map<Long, Int>>? = null

        for (tt in timetables) {
            val o = tt.jsonObject
            val stationsArray = o["stations"]?.jsonArray ?: continue
            val indexOf = HashMap<Long, Int>()
            val depOf = HashMap<Long, String>()
            for (s in stationsArray) {
                val so = s.jsonObject
                val id = (so["stationId"] as? JsonPrimitive)?.content?.toLongOrNull() ?: continue
                indexOf[id] = (so["index"] as? JsonPrimitive)?.content?.toIntOrNull() ?: continue
                depOf[id] = (so["departure"] as? JsonPrimitive)?.content.orEmpty()
            }
            val fromIdx = indexOf[fromStationId] ?: continue
            val toIdx = indexOf[toStationId] ?: continue
            if (fromIdx >= toIdx) continue

            var score = 0
            if (lineCode != null && (o["connectionCode"] as? JsonPrimitive)?.content == lineCode) score += 100
            if (depOf[fromStationId]?.take(5) == departure) score += 50
            val validFrom = (o["validFrom"] as? JsonPrimitive)?.content ?: ""
            val validTo = (o["validTo"] as? JsonPrimitive)?.content ?: ""
            if (validFrom <= date && date <= validTo) score += 10
            if (score > 0 && (best == null || score > best!!.first)) {
                best = Triple(score, stationsArray, indexOf)
            }
        }

        val chosen = best ?: return emptyList()
        val (_, stationsArray, indexOf) = chosen
        val lo = indexOf.getValue(fromStationId)
        val hi = indexOf.getValue(toStationId)

        return stationsArray.mapNotNull { s ->
            val so = s.jsonObject
            val id = (so["stationId"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return@mapNotNull null
            val idx = (so["index"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return@mapNotNull null
            val dep = (so["departure"] as? JsonPrimitive)?.content.orEmpty()
            if (idx < lo || idx >= hi || dep.isBlank()) return@mapNotNull null
            PlannedStop(id, idx, dep.take(5), exactTime = false, bookable = true)
        }.sortedBy { it.order }
    }

    /**
     * Doplni realny cas odjazdu pre dany den.
     *
     * Pri vyluke ma spoj iny cas, nez je v zakladnom poriadku. Presny cas sa da
     * zistit len pre useky, ktore RegioJet predava. Zastavky, z ktorych sa
     * nastupit neda, sa NEZAHADZUJU - vlak tam zastavuje a obsadenost pre ne
     * plati, takze prave tam je vidno, kde niekto vystupuje.
     */
    fun refineTimes(routeId: String, stops: List<PlannedStop>, toStationId: Long): List<PlannedStop> =
        stops.map { stop ->
            try {
                val detail = client.routeDetail(routeId, stop.stationId, toStationId)
                val dep = (detail["departureTime"] as? JsonPrimitive)?.content
                if (dep != null && dep.length >= 16) {
                    stop.copy(departure = dep.substring(11, 16), exactTime = true, bookable = true)
                } else {
                    stop.copy(bookable = true)
                }
            } catch (e: RjApiException) {
                stop.copy(bookable = false)          // usek sa nepredava
            }
        }

    /**
     * Nacita obsadenost pre kazdu zastavku a zlozi [Journey].
     *
     * @param firstStopOnly nacita len usek z vychodzej stanice - jedno volanie.
     *   Odpoved obsahuje vsetky vozne, takze to staci na vyber vozna a miesta;
     *   uzivatel tak necaka na prechod vsetkymi zastavkami. Na otazku "od ktorej
     *   stanice sa miesto uvolni" uz treba plny prechod.
     * @param onProgress hlasi (hotove, celkovo) - UI tak vie ukazat priebeh
     */
    fun load(
        routeId: String,
        fromStationId: Long,
        toStationId: Long,
        date: String,
        departure: String,
        pauseMillis: Long = 350,
        firstStopOnly: Boolean = false,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Journey {
        val detail = client.routeDetail(routeId, fromStationId, toStationId)
        val section = detail["sections"]?.jsonArray?.firstOrNull()?.jsonObject
        val sectionId = (section?.get("id") as? JsonPrimitive)?.content?.toLongOrNull()
            ?: (detail["mainSectionId"] as? JsonPrimitive)?.content?.toLongOrNull()
            ?: throw RjApiException("Spoj $routeId nema sectionId")
        val lineCode = (section?.get("line")?.jsonObject?.get("code") as? JsonPrimitive)?.content

        val stops = if (firstStopOnly) {
            listOf(PlannedStop(fromStationId, 1, departure, exactTime = true, bookable = true))
        } else {
            val planned = plannedStops(lineCode, fromStationId, toStationId, date, departure)
                .ifEmpty { listOf(PlannedStop(fromStationId, 1, departure, exactTime = true, bookable = true)) }
            refineTimes(routeId, planned, toStationId)
        }

        val loaded = mutableListOf<JourneyStop>()
        stops.forEachIndexed { i, stop ->
            if (i > 0 && pauseMillis > 0) Thread.sleep(pauseMillis)
            val occupancy = try {
                client.freeSeats(sectionId, stop.stationId, toStationId)
            } catch (e: RjApiException) {
                null
            }
            if (occupancy != null) {
                loaded.add(
                    JourneyStop(
                        stationId = stop.stationId,
                        stationName = stationNames[stop.stationId] ?: "stanica ${stop.stationId}",
                        departure = if (stop.exactTime) stop.departure else "~${stop.departure}",
                        order = stop.order,
                        section = occupancy,
                    ),
                )
            }
            onProgress(loaded.size, stops.size)
        }
        if (loaded.isEmpty()) throw RjApiException("Nepodarilo sa nacitat obsadenost ani jednej zastavky")

        return Journey(loaded).apply {
            layoutProvider = { deck ->
                deck.layoutUrl?.let { url ->
                    client.layoutSvg(url)?.let { svg ->
                        SeatGeometry.parse(svg, deck.seats.map { it.index })
                    }
                }
            }
        }
    }
}

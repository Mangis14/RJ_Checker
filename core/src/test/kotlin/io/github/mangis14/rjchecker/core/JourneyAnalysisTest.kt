package io.github.mangis14.rjchecker.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Analyza vlastneho miesta a odporucanie pokojnejsieho miesta nad obsadenostou
 * po usekoch.
 *
 * Kluc k spravnemu citaniu dat: obsadenost plati pre USEK. Miesto volne pre usek
 * <vychodzia> -> ciel je volne po celu cestu, kratsi usek ho uz obsadit nemoze.
 * Prechod zastavkami preto sluzi na opacnu otazku: kde sa OBSADENE miesto uvolni.
 */
class JourneyAnalysisTest {

    /** Zostavi cestu z fixtures: zastavky v poradi + obsadenost pre kazdu. */
    private fun journey(): Journey {
        val byStation = Fixtures.freeSeatsByStation()
        val stops = Fixtures.stopOrder().mapIndexedNotNull { index, stationId ->
            val body = byStation[stationId.toString()] ?: return@mapIndexedNotNull null
            val section = FreeSeatsParser.parse(body) ?: return@mapIndexedNotNull null
            JourneyStop(
                stationId = stationId,
                stationName = "stanica $stationId",
                departure = "00:%02d".format(index),
                order = index,
                section = section,
            )
        }
        assertTrue(stops.size >= 5, "ocakavam aspon 5 zastavok, mam ${stops.size}")
        return Journey(stops).apply {
            // core modul po sieti nesiaha, layouty mu dodava volajuci - v testoch
            // z odchytenych SVG podla nazvu suboru v layoutURL
            layoutProvider = { deck ->
                deck.layoutUrl?.substringAfterLast('/')?.let { file ->
                    runCatching {
                        SeatGeometry.parse(Fixtures.layoutSvg(file), deck.seats.map { it.index })
                    }.getOrNull()
                }
            }
        }
    }

    @Test
    fun `susedstvo miesta nesie obsadenost aj stanicu uvolnenia`() {
        val analysis = assertNotNull(journey().analyseSeat(coach = 6, seat = 32))
        assertTrue(analysis.bay.contains(32))
        assertTrue(analysis.neighbours.isNotEmpty(), "miesto ma mat susedov")
        for (n in analysis.neighbours) {
            // bud je volne celu cestu, alebo vieme povedat kde sa uvolni, alebo nikdy
            if (n.freeWholeWay) assertNull(n.freesAt)
        }
    }

    @Test
    fun `miesto volne na zaciatku je volne celu cestu`() {
        val j = journey()
        val first = j.stops.first()
        val deck = assertNotNull(first.section.vehicle(6)).decks.first()
        val freeAtStart = deck.seats.filter { it.free }.map { it.index }.toSet()
        // pre kazdu dalsiu zastavku musi zostat volne - kratsi usek ho neobsadi
        for (stop in j.stops.drop(1)) {
            val laterDeck = stop.section.vehicle(6)?.decks?.first() ?: continue
            val freeLater = laterDeck.seats.filter { it.free }.map { it.index }.toSet()
            val vanished = freeAtStart.filter { it in laterDeck.seats.map { s -> s.index } } - freeLater
            assertTrue(
                vanished.isEmpty(),
                "miesta $vanished boli volne z vychodzej, ale nie z ${stop.stationId}",
            )
        }
    }

    @Test
    fun `neexistujuci vozen alebo miesto vrati null`() {
        val j = journey()
        assertNull(j.analyseSeat(coach = 99, seat = 32))
        assertNull(j.analyseSeat(coach = 6, seat = 999))
    }

    @Test
    fun `odporucanie navrhuje len volne miesta a kazdy oddiel raz`() {
        val picks = journey().recommend(seatClass = null, limit = 8)
        assertTrue(picks.isNotEmpty(), "ocakavam aspon jeden navrh")
        val bays = picks.map { it.coach to it.bay.toSet() }
        assertEquals(bays.size, bays.distinct().size, "ten isty oddiel sa nema opakovat")
        val first = journey().stops.first()
        for (pick in picks) {
            val deck = assertNotNull(first.section.vehicle(pick.coach)).decks.first()
            val seat = assertNotNull(deck.seats.firstOrNull { it.index == pick.seat })
            assertTrue(seat.free, "navrhnute miesto ${pick.seat} nie je volne")
        }
    }

    @Test
    fun `odporucanie preferuje prazdny oddiel pred obsadenym`() {
        val picks = journey().recommend(seatClass = null, limit = 20)
        val fullyFree = picks.filter { it.takenNeighbours.isEmpty() }
        val partly = picks.filter { it.takenNeighbours.isNotEmpty() }
        if (fullyFree.isNotEmpty() && partly.isNotEmpty()) {
            assertTrue(
                fullyFree.minOf { it.score } >= partly.maxOf { it.score },
                "prazdny oddiel ma mat vzdy vyssie skore ako ciastocne obsadeny",
            )
        }
    }

    @Test
    fun `odporucanie nikdy nesluby pokoj do stanice pred vychodzou`() {
        // Regresia: povodna verzia hlasila "pokoj do Olomouc" pri mieste, ktore
        // je volne od Prahy - to je logicky nemozne.
        for (pick in journey().recommend(seatClass = null, limit = 20)) {
            val emptyFrom = pick.emptyFrom ?: continue
            assertTrue(
                emptyFrom.order > 0 || pick.takenNeighbours.isEmpty(),
                "miesto ${pick.seat}: oddiel sa nema 'vyprazdnit' uz na vychodzej stanici",
            )
        }
    }

    @Test
    fun `filter podla triedy vrati len vozne danej triedy`() {
        val j = journey()
        val standard = j.recommend(seatClass = "C0", limit = 20)
        val first = j.stops.first()
        for (pick in standard) {
            val vehicle = assertNotNull(first.section.vehicle(pick.coach))
            assertTrue("C0" in vehicle.seatClasses, "vozen ${pick.coach} nie je C0")
        }
    }
}

package io.github.mangis14.rjchecker.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Zhrnutie pohodlia pre cely vlak.
 *
 * Pouziva sa uz pri vybere spoja, kde este nemame vybrany vozen ani miesto -
 * a hlavne kde nechceme cakat na prechod vsetkymi zastavkami. Preto musi
 * vystacit s obsadenostou jedneho useku, teda s jednym volanim API.
 */
class ComfortSummaryTest {

    private fun singleStopJourney(): Journey {
        val section = assertNotNull(FreeSeatsParser.parse(Fixtures.freeSeatsPraha()))
        return Journey(
            listOf(
                JourneyStop(
                    stationId = 372825000,
                    stationName = "Praha",
                    departure = "21:45",
                    order = 0,
                    section = section,
                ),
            ),
        ).apply {
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
    fun `zhrnutie sa da spocitat z jedneho useku`() {
        val summary = singleStopJourney().comfortSummary(seatClass = null)
        assertTrue(summary.freeSeats > 0, "vlak ma mat volne miesta")
        assertTrue(summary.emptyCompartments >= 0)
        assertTrue(summary.emptyPairs >= 0)
    }

    @Test
    fun `najlepsi navrh je volne miesto a ma najvyssie skore`() {
        val journey = singleStopJourney()
        val summary = journey.comfortSummary(seatClass = null)
        val best = summary.best
        if (best != null) {
            val deck = assertNotNull(journey.stops.first().section.vehicle(best.coach)).decks.first()
            assertTrue(assertNotNull(deck.seat(best.seat)).free, "navrhnute miesto musi byt volne")
            val all = journey.recommend(null, limit = 20)
            assertEquals(all.first().score, best.score, "best ma byt najvyssie skore")
        }
    }

    @Test
    fun `prazdny oddiel znamena ze vsetky ostatne miesta v nom su volne`() {
        val journey = singleStopJourney()
        val summary = journey.comfortSummary(seatClass = null)
        // spocitaj nezavisle a porovnaj
        val start = journey.stops.first().section
        var compartments = 0
        var pairs = 0
        val seen = HashSet<Pair<Int, Set<Int>>>()
        for (vehicle in start.vehicles) {
            val deck = vehicle.decks.firstOrNull() ?: continue
            val layout = journey.layoutProvider(deck) ?: continue
            for (seat in deck.seats.filter { it.free }.map { it.index }) {
                val n = layout.neighboursOrNull(seat) ?: continue
                if (!seen.add(vehicle.number to n.bay.toSet())) continue
                if (n.bay.all { deck.seat(it)?.free == true }) {
                    if (layout.seatBay[seat] != null) compartments++ else pairs++
                }
            }
        }
        assertEquals(compartments, summary.emptyCompartments)
        assertEquals(pairs, summary.emptyPairs)
    }

    @Test
    fun `filter podla triedy zuzi vysledok`() {
        val journey = singleStopJourney()
        val all = journey.comfortSummary(null)
        val standard = journey.comfortSummary("C0")
        assertTrue(
            standard.freeSeats <= all.freeSeats,
            "jedna trieda nemoze mat viac volnych miest ako cely vlak",
        )
    }

    @Test
    fun `prazdny vlak bez zastavok nespadne`() {
        val summary = Journey(emptyList()).comfortSummary(null)
        assertEquals(0, summary.freeSeats)
        assertEquals(null, summary.best)
    }
}

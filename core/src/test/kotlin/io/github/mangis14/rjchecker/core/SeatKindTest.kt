package io.github.mangis14.rjchecker.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Typ sedadla - samostatne, dvojica, stvorica so stolikom, kupe.
 *
 * Odvodzuje sa z topologie, nie z API: priznak "miesto pri stoliku" posiela
 * RegioJet len pri vozni Astra a aj to len preto, ze tam upozornuje na chybajucu
 * obrazovku. Ako vseobecny marker stolika sa pouzit neda.
 */
class SeatKindTest {

    private fun layoutNamed(name: String): CoachLayout {
        val case = Fixtures.layoutCases().firstOrNull { it.deckName == name }
            ?: fail("fixture pre vozen '$name' chyba")
        return assertNotNull(
            SeatGeometry.parse(Fixtures.layoutSvg(case.file), case.seats),
            "layout $name sa nepodarilo precitat",
        )
    }

    @Test
    fun `kupejovy vozen hlasi kupe`() {
        val layout = layoutNamed("Vuz Bk (42)")
        assertEquals(SeatKind.COMPARTMENT, layout.seatKind(32))
        assertEquals(SeatKind.COMPARTMENT, layout.seatKind(12))
    }

    @Test
    fun `velkopriestorovy 2 plus 2 hlasi dvojicu alebo stvoricu`() {
        for (name in listOf("Vuz Bm Astra (80)", "Vuz Bp2xx (80) LOW cost")) {
            val layout = layoutNamed(name)
            val kind = layout.seatKind(32)
            assertTrue(
                kind == SeatKind.PAIR || kind == SeatKind.TABLE_QUAD,
                "$name: miesto 32 ma byt dvojica alebo stvorica, je $kind",
            )
        }
    }

    @Test
    fun `miesto mimo vozna je nezname`() {
        assertEquals(SeatKind.UNKNOWN, layoutNamed("Vuz Bk (42)").seatKind(999))
    }

    @Test
    fun `kazdy typ je konzistentny s velkostou oddielu`() {
        for (case in Fixtures.layoutCases()) {
            val layout = SeatGeometry.parse(Fixtures.layoutSvg(case.file), case.seats) ?: continue
            for (seat in case.seats.filter { layout.contains(it) }) {
                val kind = layout.seatKind(seat)
                val bay = layout.bay(seat).size
                when (kind) {
                    SeatKind.COMPARTMENT -> assertTrue(bay >= 5, "${case.deckName} m$seat: kupe ma mat 5+ miest, ma $bay")
                    SeatKind.TABLE_QUAD -> assertEquals(4, bay, "${case.deckName} m$seat")
                    SeatKind.PAIR -> assertEquals(2, bay, "${case.deckName} m$seat")
                    SeatKind.SINGLE -> assertEquals(1, bay, "${case.deckName} m$seat")
                    SeatKind.UNKNOWN -> Unit
                }
            }
        }
    }

    @Test
    fun `dostupnost podla triedy scita volne miesta`() {
        val section = assertNotNull(FreeSeatsParser.parse(Fixtures.freeSeatsPraha()))
        val journey = Journey(
            listOf(JourneyStop(1, "Praha", "21:45", 0, section)),
        ).apply {
            layoutProvider = { deck ->
                deck.layoutUrl?.substringAfterLast('/')?.let { file ->
                    runCatching { SeatGeometry.parse(Fixtures.layoutSvg(file), deck.seats.map { it.index }) }
                        .getOrNull()
                }
            }
        }
        val byClass = journey.availabilityByClass()
        assertTrue(byClass.isNotEmpty(), "vlak ma mat aspon jednu triedu")

        // sucet cez triedy sa musi rovnat celkovemu poctu volnych miest
        val total = section.vehicles.sumOf { v -> v.decks.first().seats.count { it.free } }
        assertEquals(total, byClass.sumOf { it.freeSeats }, "sucet po triedach != celkovy pocet")

        // triedy su usporiadane od najviac volnych miest
        assertEquals(byClass.sortedByDescending { it.freeSeats }, byClass)
        for (row in byClass) {
            assertTrue(row.seatClass.isNotBlank())
            assertTrue(row.byKind.values.sum() <= row.freeSeats, "typy nemozu prevysit pocet miest")
        }
    }
}

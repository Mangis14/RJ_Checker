package io.github.mangis14.rjchecker.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Topologia miest odvodena z SVG layoutu vozna.
 *
 * Ocakavania vychadzaju z fyzickej podoby voznov, nie z toho, co kod prave robi:
 * kupejovy vozen ma 6-miestne kupe s dvoma miestami vedla a jednym oproti,
 * velkopriestorovy 2+2 vozen ma presne jedneho suseda vedla.
 */
class SeatGeometryTest {

    private fun layoutOf(case: Fixtures.LayoutCase): CoachLayout {
        val layout = SeatGeometry.parse(Fixtures.layoutSvg(case.file), case.seats)
        return assertNotNull(layout, "layout ${case.deckName} sa nepodarilo precitat")
    }

    private fun caseNamed(name: String): Fixtures.LayoutCase =
        Fixtures.layoutCases().firstOrNull { it.deckName == name }
            ?: fail("fixture pre vozen '$name' chyba")

    @Test
    fun `vsetky odchytene layouty sa daju precitat`() {
        val cases = Fixtures.layoutCases()
        assertTrue(cases.size >= 10, "ocakavam aspon 10 typov voznov, mam ${cases.size}")
        for (case in cases) {
            val layout = layoutOf(case)
            // layout musi pokryt takmer vsetky miesta, ktore hlasi API
            val covered = case.seats.count { layout.contains(it) }
            assertTrue(
                covered >= (case.seats.size * 0.95).toInt(),
                "${case.deckName}: pokrytych len $covered z ${case.seats.size} miest",
            )
        }
    }

    @Test
    fun `kupejovy vozen Bk 42 - miesto 32 ma kupe 31-36, vedla 31 a 33, oproti 35`() {
        val layout = layoutOf(caseNamed("Vuz Bk (42)"))
        val n = layout.neighbours(32)
        assertEquals(listOf(31, 32, 33, 34, 35, 36), n.bay)
        assertEquals(setOf(31, 33), n.nextTo.toSet())
        assertEquals(35, n.facing)
        assertEquals(Confidence.CERTAIN, layout.confidence(32))
    }

    @Test
    fun `kupejovy vozen AK 42 - kupe sa odvodi z cislovania miest`() {
        val layout = layoutOf(caseNamed("Vuz AK (42)"))
        val n = layout.neighbours(32)
        assertEquals(listOf(31, 32, 33, 34, 35, 36), n.bay)
        assertEquals(setOf(31, 33), n.nextTo.toSet())
        assertNotNull(n.facing, "v kupe ma existovat miesto oproti")
        assertEquals(BaySource.SEAT_NUMBERING, layout.baySource)
    }

    @Test
    fun `velkopriestorove vozne 2+2 maju presne jedneho suseda vedla`() {
        for (name in listOf("Vuz Bm Astra (80)", "Vuz Bp2xx (80) LOW cost", "Vuz Bp1xx (75) LOW cost")) {
            val layout = layoutOf(caseNamed(name))
            assertNotNull(layout.aisleAfterColumn, "$name: ulicka sa nenasla")
            val n = layout.neighbours(32)
            assertEquals(1, n.nextTo.size, "$name: vedla ma byt 1 miesto, je ${n.nextTo}")
            assertNull(n.facing, "$name: radove sedenie nema miesto oproti")
            assertEquals(Confidence.CERTAIN, layout.confidence(32), "$name")
        }
    }

    @Test
    fun `Relax Bm3xx sa neda citat spolahlivo a kod to prizna`() {
        // ZNAMA LIMITACIA: SVG tohto vozna je o verziu starsie ako cislovanie
        // z API (ma miesto 26, API ma 35) a stlpce sa necitaju cisto.
        // Poziadavkou je priznat neistotu, nie uhadnut spravne.
        val layout = layoutOf(caseNamed("Vuz Bm3xx (54) Relax"))
        assertEquals(Confidence.UNCERTAIN, layout.confidence(32))
        assertTrue(
            layout.neighbours(32).nextTo.size > 1,
            "pri neistote ma vratit viac kandidatov, nie jedno miesto",
        )
    }

    @Test
    fun `miesto mimo vozna nema susedstvo`() {
        val layout = layoutOf(caseNamed("Vuz Bk (42)"))
        assertTrue(!layout.contains(999))
        assertNull(layout.neighboursOrNull(999))
    }
}

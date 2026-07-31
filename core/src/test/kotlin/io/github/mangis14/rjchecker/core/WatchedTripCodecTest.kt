package io.github.mangis14.rjchecker.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Zoznam sledovanych spojov v ulozisku.
 *
 * Predtym sa drzal len jeden spoj, takze nastavenie sledovania pre cestu spat
 * ticho prepisalo cestu tam. Preto zoznam - a preto tento test.
 */
class WatchedTripCodecTest {

    private fun trip(coach: Int, seat: Int, from: String = "Praha - hl.n.") = WatchedTrip(
        date = "2026-08-14",
        fromId = 372825000, toId = 1763018007,
        fromName = from, toName = "Košice - žst.",
        routeId = "8551447568", departure = "07:41",
        coach = coach, seat = seat,
    )

    @Test
    fun `prazdny zoznam prezije obe cesty`() {
        assertEquals(emptyList(), WatchedTripCodec.decode(WatchedTripCodec.encode(emptyList())))
        assertEquals(emptyList(), WatchedTripCodec.decode(null))
        assertEquals(emptyList(), WatchedTripCodec.decode(""))
    }

    @Test
    fun `viac spojov sa zachova vratane poradia`() {
        val trips = listOf(trip(6, 32), trip(3, 41), trip(2, 15))
        assertEquals(trips, WatchedTripCodec.decode(WatchedTripCodec.encode(trips)))
    }

    @Test
    fun `diakritika v nazve stanice sa nepokazi`() {
        val trips = listOf(trip(6, 32, from = "Žilina - žst."))
        assertEquals(trips, WatchedTripCodec.decode(WatchedTripCodec.encode(trips)))
    }

    @Test
    fun `oddelovace v hodnote sa escapuju`() {
        // nazov s oddelovacom by inak rozlomil zapis na dve polozky
        val trips = listOf(trip(6, 32, from = "Divná | stanica"), trip(1, 1))
        val decoded = WatchedTripCodec.decode(WatchedTripCodec.encode(trips))
        assertEquals(2, decoded.size, "escapovanie zlyhalo, zapis sa rozlomil")
        assertEquals("Divná | stanica", decoded[0].fromName)
    }

    @Test
    fun `percento v hodnote sa nepokazi`() {
        val trips = listOf(trip(6, 32, from = "100%25 stanica"))
        assertEquals(trips, WatchedTripCodec.decode(WatchedTripCodec.encode(trips)))
    }

    @Test
    fun `poskodeny zapis sa preskoci a zvysok sa nacita`() {
        val good = WatchedTripCodec.encode(listOf(trip(6, 32)))
        val decoded = WatchedTripCodec.decode("toto|nie|je|spoj\n$good")
        assertEquals(1, decoded.size, "nekompletna polozka sa ma preskocit")
        assertEquals(32, decoded.first().seat)
    }

    @Test
    fun `id odlisi spoje aj rovnaky vlak s inym miestom`() {
        assertTrue(trip(6, 32).id != trip(6, 33).id)
        assertTrue(trip(6, 32).id != trip(7, 32).id)
        assertEquals(trip(6, 32).id, trip(6, 32).id)
    }
}

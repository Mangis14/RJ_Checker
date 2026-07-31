package io.github.mangis14.rjchecker.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Parsovanie odpovede POST /routes/freeSeats.
 *
 * Odpoved obsahuje vsetky vozne naraz - to je dolezite, lebo jeden prechod
 * zastavkami tak staci na analyzu vlastneho miesta aj na odporucanie.
 */
class FreeSeatsParserTest {

    private val section by lazy { FreeSeatsParser.parse(Fixtures.freeSeatsPraha()) }

    @Test
    fun `odpoved sa rozparsuje na vozne s volnymi a obsadenymi miestami`() {
        val s = assertNotNull(section)
        assertTrue(s.vehicles.size >= 7, "ocakavam viac voznov, mam ${s.vehicles.size}")
        for (vehicle in s.vehicles) {
            val deck = vehicle.decks.first()
            assertTrue(
                deck.seats.isNotEmpty(),
                "vozen ${vehicle.number} nema ziadne miesta",
            )
            // kazde miesto je bud volne alebo obsadene, nikdy oboje
            val free = deck.seats.count { it.free }
            assertEquals(deck.seats.size, free + deck.seats.count { !it.free })
        }
    }

    @Test
    fun `vozen sa da najst podla cisla a nesie triedu aj layout`() {
        val s = assertNotNull(section)
        val vehicle = assertNotNull(s.vehicle(6), "vozen 6 chyba")
        val deck = vehicle.decks.first()
        assertTrue(deck.name.isNotBlank())
        assertTrue(vehicle.seatClasses.isNotEmpty())
        assertNotNull(deck.layoutUrl, "layoutURL je potrebny na topologiu miest")
    }

    @Test
    fun `seatConstraint sa preklada na priznaky miesta`() {
        val s = assertNotNull(section)
        val all = s.vehicles.flatMap { it.decks }.flatMap { it.seats }
        // v suprave nocneho spoja su miesta pri stoliku aj tiche kupe
        assertTrue(
            all.any { SeatFlag.TABLE in it.flags },
            "ocakavam aspon jedno miesto pri stoliku",
        )
        assertTrue(
            all.any { SeatFlag.QUIET in it.flags } || all.any { SeatFlag.CHILDREN in it.flags },
            "ocakavam tiche alebo detske kupe",
        )
    }

    @Test
    fun `obsadenost sa da nacitat pre kazdu zastavku`() {
        val byStation = Fixtures.freeSeatsByStation()
        assertTrue(byStation.size >= 5, "ocakavam viac zastavok, mam ${byStation.size}")
        for ((stationId, body) in byStation) {
            val parsed = FreeSeatsParser.parse(body)
            assertNotNull(parsed, "zastavka $stationId sa nerozparsovala")
            assertTrue(parsed.vehicles.isNotEmpty(), "zastavka $stationId nema vozne")
        }
    }

    @Test
    fun `chybna odpoved nespadne ale vrati null`() {
        assertEquals(null, FreeSeatsParser.parse("""{"message":"Unexpected error"}"""))
        assertEquals(null, FreeSeatsParser.parse("[]"))
        assertEquals(null, FreeSeatsParser.parse("toto nie je json"))
    }
}

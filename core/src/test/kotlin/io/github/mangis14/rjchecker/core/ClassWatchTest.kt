package io.github.mangis14.rjchecker.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Sledovanie celej triedy: "daj vediet, ked sa uvolni hocijaky Relax".
 *
 * Na rozdiel od sledovania konkretneho miesta sa tu pozera cely vlak, takze
 * snapshot drzi dvojice vozen-miesto.
 */
class ClassWatchTest {

    private fun journey(): Journey {
        val section = assertNotNull(FreeSeatsParser.parse(Fixtures.freeSeatsPraha()))
        return Journey(listOf(JourneyStop(1, "Praha", "21:45", 0, section))).apply {
            layoutProvider = { deck ->
                deck.layoutUrl?.substringAfterLast('/')?.let { file ->
                    runCatching { SeatGeometry.parse(Fixtures.layoutSvg(file), deck.seats.map { it.index }) }
                        .getOrNull()
                }
            }
        }
    }

    private fun anyClassWithFreeSeats(j: Journey): String =
        j.availabilityByClass().first { it.freeSeats > 0 }.seatClass

    @Test
    fun `volne miesta triedy su len z voznov tej triedy`() {
        val j = journey()
        val cls = anyClassWithFreeSeats(j)
        val seats = j.freeSeatsInClass(cls)
        assertTrue(seats.isNotEmpty(), "trieda $cls ma mat volne miesta")

        val section = j.stops.first().section
        for ((coach, seat) in seats) {
            val vehicle = assertNotNull(section.vehicle(coach), "vozen $coach")
            assertTrue(cls in vehicle.seatClasses, "vozen $coach nie je trieda $cls")
            assertTrue(
                assertNotNull(vehicle.decks.first().seat(seat)).free,
                "miesto $coach-$seat nie je volne",
            )
        }
    }

    @Test
    fun `pocet sedi s rozpadom po triedach`() {
        val j = journey()
        val cls = anyClassWithFreeSeats(j)
        val expected = j.availabilityByClass().first { it.seatClass == cls }.freeSeats
        assertEquals(expected, j.freeSeatsInClass(cls).size)
    }

    @Test
    fun `filter na pohodlie nikdy nevrati viac ako bez neho`() {
        val j = journey()
        val cls = anyClassWithFreeSeats(j)
        val all = j.freeSeatsInClass(cls, onlyComfortable = false)
        val comfy = j.freeSeatsInClass(cls, onlyComfortable = true)
        assertTrue(comfy.size <= all.size, "filter nemoze pridavat miesta")
        assertTrue(all.containsAll(comfy), "filtrovane miesta musia byt podmnozinou")
    }

    @Test
    fun `neznama trieda nevrati nic`() {
        assertTrue(journey().freeSeatsInClass("NEEXISTUJE").isEmpty())
    }

    @Test
    fun `uvolnene miesta triedy sa vymenuju s voznom aj miestom`() {
        val before = SeatSnapshot(emptyMap(), classFreeSeats = setOf("5-32"))
        val after = SeatSnapshot(emptyMap(), classFreeSeats = setOf("5-32", "3-11", "5-12"))
        val freed = SeatWatcher.classSeatsFreed(before, after)
        assertEquals(listOf("3-11", "5-12"), freed, "zoradene podla vozna a miesta")
        assertEquals(
            "vozeň 3, miesto 11; vozeň 5, miesto 12",
            SeatWatcher.describeClassSeats(freed),
        )
    }

    @Test
    fun `prve kolo nehlasi nic ani pri triede`() {
        val after = SeatSnapshot(emptyMap(), classFreeSeats = setOf("1-1", "2-2"))
        assertTrue(SeatWatcher.classSeatsFreed(null, after).isEmpty())
    }

    @Test
    fun `obsadenie miesta v triede nie je uvolnenie`() {
        val before = SeatSnapshot(emptyMap(), classFreeSeats = setOf("5-32", "5-33"))
        val after = SeatSnapshot(emptyMap(), classFreeSeats = setOf("5-32"))
        assertTrue(SeatWatcher.classSeatsFreed(before, after).isEmpty())
    }

    @Test
    fun `dlhy zoznam sa v texte skrati`() {
        val keys = (1..9).map { "5-$it" }
        val text = SeatWatcher.describeClassSeats(keys, max = 3)
        assertTrue(text.contains("+6 ďalších"), text)
    }

    @Test
    fun `sledovanie triedy ma vlastne id a popis`() {
        val base = WatchedTrip(
            date = "2026-08-14", fromId = 1, toId = 2,
            fromName = "A", toName = "B", routeId = "R1",
            departure = "07:41", coach = 0, seat = 0, seatClass = "C1",
        )
        assertTrue(base.isClassWatch)
        assertEquals("R1-class-C1", base.id)
        assertTrue(base.label.contains("C1"))
        // sledovanie miesta v tom istom spoji je iny zaznam
        assertTrue(base.id != base.copy(seatClass = null, coach = 6, seat = 32).id)
    }

    @Test
    fun `stary zaznam bez triedy sa nacita ako sledovanie miesta`() {
        // 9 poli = format pred pridanim triedy; uzivatel nesmie prist o sledovanie
        val old = "2026-08-14|372825000|1763018007|Praha|Košice|8551447568|07:41|6|32"
        val decoded = WatchedTripCodec.decode(old)
        assertEquals(1, decoded.size)
        assertTrue(!decoded.first().isClassWatch)
        assertEquals(32, decoded.first().seat)
    }

    @Test
    fun `sledovanie triedy prezije ulozenie a nacitanie`() {
        val trips = listOf(
            WatchedTrip(
                date = "2026-08-14", fromId = 1, toId = 2, fromName = "A", toName = "B",
                routeId = "R1", departure = "07:41", coach = 0, seat = 0,
                seatClass = "C1", onlyComfortable = true,
            ),
            WatchedTrip(
                date = "2026-08-14", fromId = 1, toId = 2, fromName = "A", toName = "B",
                routeId = "R1", departure = "07:41", coach = 6, seat = 32,
            ),
        )
        assertEquals(trips, WatchedTripCodec.decode(WatchedTripCodec.encode(trips)))
    }
}

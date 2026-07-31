package io.github.mangis14.rjchecker.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Tvar poziadavky na /routes/freeSeats.
 *
 * Toto je zistenie, ktore sa da lahko stratit: publikovany spec (SwaggerHub
 * 1.1.0) uvadza `tariffs` a `seatClass` ako povinne polia na najvyssej urovni,
 * ale nasadeny endpoint ich tak odmieta. Bez tohto testu by niekto "opravil"
 * telo podla specu a rozbil to.
 */
class RjClientTest {

    private val json = Json

    @Test
    fun `telo obsahuje sekciu a vnoreny seatPreference, nie tariffs na najvyssej urovni`() {
        val body = RjClient.freeSeatsBody(sectionId = 123, fromStationId = 456, toStationId = 789)
        val root = json.parseToJsonElement(body).jsonObject

        assertEquals(setOf("sections", "seatPreference"), root.keys)
        assertTrue("tariffs" !in root.keys, "tariffs na najvyssej urovni endpoint odmieta")
        assertTrue("seatClass" !in root.keys, "seatClass na najvyssej urovni endpoint odmieta")

        val section = root.getValue("sections").jsonArray.single().jsonObject
        assertEquals("123", section.getValue("sectionId").jsonPrimitive.content)
        assertEquals("456", section.getValue("fromStationId").jsonPrimitive.content)
        assertEquals("789", section.getValue("toStationId").jsonPrimitive.content)

        val pref = root.getValue("seatPreference").jsonObject
        assertEquals(listOf("REGULAR"), pref.getValue("tariffs").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `trieda miesta ide do seatPreference`() {
        val body = RjClient.freeSeatsBody(1, 2, 3, seatClass = "C0")
        val pref = json.parseToJsonElement(body).jsonObject.getValue("seatPreference").jsonObject
        assertEquals("C0", pref.getValue("seatClass").jsonPrimitive.content)
    }

    @Test
    fun `media type je verzovany`() {
        // s obycajnym application/json vracia endpoint HTTP 400 "Unexpected error"
        assertEquals("application/1.1.0+json", RjClient.VERSIONED_MEDIA_TYPE)
    }

    @Test
    fun `telo je platny json aj bez triedy`() {
        val body = RjClient.freeSeatsBody(8551447269L, 372825000L, 1763018007L)
        json.parseToJsonElement(body)      // nesmie hodit vynimku
        assertTrue(body.startsWith("{") && body.endsWith("}"))
    }
}

package io.github.mangis14.rjchecker.core

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pristup k zlatym fixtures - realnym odpovediam API a SVG layoutom voznov
 * odchytenym skriptom tools/capture_fixtures.py.
 *
 * Testy vdaka nim bezia bez siete aj bez Android SDK.
 */
object Fixtures {

    /** Adresar `fixtures` sa hlada smerom nahor, aby test nezavisel na tom, odkial ho pusti. */
    val root: File by lazy {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "fixtures")
            if (File(candidate, "layouts/index.json").isFile) return@lazy candidate
            dir = dir.parentFile
        }
        error("Adresar fixtures sa nenasiel. Spusti tools/capture_fixtures.py.")
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun text(relative: String): String = File(root, relative).readText()

    fun element(relative: String): JsonElement = json.parseToJsonElement(text(relative))

    /** Popis kazdeho odchyteneho layoutu: nazov vozna a miesta, ktore hlasi API. */
    data class LayoutCase(val file: String, val deckName: String, val seats: List<Int>)

    fun layoutCases(): List<LayoutCase> =
        element("layouts/index.json").jsonObject.map { (file, info) ->
            val obj = info.jsonObject
            LayoutCase(
                file = file,
                deckName = obj.getValue("deckName").jsonPrimitive.content,
                seats = obj.getValue("seats").jsonArray.map { it.jsonPrimitive.int },
            )
        }

    fun layoutSvg(file: String): String = text("layouts/$file")

    fun freeSeatsPraha(): String = text("freeSeats_praha.json")

    /** Obsadenost pre kazdu zastavku: id stanice -> odpoved /routes/freeSeats. */
    fun freeSeatsByStation(): Map<String, String> =
        element("freeSeats_byStation.json").jsonObject.mapValues { (_, v) -> v.toString() }

    fun meta(): JsonElement = element("meta.json")

    /** Zastavky spoja v geografickom poradi (id stanic), z meta.json. */
    fun stopOrder(): List<Long> =
        meta().jsonObject.getValue("stopStationIds").jsonArray
            .map { it.jsonPrimitive.content.toLong() }
}

private val kotlinx.serialization.json.JsonPrimitive.int: Int get() = content.toInt()

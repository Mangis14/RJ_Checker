package io.github.mangis14.rjchecker.core

import java.text.Normalizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parsovanie odpovede POST /routes/freeSeats.
 *
 * Odpoved obsahuje vsetky vozne naraz - preto jeden prechod zastavkami staci
 * na analyzu vlastneho miesta aj na odporucanie miesta v celom vlaku.
 */
object FreeSeatsParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Priznaky sa hladaju na texte bez diakritiky.
     *
     * RegioJet posiela seatConstraint v jazyku podla hlavicky X-Lang, takze
     * hladanie "rusiv" by na slovenskom "rusivych zariadeni" nesedelo. Odstranenie
     * diakritiky robi porovnanie nezavisle od nej.
     */
    private fun deaccent(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")

    internal fun flagsOf(constraint: String?): Set<SeatFlag> {
        if (constraint.isNullOrBlank()) return emptySet()
        val t = deaccent(constraint)
        val flags = mutableSetOf<SeatFlag>()
        if ("stol" in t) flags.add(SeatFlag.TABLE)
        if ("rusiv" in t) flags.add(SeatFlag.QUIET)
        if ("detsk" in t) flags.add(SeatFlag.CHILDREN)
        if ("zeny" in t || "zen " in t) flags.add(SeatFlag.WOMEN_ONLY)
        return flags
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString || it.content != "null" }
            ?.contentOrNullSafe()

    private fun JsonPrimitive.contentOrNullSafe(): String? =
        if (this.content == "null") null else this.content

    private fun JsonObject.int(key: String): Int? = str(key)?.toIntOrNull()

    private fun seatsOf(deck: JsonObject, key: String, free: Boolean): List<Seat> {
        val array = deck[key] as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element.jsonObject
            val index = obj.int("index") ?: return@mapNotNull null
            Seat(
                index = index,
                free = free,
                seatClass = obj.str("seatClass"),
                flags = flagsOf(obj.str("seatConstraint")),
            )
        }
    }

    /** Vrati prvy usek z odpovede, alebo null ak odpoved nie je pouzitelna. */
    fun parse(json: String): FreeSeatsSection? {
        val root = try {
            this.json.parseToJsonElement(json)
        } catch (e: Exception) {
            return null                      // chybova odpoved alebo nie JSON
        }
        val sections = root as? JsonArray ?: return null
        val section = sections.firstOrNull()?.jsonObject ?: return null

        val vehicles = (section["vehicles"] as? JsonArray ?: JsonArray(emptyList())).mapNotNull { v ->
            val vo = v.jsonObject
            val number = vo.int("number") ?: vo.int("vehicleNumber") ?: return@mapNotNull null
            val decks = (vo["decks"] as? JsonArray ?: JsonArray(emptyList())).mapNotNull { d ->
                val dobj = d.jsonObject
                val seats = seatsOf(dobj, "freeSeats", free = true) +
                    seatsOf(dobj, "occupiedSeats", free = false)
                if (seats.isEmpty()) return@mapNotNull null
                Deck(
                    number = dobj.int("number") ?: 1,
                    name = dobj.str("name") ?: "",
                    layoutUrl = dobj.str("layoutURL"),
                    seats = seats.distinctBy { it.index },
                )
            }
            if (decks.isEmpty()) return@mapNotNull null
            Vehicle(
                number = number,
                standard = vo.str("standard") ?: vo.str("vehicleStandardKey"),
                seatClasses = (vo["seatClasses"] as? JsonArray)?.mapNotNull {
                    when (it) {
                        is JsonPrimitive -> it.content
                        else -> it.jsonObject.str("name")
                    }
                } ?: emptyList(),
                decks = decks,
            )
        }
        if (vehicles.isEmpty()) return null

        return FreeSeatsSection(
            sectionId = section.str("sectionId")?.toLongOrNull() ?: 0L,
            vehicles = vehicles,
        )
    }
}

package io.github.mangis14.rjchecker.core

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Chyba z API. [status] je HTTP kod, ak nejaky prisiel - podla neho sa rozlisi
 * chyba poziadavky (4xx, opakovanie nepomoze) od vypadku.
 */
class RjApiException(message: String, val status: Int? = null) : IOException(message)

/** Jeden priamy spoj z vyhladavania. */
data class TrainOption(
    val routeId: String,
    val departure: String,        // HH:mm
    val arrival: String,          // HH:mm
    val departureIso: String,
    val freeSeats: Int,
)

/** Stanica - id pre API, nazov pre uzivatela. */
data class StationRef(val id: Long, val name: String)

/**
 * Ulozisko SVG layoutov voznov.
 *
 * Layout daneho typu vozna sa nemeni, ale ma cca 88 kB - bez cache by ho
 * kontrola na pozadi stahovala kazdych 15 minut znovu. Implementaciu dodava
 * volajuci (core modul sa nedotyka suboroveho systemu Androidu).
 */
interface LayoutStore {
    /** Vrati ulozeny layout, alebo null ak sa este nestahoval. */
    fun get(url: String): String?

    /** Ulozi stiahnuty layout pre dalsie pouzitie. */
    fun put(url: String, svg: String)
}

/**
 * Klient nad verejnym backendom RegioJetu - tym istym, z ktoreho cita ich web.
 *
 * Zamerne cez java.net, takze modul nema ziadnu sietovu zavislost a funguje aj
 * na Androide. Volania su blokujuce; volajuci ich ma spustat na IO vlakne.
 *
 * RegioJet ma aj oficialny Affiliate API (v1.1.0), ktory vyzaduje HTTP Basic
 * Auth - o pristup sa zada na developers@studentagency.cz. Tento klient cita
 * verejne zobrazovane data bez auth, iba na citanie.
 */
class RjClient(
    private val baseUrl: String = "https://brn-ybus-pubapi.sa.cz/restapi",
    private val lang: String = "sk",
    private val currency: String = "EUR",
    /** Dokumentacia uvadza APP pre mobilnu aplikaciu. */
    private val applicationOrigin: String = "APP",
    private val userAgent: String = "rjseat/1.0",
    /** cache layoutov; bez nej sa SVG vozna stahuje pri kazdom volani */
    private val layoutStore: LayoutStore? = null,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    companion object {
        /**
         * Content-Type aj Accept musia byt verzovane. S obycajnym
         * application/json vrati endpoint freeSeats HTTP 400 "Unexpected error".
         */
        const val VERSIONED_MEDIA_TYPE = "application/1.1.0+json"

        /**
         * Telo pre POST /routes/freeSeats.
         *
         * Publikovany spec (SwaggerHub 1.1.0) uvadza `tariffs` a `seatClass` ako
         * povinne polia na najvyssej urovni, ale nasadeny endpoint ich takto
         * odmieta ("request.body.json.property.unrecognized"). Realne ocakava
         * vnoreny `seatPreference` - rovnako, ako to posiela ich vlastny frontend.
         */
        fun freeSeatsBody(
            sectionId: Long,
            fromStationId: Long,
            toStationId: Long,
            seatClass: String? = null,
        ): String = buildString {
            append("""{"sections":[{"sectionId":""").append(sectionId)
            append(""","fromStationId":""").append(fromStationId)
            append(""","toStationId":""").append(toStationId)
            append("""}],"seatPreference":{"tariffs":["REGULAR"]""")
            if (seatClass != null) append(""","seatClass":"""").append(seatClass).append('"')
            append("}}")
        }
    }

    private fun request(method: String, path: String, query: Map<String, Any?> = emptyMap(), body: String? = null): String {
        val qs = query.entries
            .filter { it.value != null }
            .joinToString("&") { "${it.key}=${URLEncoder.encode(it.value.toString(), "UTF-8")}" }
        val url = URL(baseUrl + path + if (qs.isEmpty()) "" else "?$qs")

        var lastError: Exception? = null
        repeat(3) { attempt ->
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 20_000
                readTimeout = 45_000
                setRequestProperty("X-Lang", lang)
                setRequestProperty("X-Currency", currency)
                setRequestProperty("X-Application-Origin", applicationOrigin)
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", if (body != null) VERSIONED_MEDIA_TYPE else "application/json")
                if (body != null) {
                    setRequestProperty("Content-Type", VERSIONED_MEDIA_TYPE)
                    doOutput = true
                }
            }
            try {
                if (body != null) conn.outputStream.use { it.write(body.toByteArray()) }
                val status = conn.responseCode
                if (status in 200..299) {
                    return conn.inputStream.bufferedReader().use { it.readText() }
                }
                val detail = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                // 4xx okrem 429 su chyby requestu - opakovanie nepomoze
                if (status != 429 && status in 400..499) {
                    throw RjApiException("HTTP $status pri $path: ${detail.take(200)}", status)
                }
                lastError = RjApiException("HTTP $status pri $path", status)
            } catch (e: RjApiException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            } finally {
                conn.disconnect()
            }
            Thread.sleep(1000L * (attempt + 1))
        }
        throw RjApiException("Volanie $path zlyhalo: ${lastError?.message}")
    }

    /** Vlakove stanice v CZ a SK. */
    fun stations(): List<StationRef> {
        val root = json.parseToJsonElement(request("GET", "/consts/locations")).jsonArray
        val out = mutableListOf<StationRef>()
        for (country in root) {
            val code = (country.jsonObject["code"] as? JsonPrimitive)?.content
            if (code != "CZ" && code != "SK") continue
            for (city in country.jsonObject["cities"]?.jsonArray ?: JsonArray(emptyList())) {
                for (st in city.jsonObject["stations"]?.jsonArray ?: JsonArray(emptyList())) {
                    val o = st.jsonObject
                    val types = o["stationsTypes"]?.jsonArray?.map { (it as JsonPrimitive).content } ?: emptyList()
                    if ("TRAIN_STATION" !in types) continue
                    val id = (o["id"] as? JsonPrimitive)?.content?.toLongOrNull() ?: continue
                    val name = (o["fullname"] as? JsonPrimitive)?.content ?: continue
                    out.add(StationRef(id, name))
                }
            }
        }
        return out.sortedBy { it.name }
    }

    /** Kluc triedy -> nazov pre uzivatela, napr. C1 -> "Relax (2. tr.)". */
    fun seatClassTitles(): Map<String, String> {
        val root = json.parseToJsonElement(request("GET", "/consts/seatClasses")).jsonArray
        return root.mapNotNull {
            val o = it.jsonObject
            val key = (o["key"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            key to ((o["title"] as? JsonPrimitive)?.content ?: key)
        }.toMap()
    }

    /** Priame vlakove spoje v dany den. */
    fun directTrains(fromStationId: Long, toStationId: Long, date: String): List<TrainOption> {
        val body = request(
            "GET", "/routes/search/simple",
            mapOf(
                "tariffs" to "REGULAR",
                "fromLocationType" to "STATION", "fromLocationId" to fromStationId,
                "toLocationType" to "STATION", "toLocationId" to toStationId,
                "departureDate" to date,
            ),
        )
        val routes = json.parseToJsonElement(body).jsonObject["routes"]?.jsonArray ?: return emptyList()
        return routes.mapNotNull { r ->
            val o = r.jsonObject
            val transfers = (o["transfersCount"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
            if (transfers != 0) return@mapNotNull null
            val types = o["vehicleTypes"]?.jsonArray?.map { (it as JsonPrimitive).content } ?: emptyList()
            if ("TRAIN" !in types) return@mapNotNull null
            val dep = (o["departureTime"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            if (!dep.startsWith(date)) return@mapNotNull null
            val arr = (o["arrivalTime"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            TrainOption(
                routeId = (o["id"] as? JsonPrimitive)?.content ?: return@mapNotNull null,
                departure = dep.substring(11, 16),
                arrival = arr.substring(11, 16),
                departureIso = dep,
                freeSeats = (o["freeSeatsCount"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
            )
        }
    }

    /** Detail useku - sectionId, kod vlaku a realny cas odjazdu z danej stanice. */
    fun routeDetail(routeId: String, fromStationId: Long, toStationId: Long): JsonObject =
        json.parseToJsonElement(
            request(
                "GET", "/routes/$routeId/simple",
                mapOf(
                    "routeId" to routeId, "fromStationId" to fromStationId,
                    "toStationId" to toStationId, "tariffs" to "REGULAR",
                ),
            ),
        ).jsonObject

    fun freeSeats(sectionId: Long, fromStationId: Long, toStationId: Long, seatClass: String? = null): FreeSeatsSection? =
        FreeSeatsParser.parse(
            request(
                "POST", "/routes/freeSeats",
                body = freeSeatsBody(sectionId, fromStationId, toStationId, seatClass),
            ),
        )

    /** Zakladny cestovny poriadok vsetkych liniek. Velky, preto sa cachuje. */
    fun timetables(): JsonArray = json.parseToJsonElement(request("GET", "/consts/timetables")).jsonArray

    /**
     * SVG layout vozna - z neho sa odvodzuje topologia miest.
     *
     * Cita sa najprv z cache: layout typu vozna sa nemeni, ale ma cca 88 kB,
     * co je najvacsia jednotliva polozka jednej kontroly na pozadi.
     */
    fun layoutSvg(url: String): String? {
        layoutStore?.get(url)?.let { return it }
        val downloaded = try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 45_000
                setRequestProperty("User-Agent", userAgent)
            }
            try {
                if (conn.responseCode in 200..299) {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } else {
                    null
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            null
        }
        if (downloaded != null) layoutStore?.put(url, downloaded)
        return downloaded
    }
}

package io.github.mangis14.rjchecker.core

/**
 * Priznak miesta odvodeny z pola `seatConstraint`, ktore posiela RegioJet.
 *
 * Ide o jedinu metadatovu vrstvu, ktoru API o miestach dava - suradnice ani
 * topologiu neposiela vobec, tie sa musia odvodit z SVG layoutu vozna.
 */
enum class SeatFlag { TABLE, QUIET, CHILDREN, WOMEN_ONLY }

/** Jedno miesto vo vozni pre konkretny usek cesty. */
data class Seat(
    val index: Int,
    val free: Boolean,
    val seatClass: String? = null,
    val flags: Set<SeatFlag> = emptySet(),
)

data class Deck(
    val number: Int,
    val name: String,
    val layoutUrl: String?,
    val seats: List<Seat>,
) {
    val freeSeats: List<Int> get() = seats.filter { it.free }.map { it.index }

    fun seat(index: Int): Seat? = seats.firstOrNull { it.index == index }
}

data class Vehicle(
    val number: Int,
    val standard: String?,
    val seatClasses: List<String>,
    val decks: List<Deck>,
)

/** Odpoved POST /routes/freeSeats pre jeden usek - obsahuje vsetky vozne naraz. */
data class FreeSeatsSection(
    val sectionId: Long,
    val vehicles: List<Vehicle>,
) {
    fun vehicle(number: Int): Vehicle? = vehicles.firstOrNull { it.number == number }
}

/** Nakolko sa da odvodene susedstvo brat ako iste. */
enum class Confidence { CERTAIN, UNCERTAIN }

/**
 * Typ sedadla podla toho, s kolkymi miestami ho delis.
 *
 * Odvodzuje sa z topologie vozna, nie z API. Priznak "miesto pri stoliku"
 * posiela RegioJet len pri vozni Astra a aj tam len ako upozornenie na chybajucu
 * obrazovku, takze ako vseobecny marker stolika sa pouzit neda.
 */
enum class SeatKind {
    /** samostatne miesto, nikto vedla (napr. strana 1 vo vozni Relax 2+1) */
    SINGLE,

    /** dvojica vedla seba */
    PAIR,

    /** stvorica otocena k sebe - v tomto priestore je zvycajne stolik */
    TABLE_QUAD,

    /** kupe pre 5 a viac */
    COMPARTMENT,

    UNKNOWN,
}

/** Odkial sa vzalo rozdelenie na oddiely. */
enum class BaySource { SEAT_NUMBERING, ROW_GEOMETRY, NONE }

/**
 * Susedstvo miesta.
 *
 * @param bay cely oddiel vratane seba - kupe, alebo pri radovom sedeni dvojica
 * @param nextTo miesta na tej istej lavici / v tom istom rade
 * @param facing miesto priamo oproti; null znamena "neexistuje alebo nezname"
 */
data class Neighbours(
    val seat: Int,
    val bay: List<Int>,
    val nextTo: List<Int>,
    val facing: Int?,
) {
    /** Susedia od najblizsieho: vedla, potom oproti, potom zvysok oddielu. */
    val ordered: List<Int>
        get() = (nextTo + listOfNotNull(facing) + bay.filter { it != seat }).distinct()
}

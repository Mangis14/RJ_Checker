package io.github.mangis14.rjchecker

import io.github.mangis14.rjchecker.core.Deck
import io.github.mangis14.rjchecker.core.FreeSeatsSection
import io.github.mangis14.rjchecker.core.Journey
import io.github.mangis14.rjchecker.core.JourneyLoader
import io.github.mangis14.rjchecker.core.JourneyStop
import io.github.mangis14.rjchecker.core.RjClient
import io.github.mangis14.rjchecker.core.SeatAnalysis
import io.github.mangis14.rjchecker.core.SeatPick
import io.github.mangis14.rjchecker.core.StationRef
import io.github.mangis14.rjchecker.core.TrainOption
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Krok, v ktorom sa uzivatel nachadza. */
enum class Step { PICK_TRIP, PICK_TRAIN, PICK_SEAT, RESULT }

data class UiState(
    val step: Step = Step.PICK_TRIP,
    val busy: Boolean = false,
    val progress: String? = null,
    val error: String? = null,

    val stations: List<StationRef> = emptyList(),
    val date: String = LocalDate.now().toString(),
    val from: StationRef? = null,
    val to: StationRef? = null,

    val trains: List<TrainOption> = emptyList(),
    val train: TrainOption? = null,

    /** Obsadenost pre cely usek - staci na vyber vozna a miesta (1 volanie). */
    val quickSection: FreeSeatsSection? = null,
    val coach: Int? = null,
    val seat: Int? = null,

    val analysis: SeatAnalysis? = null,
    val recommendations: List<SeatPick> = emptyList(),
    val seatClassTitles: Map<String, String> = emptyMap(),
    val watching: Boolean = false,
    /** true = plna analyza (vsetky zastavky) uz prebehla */
    val fullScanDone: Boolean = false,
)

class SeatViewModel(app: Application) : AndroidViewModel(app) {

    private val client = RjClient()
    private val prefs = TripPrefs(app)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var journey: Journey? = null
    private var stationNames: Map<Long, String> = emptyMap()

    init {
        loadStations()
    }

    private fun fail(e: Throwable) = _state.update {
        it.copy(busy = false, progress = null, error = e.message ?: e.toString())
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    private fun loadStations() = viewModelScope.launch {
        _state.update { it.copy(busy = true) }
        runCatching {
            withContext(Dispatchers.IO) { client.stations() to client.seatClassTitles() }
        }.onSuccess { (stations, titles) ->
            stationNames = stations.associate { it.id to it.name }
            val saved = prefs.load()
            _state.update {
                it.copy(
                    busy = false,
                    stations = stations,
                    seatClassTitles = titles,
                    // Praha hl.n. -> Kosice zst. ako rozumny default
                    from = stations.firstOrNull { s -> s.id == (saved?.fromId ?: 372825000L) }
                        ?: stations.firstOrNull { s -> s.id == 372825000L },
                    to = stations.firstOrNull { s -> s.id == (saved?.toId ?: 1763018007L) }
                        ?: stations.firstOrNull { s -> s.id == 1763018007L },
                    date = saved?.date ?: it.date,
                    watching = saved != null,
                )
            }
        }.onFailure { fail(it) }
    }

    fun setDate(date: String) = _state.update { it.copy(date = date) }
    fun setFrom(station: StationRef) = _state.update { it.copy(from = station) }
    fun setTo(station: StationRef) = _state.update { it.copy(to = station) }
    fun back() = _state.update {
        when (it.step) {
            Step.RESULT -> it.copy(step = Step.PICK_SEAT, analysis = null, recommendations = emptyList())
            Step.PICK_SEAT -> it.copy(step = Step.PICK_TRAIN, coach = null, seat = null)
            Step.PICK_TRAIN -> it.copy(step = Step.PICK_TRIP, train = null)
            Step.PICK_TRIP -> it
        }
    }

    fun searchTrains() = viewModelScope.launch {
        val s = _state.value
        val from = s.from ?: return@launch
        val to = s.to ?: return@launch
        _state.update { it.copy(busy = true, error = null, progress = "hladam spoje") }
        runCatching {
            withContext(Dispatchers.IO) { client.directTrains(from.id, to.id, s.date) }
        }.onSuccess { trains ->
            _state.update {
                if (trains.isEmpty()) {
                    it.copy(busy = false, progress = null, error = "V ten den nejde priamy vlak.")
                } else {
                    it.copy(busy = false, progress = null, trains = trains, step = Step.PICK_TRAIN)
                }
            }
        }.onFailure { fail(it) }
    }

    /**
     * Rychla faza: obsadenost pre cely usek (1 volanie). Staci na vyber vozna
     * a miesta, takze uzivatel necaka na prechod vsetkymi zastavkami.
     */
    fun selectTrain(train: TrainOption) = viewModelScope.launch {
        val s = _state.value
        val from = s.from ?: return@launch
        val to = s.to ?: return@launch
        _state.update { it.copy(busy = true, error = null, progress = "nacitavam suparvu", train = train) }
        runCatching {
            withContext(Dispatchers.IO) {
                val loader = JourneyLoader(client, stationNames)
                // len vychodzia stanica - rychle
                val quick = loader.load(
                    routeId = train.routeId,
                    fromStationId = from.id,
                    toStationId = to.id,
                    date = s.date,
                    departure = train.departure,
                    pauseMillis = 0,
                    onProgress = { _, _ -> },
                    firstStopOnly = true,
                )
                quick
            }
        }.onSuccess { quick ->
            journey = quick
            _state.update {
                it.copy(
                    busy = false, progress = null, step = Step.PICK_SEAT,
                    quickSection = quick.stops.first().section,
                    fullScanDone = false,
                )
            }
        }.onFailure { fail(it) }
    }

    fun selectCoach(number: Int) = _state.update { it.copy(coach = number, seat = null) }

    /**
     * Pomala faza: prechod vsetkymi zastavkami, aby sa dalo povedat, od ktorej
     * stanice sa ktore susedne miesto uvolni.
     */
    fun selectSeat(seat: Int) = viewModelScope.launch {
        val s = _state.value
        val coach = s.coach ?: return@launch
        val from = s.from ?: return@launch
        val to = s.to ?: return@launch
        val train = s.train ?: return@launch

        _state.update {
            it.copy(seat = seat, step = Step.RESULT, busy = true, progress = "citam vozen")
        }

        // Pozor: analyseSeat si dotahuje SVG layout vozna, takze MUSI bezat na IO -
        // na main threade by to skoncilo NetworkOnMainThreadException.
        val quickAnalysis = withContext(Dispatchers.IO) {
            runCatching { journey?.analyseSeat(coach, seat) }.getOrNull()
        }
        _state.update { it.copy(analysis = quickAnalysis, progress = "zistujem zastavky") }

        runCatching {
            withContext(Dispatchers.IO) {
                val loader = JourneyLoader(client, stationNames)
                val full = loader.load(
                    routeId = train.routeId,
                    fromStationId = from.id,
                    toStationId = to.id,
                    date = s.date,
                    departure = train.departure,
                    onProgress = { done, total ->
                        _state.update { st -> st.copy(progress = "zastavky $done/$total") }
                    },
                )
                val analysis = full.analyseSeat(coach, seat)
                val seatClass = full.stops.first().section.vehicle(coach)
                    ?.seatClasses?.firstOrNull()
                val picks = full.recommend(seatClass, limit = 8)
                Triple(full, analysis, picks)
            }
        }.onSuccess { (full, analysis, picks) ->
            journey = full
            _state.update {
                it.copy(
                    busy = false, progress = null, analysis = analysis,
                    recommendations = picks, fullScanDone = true,
                )
            }
            analysis?.let {
                prefs.saveSnapshot(
                    it.neighbours.associate { n -> n.seat to n.freeWholeWay },
                    it.freeInCoach,
                )
            }
        }.onFailure { fail(it) }
    }

    fun toggleWatching() {
        val s = _state.value
        val app = getApplication<Application>()
        if (s.watching) {
            WatchWorker.cancel(app)
            prefs.clear()
            _state.update { it.copy(watching = false) }
            return
        }
        val from = s.from ?: return
        val to = s.to ?: return
        val train = s.train ?: return
        val coach = s.coach ?: return
        val seat = s.seat ?: return
        prefs.save(
            WatchedTrip(
                date = s.date, fromId = from.id, toId = to.id,
                fromName = from.name, toName = to.name,
                routeId = train.routeId, departure = train.departure,
                coach = coach, seat = seat,
            ),
        )
        s.analysis?.let {
            prefs.saveSnapshot(it.neighbours.associate { n -> n.seat to n.freeWholeWay }, it.freeInCoach)
        }
        WatchWorker.schedule(app)
        _state.update { it.copy(watching = true) }
    }

    fun decksOf(coach: Int): Deck? =
        _state.value.quickSection?.vehicle(coach)?.decks?.firstOrNull()

    fun stopsOfJourney(): List<JourneyStop> = journey?.stops.orEmpty()
}

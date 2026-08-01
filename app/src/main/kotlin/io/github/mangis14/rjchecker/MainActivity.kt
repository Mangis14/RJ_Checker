package io.github.mangis14.rjchecker

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.mangis14.rjchecker.core.ComfortSummary
import io.github.mangis14.rjchecker.core.Confidence
import io.github.mangis14.rjchecker.core.SeatFlag
import io.github.mangis14.rjchecker.core.SeatKind
import io.github.mangis14.rjchecker.core.StationRef
import java.time.LocalDate
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

/** Minimalna velkost prvku na dotyk - pod 48 dp sa na mobile mieri tazko. */
private val TapTarget = 48.dp

class MainActivity : ComponentActivity() {

    private val askNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* bez povolenia appka funguje, len nenotifikuje */ }

    /**
     * Vozen a miesto z notifikacie. Drzi sa v state, aby to fungovalo aj ked uz
     * appka bezi - vtedy pride intent do onNewIntent, nie do onCreate.
     */
    private var fromNotification by mutableStateOf<Pair<Int, Int>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        fromNotification = readNotificationTarget(intent)
        setContent {
            RjSeatTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    RjSeatApp(
                        openFromNotification = fromNotification,
                        onNotificationHandled = { fromNotification = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readNotificationTarget(intent)?.let { fromNotification = it }
    }

    private fun readNotificationTarget(intent: Intent?): Pair<Int, Int>? {
        val coach = intent?.getIntExtra(WatchWorker.EXTRA_COACH, -1) ?: -1
        val seat = intent?.getIntExtra(WatchWorker.EXTRA_SEAT, -1) ?: -1
        return if (coach > 0 && seat > 0) coach to seat else null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RjSeatApp(
    vm: SeatViewModel = viewModel(),
    openFromNotification: Pair<Int, Int>? = null,
    onNotificationHandled: () -> Unit = {},
) {
    val state by vm.state.collectAsState()

    // Klepnutie na notifikaciu skoci priamo na analyzu sledovaneho miesta.
    LaunchedEffect(openFromNotification) {
        openFromNotification?.let { (coach, seat) ->
            vm.openWatchedTrip(coach, seat)
            onNotificationHandled()
        }
    }

    // Systemove tlacitko / gesto "spat" vracia o krok vzad. Na prvej obrazovke
    // sa nechava povodne chovanie, teda odchod z appky.
    BackHandler(enabled = state.step != Step.PICK_TRIP) { vm.back() }

    state.pendingOccupiedSeat?.let { seat ->
        AlertDialog(
            onDismissRequest = { vm.dismissOccupiedSeat() },
            title = { Text("Miesto $seat je obsadené") },
            text = {
                Text(
                    "Je to tvoje už zakúpené miesto? Ak áno, môžem sledovať, kto sedí " +
                        "okolo teba a od ktorej stanice sa susedné miesto uvoľní.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(
                    onClick = { vm.confirmOccupiedSeat() },
                    modifier = Modifier.heightIn(min = TapTarget),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = RjYellow,
                        contentColor = RjInk,
                    ),
                ) { Text("Áno, je moje") }
            },
            dismissButton = {
                TextButton(
                    onClick = { vm.dismissOccupiedSeat() },
                    modifier = Modifier.heightIn(min = TapTarget),
                ) { Text("Zrušiť") }
            },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = RjYellow,
                    titleContentColor = RjInk,
                    navigationIconContentColor = RjInk,
                ),
                navigationIcon = {
                    if (state.step != Step.PICK_TRIP) {
                        IconButton(onClick = { vm.back() }, modifier = Modifier.size(TapTarget)) {
                            Text("‹", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                },
                title = {
                    Column {
                        Text(stepTitle(state.step), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        stepSubtitle(state)?.let {
                            Text(it, fontSize = 12.sp, color = RjInk.copy(alpha = 0.7f))
                        }
                    }
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 16.dp),
        ) {
            state.error?.let { message ->
                Card(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(message, style = MaterialTheme.typography.bodyMedium)
                        TextButton(
                            onClick = { vm.dismissError() },
                            modifier = Modifier.heightIn(min = TapTarget),
                        ) { Text("Zavrieť") }
                    }
                }
            }

            if (state.busy) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        state.progress ?: "pracujem…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (state.step) {
                    Step.PICK_TRIP -> PickTrip(state, vm)
                    Step.PICK_TRAIN -> PickTrain(state, vm)
                    Step.PICK_SEAT -> PickSeat(state, vm)
                    Step.RESULT -> ResultScreen(state, vm)
                }
            }
        }
    }
}

private fun stepTitle(step: Step) = when (step) {
    Step.PICK_TRIP -> "Kam cestuješ?"
    Step.PICK_TRAIN -> "Vyber spoj"
    Step.PICK_SEAT -> "Vyber miesto"
    Step.RESULT -> "Tvoje miesto"
}

private fun stepSubtitle(state: UiState): String? = when (state.step) {
    Step.PICK_TRIP -> null
    Step.PICK_TRAIN -> "${state.from?.name ?: ""} → ${state.to?.name ?: ""}"
    Step.PICK_SEAT, Step.RESULT -> state.train?.let { "${it.departure} – ${it.arrival}" }
}

// ---------------------------------------------------------------- 1. trasa

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StationPicker(
    label: String,
    selected: StationRef?,
    options: List<StationRef>,
    onPick: (StationRef) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, options) {
        if (query.isBlank()) options.take(50)
        else options.filter { it.name.contains(query, ignoreCase = true) }.take(50)
    }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = if (expanded) query else (selected?.name ?: ""),
            onValueChange = { query = it; expanded = true },
            label = { Text(label) },
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .menuAnchor(MenuAnchorType.PrimaryEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            filtered.forEach { station ->
                DropdownMenuItem(
                    text = { Text(station.name, style = MaterialTheme.typography.bodyLarge) },
                    modifier = Modifier.heightIn(min = TapTarget),
                    onClick = { onPick(station); expanded = false; query = "" },
                )
            }
        }
    }
}

@Composable
private fun PickTrip(state: UiState, vm: SeatViewModel) {
    val today = remember { LocalDate.now() }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Sledovane spoje sa daju otvorit jednym klepnutim - netreba znovu
        // prechadzat vyber trasy, spoja a miesta. Moze ich byt viac naraz
        // (cesta tam aj spat, alebo dva kandidatske vlaky).
        if (state.watchedTrips.isNotEmpty()) {
            item {
                Text(
                    if (state.watchedTrips.size == 1) "Sleduješ" else "Sleduješ ${state.watchedTrips.size} spoje",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            items(state.watchedTrips, key = { it.id }) { trip ->
                Card(
                    Modifier
                        .fillMaxWidth()
                        .clickable { vm.openWatchedTrip(trip.coach, trip.seat, trip.id) },
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = RjYellow),
                ) {
                    Row(
                        Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp)
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                trip.label,
                                style = MaterialTheme.typography.titleMedium,
                                color = RjInk,
                            )
                            Text(
                                "${trip.fromName} → ${trip.toName} · ${trip.date} ${trip.departure}",
                                style = MaterialTheme.typography.bodySmall,
                                color = RjInk.copy(alpha = 0.8f),
                            )
                        }
                        TextButton(
                            onClick = { vm.stopWatching(trip.id) },
                            modifier = Modifier.heightIn(min = TapTarget),
                        ) { Text("Zrušiť", color = RjInk) }
                    }
                }
            }
            item { HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)) }
        }

        item { StationPicker("Odkiaľ", state.from, state.stations) { vm.setFrom(it) } }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = { vm.swapStations() },
                    modifier = Modifier.heightIn(min = TapTarget),
                ) { Text("⇅  Otočiť smer") }
            }
        }
        item { StationPicker("Kam", state.to, state.stations) { vm.setTo(it) } }

        item {
            Text(
                "Dátum",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        item {
            // vodorovny zoznam - na mobile sa listuje prstom lahsie ako mriezka
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items((0..27).toList()) { offset ->
                    val date = today.plusDays(offset.toLong())
                    DateChip(
                        date = date,
                        selected = state.date == date.toString(),
                        onClick = { vm.setDate(date.toString()) },
                    )
                }
            }
        }

        item {
            Button(
                onClick = { vm.searchTrains() },
                enabled = !state.busy && state.from != null && state.to != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(top = 8.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = RjYellow, contentColor = RjInk),
            ) { Text("Nájdi spoje", style = MaterialTheme.typography.labelLarge, fontSize = 17.sp) }
        }
    }
}

@Composable
private fun DateChip(date: LocalDate, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) RjYellow else MaterialTheme.colorScheme.surface
    val fg = if (selected) RjInk else MaterialTheme.colorScheme.onSurface
    Column(
        Modifier
            .width(62.dp)
            .heightIn(min = 64.dp)
            .background(bg, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            date.dayOfWeek.getDisplayName(JavaTextStyle.SHORT, Locale("sk")).lowercase(),
            fontSize = 11.sp,
            color = fg.copy(alpha = 0.75f),
        )
        Text(
            "${date.dayOfMonth}.${date.monthValue}.",
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = fg,
        )
    }
}

// ---------------------------------------------------------------- 2. spoj

@Composable
private fun PickTrain(state: UiState, vm: SeatViewModel) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(state.trains) { train ->
            Card(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !state.busy) { vm.selectTrain(train) },
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${train.departure} – ${train.arrival}",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${train.freeSeats} voľných",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    Spacer(Modifier.height(10.dp))
                    ComfortRow(state.comfort[train.routeId], state.comfortLoading, state, train, vm)
                }
            }
        }
    }
}

/** Odporucenie pohodlia priamo v zozname spojov. */
@Composable
private fun ComfortRow(
    comfort: ComfortSummary?,
    loading: Boolean,
    state: UiState,
    train: io.github.mangis14.rjchecker.core.TrainOption,
    vm: SeatViewModel,
) {
    if (comfort == null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                if (loading) "zisťujem pohodlie…" else "pohodlie sa nezistilo",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (comfort.emptyCompartments > 0) {
                Badge("${comfort.emptyCompartments}× prázdne kupé", RjSeatFree)
            }
            if (comfort.emptyPairs > 0) {
                Badge("${comfort.emptyPairs}× prázdna dvojica", RjYellowDim)
            }
            if (comfort.emptyCompartments == 0 && comfort.emptyPairs == 0) {
                Badge("nikde celý oddiel voľný", RjSeatTaken)
            }
        }
        // Rozpad volnych miest po triedach ako farebne tagy - trieda sa da
        // rovno zacat sledovat, bez vyberu vozna a miesta.
        if (comfort.byClass.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            comfort.byClass.forEach { row ->
                val style = classStyle(row.seatClass, state.seatClassTitles[row.seatClass])
                val kinds = row.byKind.entries
                    .sortedByDescending { it.value }
                    .joinToString(", ") { "${it.value}× ${kindShort(it.key)}" }
                val watched = state.watchedTrips.any {
                    it.routeId == train.routeId && it.seatClass == row.seatClass
                }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ClassTag(style)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${row.freeSeats} voľných",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                        )
                        if (kinds.isNotEmpty()) {
                            Text(
                                kinds,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    TextButton(
                        onClick = { vm.toggleClassWatch(train, row.seatClass, onlyComfortable = true) },
                        modifier = Modifier.heightIn(min = TapTarget),
                    ) { Text(if (watched) "Sledujem" else "Sledovať", fontSize = 13.sp) }
                }
            }
        }
        comfort.best?.let { best ->
            Spacer(Modifier.height(8.dp))
            Text(
                "najpokojnejšie: vozeň ${best.coach}, miesto ${best.seat}" +
                    if (best.takenNeighbours.isEmpty()) " – celý oddiel voľný" else "",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .background(color.copy(alpha = 0.22f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

// ---------------------------------------------------------------- 3. miesto

@Composable
private fun PickSeat(state: UiState, vm: SeatViewModel) {
    val coach = state.coach
    if (coach == null) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.quickSection?.vehicles?.sortedBy { it.number } ?: emptyList()) { vehicle ->
                val deck = vehicle.decks.first()
                val free = deck.seats.count { it.free }
                Card(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 72.dp)
                        .clickable { vm.selectCoach(vehicle.number) },
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                ) {
                    Row(
                        Modifier.padding(16.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(38.dp).background(RjYellow, RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "${vehicle.number}",
                                color = RjInk,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                vehicle.seatClasses.forEach { key ->
                                    ClassTag(classStyle(key, state.seatClassTitles[key]))
                                }
                            }
                            Text(
                                deck.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        Text(
                            "$free/${deck.seats.size}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (free > 0) RjSeatFree else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    } else {
        val deck = vm.decksOf(coach)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 58.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(bottom = 12.dp)) {
                    Text("Vozeň $coach", style = MaterialTheme.typography.titleMedium)
                    Row(
                        Modifier.padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        LegendDot(RjSeatFree, "voľné")
                        LegendDot(RjSeatTaken, "obsadené")
                    }
                    Text(
                        "Máš už kúpené miesto? Klepni na svoje – aj keď je obsadené.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            items(deck?.seats?.sortedBy { it.index } ?: emptyList()) { seat ->
                Box(
                    Modifier
                        .padding(4.dp)
                        .heightIn(min = TapTarget)
                        .background(
                            if (seat.free) RjSeatFree else RjSeatTaken,
                            RoundedCornerShape(10.dp),
                        )
                        // Obsadene miesto sa NEblokuje - typicky je to vlastne
                        // zakupene miesto, len sa najprv spyta.
                        .clickable {
                            if (seat.free) vm.selectSeat(seat.index)
                            else vm.askAboutOccupiedSeat(seat.index)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${seat.index}",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).background(color, RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(5.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------- 4. vysledok

@Composable
private fun ResultScreen(state: UiState, vm: SeatViewModel) {
    val analysis = state.analysis
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (analysis == null) {
            item { Text("Analýza sa nepodarila.", style = MaterialTheme.typography.bodyMedium) }
        } else {
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = RjYellow),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "Vozeň ${analysis.coach} · miesto ${analysis.seat}",
                            style = MaterialTheme.typography.titleLarge,
                            color = RjInk,
                        )
                        Text(
                            "${analysis.coachName} · oddiel ${analysis.bay.joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = RjInk.copy(alpha = 0.8f),
                        )
                    }
                }
                if (analysis.confidence == Confidence.UNCERTAIN) {
                    Text(
                        "Susedstvo je neisté – layout tohto vozňa sa nedá prečítať spoľahlivo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = RjWarn,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            item {
                Text("Kto je okolo teba", style = MaterialTheme.typography.titleMedium)
                if (!state.fullScanDone) {
                    Text(
                        "zisťujem, od ktorej stanice sa miesta uvoľnia…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(analysis.neighbours) { n ->
                val freesAt = n.freesAt
                val status = when {
                    n.freeWholeWay -> "voľné celú cestu"
                    freesAt != null -> "uvoľní sa v ${freesAt.stationName} (${freesAt.departure})"
                    state.fullScanDone -> "obsadené po celej trase"
                    else -> "obsadené"
                }
                val accent = when {
                    n.freeWholeWay -> RjSeatFree
                    freesAt != null -> RjYellowDim
                    else -> RjSeatTaken
                }
                Card(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                ) {
                    Row(
                        Modifier.padding(14.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(34.dp).background(accent, RoundedCornerShape(9.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "${n.seat}",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                n.relation + (
                                    if (n.flags.isEmpty()) ""
                                    else " · " + n.flags.joinToString(", ") { flagLabel(it) }
                                    ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(status, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            item {
                Text(
                    "Vo vozni voľných ${analysis.freeInCoach} z ${analysis.coachTotal}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.recommendations.isNotEmpty()) {
                    Text(
                        "Pokojnejšie miesta",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }
            }
            items(state.recommendations) { pick ->
                val emptyFrom = pick.emptyFrom
                val status = when {
                    pick.takenNeighbours.isEmpty() -> "celé voľné celú cestu"
                    emptyFrom != null -> "prázdne od ${emptyFrom.stationName} (${emptyFrom.departure})"
                    else -> "voľné ${pick.freeNeighbours.size} z ${pick.bay.size - 1}"
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = TapTarget)
                        .clickable { vm.jumpToSeat(pick.coach, pick.seat) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "vozeň ${pick.coach} · miesto ${pick.seat}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            seatKindLabel(pick.kind, pick.confidence) +
                                pick.bay.joinToString(", ") + " · " + status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text("›", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            item {
                Button(
                    onClick = { vm.toggleWatching() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .padding(top = 14.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.watching) RjInk else RjYellow,
                        contentColor = if (state.watching) Color.White else RjInk,
                    ),
                ) {
                    Text(
                        if (state.watching) "Prestať sledovať" else "Sledovať toto miesto",
                        style = MaterialTheme.typography.labelLarge,
                        fontSize = 16.sp,
                    )
                }
                Text(
                    "Na pozadí kontroluje zmeny každých 15 minút a pošle notifikáciu, keď sa " +
                        "susedné miesto uvoľní alebo obsadí.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/**
 * Popisok typu sedadla. Pri neistej topologii (Relax Bm3xx) sa typ NEUVADZA -
 * radsej chybajuci detail ako vymysleny.
 */
private fun seatKindLabel(kind: SeatKind, confidence: Confidence): String {
    if (confidence == Confidence.UNCERTAIN) return "miesta "
    return when (kind) {
        SeatKind.SINGLE -> "samostatné "
        SeatKind.PAIR -> "dvojica "
        SeatKind.TABLE_QUAD -> "štvorica so stolíkom "
        SeatKind.COMPARTMENT -> "kupé "
        SeatKind.UNKNOWN -> "miesta "
    }
}

/** Farebny tag triedy - vyrazny a citatelny na prvy pohlad. */
@Composable
private fun ClassTag(style: ClassStyle) {
    Text(
        style.label,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.SemiBold,
        color = style.foreground,
        modifier = Modifier
            .background(style.background, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

private fun kindShort(kind: SeatKind): String = when (kind) {
    SeatKind.SINGLE -> "samostatné"
    SeatKind.PAIR -> "dvojica"
    SeatKind.TABLE_QUAD -> "stolík"
    SeatKind.COMPARTMENT -> "kupé"
    SeatKind.UNKNOWN -> "ostatné"
}

private fun flagLabel(flag: SeatFlag): String = when (flag) {
    SeatFlag.TABLE -> "pri stolíku"
    SeatFlag.QUIET -> "tiché kupé"
    SeatFlag.CHILDREN -> "detské kupé"
    SeatFlag.WOMEN_ONLY -> "len ženy"
}

package io.github.mangis14.rjchecker

import io.github.mangis14.rjchecker.core.Confidence
import io.github.mangis14.rjchecker.core.SeatFlag
import io.github.mangis14.rjchecker.core.StationRef
import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import java.time.LocalDate

class MainActivity : ComponentActivity() {

    private val askNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* bez povolenia appka funguje, len nenotifikuje */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RjSeatApp()
                }
            }
        }
    }
}

@Composable
fun RjSeatApp(vm: SeatViewModel = viewModel()) {
    val state by vm.state.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("RJ Seat", fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Text(
            "Kto sedi okolo teba a od ktorej stanice sa miesto uvolni",
            fontSize = 12.sp,
            color = Color.Gray,
        )
        Spacer(Modifier.height(12.dp))

        state.error?.let { message ->
            Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(message, fontSize = 13.sp)
                    TextButton(onClick = { vm.dismissError() }) { Text("Zavriet") }
                }
            }
        }

        if (state.busy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(state.progress ?: "pracujem...", fontSize = 13.sp)
            }
            Spacer(Modifier.height(8.dp))
        }

        // Kazda obrazovka ma vlastny scrollovaci koren a dostane zvysny priestor -
        // vnorene scrollovanie by v Compose padalo na neohranicenu vysku.
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
        if (query.isBlank()) options.take(40)
        else options.filter { it.name.contains(query, ignoreCase = true) }.take(40)
    }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = if (expanded) query else (selected?.name ?: ""),
            onValueChange = { query = it; expanded = true },
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            filtered.forEach { station ->
                DropdownMenuItem(
                    text = { Text(station.name) },
                    onClick = { onPick(station); expanded = false; query = "" },
                )
            }
        }
    }
}

@Composable
private fun PickTrip(state: UiState, vm: SeatViewModel) {
    val today = remember { LocalDate.now() }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            StationPicker("Odkial", state.from, state.stations) { vm.setFrom(it) }
            Spacer(Modifier.height(8.dp))
            StationPicker("Kam", state.to, state.stations) { vm.setTo(it) }
            Spacer(Modifier.height(12.dp))
            Text("Datum", fontSize = 13.sp, color = Color.Gray)
        }
        items((0..13).chunked(4)) { chunk ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                chunk.forEach { offset ->
                    val date = today.plusDays(offset.toLong()).toString()
                    val selected = state.date == date
                    OutlinedButton(onClick = { vm.setDate(date) }) {
                        Text(
                            date.substring(5),
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
        item {
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { vm.searchTrains() },
                enabled = !state.busy && state.from != null && state.to != null,
            ) { Text("Najdi spoje") }
        }
    }
}

@Composable
private fun PickTrain(state: UiState, vm: SeatViewModel) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text("Priame spoje ${state.date}", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
        }
        items(state.trains) { train ->
            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable(enabled = !state.busy) { vm.selectTrain(train) },
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("${train.departure} - ${train.arrival}", fontWeight = FontWeight.Bold)
                    Text("volnych ${train.freeSeats} miest", fontSize = 12.sp, color = Color.Gray)
                }
            }
        }
        item { TextButton(onClick = { vm.back() }) { Text("Spat") } }
    }
}

@Composable
private fun PickSeat(state: UiState, vm: SeatViewModel) {
    val coach = state.coach
    if (coach == null) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Text("Vyber vozen", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
            }
            items(state.quickSection?.vehicles?.sortedBy { it.number } ?: emptyList()) { vehicle ->
                val deck = vehicle.decks.first()
                val free = deck.seats.count { it.free }
                Card(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { vm.selectCoach(vehicle.number) },
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Vozen ${vehicle.number} - ${deck.name}", fontWeight = FontWeight.Medium)
                        Text(
                            vehicle.seatClasses.joinToString(", ") { state.seatClassTitles[it] ?: it } +
                                "  -  volnych $free/${deck.seats.size}",
                            fontSize = 12.sp,
                            color = Color.Gray,
                        )
                    }
                }
            }
            item { TextButton(onClick = { vm.back() }) { Text("Spat") } }
        }
    } else {
        val deck = vm.decksOf(coach)
        LazyVerticalGrid(columns = GridCells.Fixed(6), modifier = Modifier.fillMaxSize()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Text("Vozen $coach - vyber svoje miesto", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text("zelene = volne, sive = obsadene", fontSize = 11.sp, color = Color.Gray)
                    Spacer(Modifier.height(8.dp))
                }
            }
            items(deck?.seats?.sortedBy { it.index } ?: emptyList()) { seat ->
                val bg = if (seat.free) Color(0xFF2E7D32) else Color(0xFF9E9E9E)
                Column(
                    Modifier
                        .padding(3.dp)
                        .background(bg, RoundedCornerShape(6.dp))
                        .clickable { vm.selectSeat(seat.index) }
                        .padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("${seat.index}", color = Color.White, fontSize = 13.sp)
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                TextButton(onClick = { vm.back() }) { Text("Spat") }
            }
        }
    }
}

@Composable
private fun ResultScreen(state: UiState, vm: SeatViewModel) {
    val analysis = state.analysis
    LazyColumn(Modifier.fillMaxSize()) {
        if (analysis == null) {
            item { Text("Analyza sa nepodarila.", fontSize = 13.sp) }
        } else {
            item {
                Text("Vozen ${analysis.coach} - ${analysis.coachName}", fontWeight = FontWeight.Bold)
                Text("Miesto ${analysis.seat}, oddiel ${analysis.bay.joinToString(",")}", fontSize = 13.sp)
                if (analysis.confidence == Confidence.UNCERTAIN) {
                    Text(
                        "! susedstvo je neiste - layout tohto vozna sa neda precitat spolahlivo",
                        fontSize = 12.sp,
                        color = Color(0xFFB26A00),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text("Kto je okolo teba", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                if (!state.fullScanDone) {
                    Text(
                        "zistujem, od ktorej stanice sa miesta uvolnia...",
                        fontSize = 11.sp,
                        color = Color.Gray,
                    )
                }
            }
            items(analysis.neighbours) { n ->
                val freesAt = n.freesAt
                val status = when {
                    n.freeWholeWay -> "volne celu cestu"
                    freesAt != null -> "uvolni sa v ${freesAt.stationName} (${freesAt.departure})"
                    state.fullScanDone -> "obsadene po celej trase"
                    else -> "obsadene"
                }
                val flags = n.flags.joinToString(",") { flagLabel(it) }
                Text(
                    "miesto ${n.seat} (${n.relation}) - $status" + if (flags.isNotEmpty()) "  +$flags" else "",
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Vo vozni volnych ${analysis.freeInCoach}/${analysis.coachTotal}",
                    fontSize = 12.sp,
                    color = Color.Gray,
                )
                if (state.recommendations.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Text("Pokojnejsie miesta", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                }
            }
            items(state.recommendations) { pick ->
                val kind = if (pick.isCompartment) "kupe" else "dvojica"
                val emptyFrom = pick.emptyFrom
                val status = when {
                    pick.takenNeighbours.isEmpty() -> "cele volne celu cestu"
                    emptyFrom != null -> "prazdne od ${emptyFrom.stationName} (${emptyFrom.departure})"
                    else -> "volne ${pick.freeNeighbours.size}/${pick.bay.size - 1}"
                }
                Text(
                    "vozen ${pick.coach} miesto ${pick.seat} - $kind ${pick.bay.joinToString(",")} - $status",
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
            item {
                Spacer(Modifier.height(14.dp))
                Button(onClick = { vm.toggleWatching() }) {
                    Text(if (state.watching) "Prestat sledovat" else "Sledovat toto miesto")
                }
                Text(
                    "Sledovanie kontroluje zmeny na pozadi kazdych 15 minut a posle notifikaciu, " +
                        "ked sa susedne miesto uvolni alebo obsadi.",
                    fontSize = 11.sp,
                    color = Color.Gray,
                )
                TextButton(onClick = { vm.back() }) { Text("Spat") }
            }
        }
    }
}

private fun flagLabel(flag: SeatFlag): String = when (flag) {
    SeatFlag.TABLE -> "stolik"
    SeatFlag.QUIET -> "ticho"
    SeatFlag.CHILDREN -> "deti"
    SeatFlag.WOMEN_ONLY -> "len zeny"
}

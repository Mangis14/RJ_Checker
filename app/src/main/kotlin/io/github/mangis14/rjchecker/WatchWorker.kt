package io.github.mangis14.rjchecker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.mangis14.rjchecker.core.Journey
import io.github.mangis14.rjchecker.core.JourneyLoader
import io.github.mangis14.rjchecker.core.RjClient
import io.github.mangis14.rjchecker.core.SeatChange
import io.github.mangis14.rjchecker.core.SeatSnapshot
import io.github.mangis14.rjchecker.core.SeatWatcher
import io.github.mangis14.rjchecker.core.WatchDecision
import io.github.mangis14.rjchecker.core.WatchSchedule
import io.github.mangis14.rjchecker.core.WatchedTrip
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Periodicka kontrola sledovanych miest.
 *
 * Notifikacia ide len pri skutocnej zmene voci poslednemu ulozenemu stavu -
 * preto sa snapshot uklada pre kazdy spoj zvlast. Bez toho by appka hlasila
 * "zmenu" pri kazdom kole.
 */
class WatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = TripPrefs(applicationContext)
        val trips = prefs.trips()
        if (trips.isEmpty()) {
            cancel(applicationContext)
            return Result.success()
        }

        val tick = prefs.nextTick()
        val now = System.currentTimeMillis() / 60_000
        var anyFailed = false

        val due = mutableListOf<WatchedTrip>()
        for (trip in trips) {
            when (
                WatchSchedule.decide(
                    nowMinutes = now,
                    departureMinutes = trip.departureEpochMinutes(),
                    tick = tick,
                )
            ) {
                // spoj uz odisiel - prestat ho sledovat, ale ostatne nechat bezat
                WatchDecision.STOP -> prefs.removeTrip(trip.id)
                WatchDecision.SKIP -> Unit
                WatchDecision.CHECK -> due.add(trip)
            }
        }

        // Spoje na tom istom vlaku a useku citaju uplne rovnake data, takze sa
        // nacitaju raz a vyhodnotia sa nad nimi vsetky. Sledovanie miesta aj
        // triedy v jednom vlaku tak stoji jedno stiahnutie, nie dve.
        for ((_, group) in due.groupBy { JourneyKey(it.routeId, it.fromId, it.toId, it.date, it.departure) }) {
            val journey = try {
                withContext(Dispatchers.IO) {
                    val client = RjClient(layoutStore = FileLayoutStore(applicationContext))
                    JourneyLoader(client).load(
                        routeId = group.first().routeId,
                        fromStationId = group.first().fromId,
                        toStationId = group.first().toId,
                        date = group.first().date,
                        departure = group.first().departure,
                        firstStopOnly = true,
                    )
                }
            } catch (e: Exception) {
                anyFailed = true
                continue
            }
            for (trip in group) {
                val ok = withContext(Dispatchers.IO) {
                    if (trip.isClassWatch) checkClass(prefs, trip, journey)
                    else checkSeat(prefs, trip, journey)
                }
                if (!ok) anyFailed = true
            }
        }

        if (prefs.trips().isEmpty()) cancel(applicationContext)
        return if (anyFailed) Result.retry() else Result.success()
    }

    /** Spoje s rovnakym klucom citaju rovnake data - staci ich nacitat raz. */
    private data class JourneyKey(
        val routeId: String,
        val fromId: Long,
        val toId: Long,
        val date: String,
        val departure: String,
    )

    /**
     * Sledovanie celej triedy - "daj vediet, ked sa uvolni hocijaky Relax".
     *
     * Pozera cely vlak, nie jeden vozen, takze snapshot drzi dvojice
     * vozen-miesto.
     */
    private fun checkClass(prefs: TripPrefs, trip: WatchedTrip, journey: Journey): Boolean {
        val seatClass = trip.seatClass ?: return true
        val free = try {
            journey.freeSeatsInClass(seatClass, trip.onlyComfortable)
        } catch (e: Exception) {
            return false
        }

        val current = SeatSnapshot(
            seats = emptyMap(),
            freeInCoach = free.size,
            classFreeSeats = free.map { "${it.coach}-${it.seat}" }.toSet(),
        )
        val previous = prefs.loadSnapshot(trip.id)
        val freed = SeatWatcher.classSeatsFreed(previous, current)

        if (freed.isNotEmpty()) {
            // Pohodlie sa oznacuje, nefiltruje: pri vypredanej triede clovek
            // potrebuje vediet o kazdom uvolnenom mieste.
            val comfy = free.filter { it.comfortable }
                .map { "${it.coach}-${it.seat}" }.toSet()
            val wasSoldOut = previous != null && previous.classFreeSeats.isEmpty()
            notify(
                trip = trip,
                title = if (wasSoldOut) {
                    "${classLabel(seatClass)} sa uvoľnil"
                } else {
                    "Uvoľnil sa ${classLabel(seatClass)}"
                },
                text = SeatWatcher.describeClassSeats(freed, comfortable = comfy) +
                    " (v triede spolu ${free.size} voľných). Klepni pre analýzu.",
            )
        }
        prefs.saveSnapshot(trip.id, current)
        return true
    }

    /** @return false ak sa kontrola nepodarila a ma sa zopakovat */
    private fun checkSeat(prefs: TripPrefs, trip: WatchedTrip, journey: Journey): Boolean {
        val loaded = try {
            val analysis = journey.analyseSeat(trip.coach, trip.seat)
            val coachFree = journey.stops.firstOrNull()
                ?.section?.vehicle(trip.coach)?.decks?.firstOrNull()
                ?.freeSeats?.toSet() ?: emptySet()
            analysis?.let { Triple(it, coachFree, journey.emptyBaysInCoach(trip.coach)) }
        } catch (e: Exception) {
            return false
        } ?: return true

        val (analysis, coachFree, emptyBays) = loaded
        val current = SeatSnapshot.of(analysis, coachFree, emptyBays)
        val previous = prefs.loadSnapshot(trip.id)

        val alerts = SeatWatcher.diff(previous, current)
        val baysFreed = SeatWatcher.baysBecameEmpty(previous, current)
        val freedInCoach = SeatWatcher.coachFreed(previous, current)

        // Poradie dolezitosti: cely prazdny oddiel je najsilnejsi signal (da sa
        // presunut a cestovat sam), potom zmena vedla teba, az potom zvysok vozna.
        when {
            baysFreed.isNotEmpty() -> notify(
                trip = trip,
                title = "Uvoľnil sa celý oddiel",
                text = "Vozeň ${trip.coach}: miesta ${baysFreed.first().replace(",", ", ")} " +
                    "sú voľné všetky. Klepni pre analýzu.",
            )

            alerts.isNotEmpty() -> {
                val freed = alerts.filter { it.change == SeatChange.FREED }.map { it.seat }
                val taken = alerts.filter { it.change == SeatChange.TAKEN }.map { it.seat }
                val parts = buildList {
                    if (freed.isNotEmpty()) add("uvoľnilo sa miesto ${SeatWatcher.describeSeats(freed)}")
                    if (taken.isNotEmpty()) add("obsadilo sa ${SeatWatcher.describeSeats(taken)}")
                }
                notify(
                    trip = trip,
                    title = "Vedľa teba sa niečo zmenilo",
                    text = "Vozeň ${trip.coach}, tvoje miesto ${trip.seat}: " +
                        parts.joinToString("; ") + ". Klepni pre analýzu.",
                )
            }

            freedInCoach.isNotEmpty() -> notify(
                trip = trip,
                title = "Vo vozni ${trip.coach} sa uvoľnilo miesto",
                text = "Voľné je teraz miesto ${SeatWatcher.describeSeats(freedInCoach)} " +
                    "(vo vozni spolu ${current.freeInCoach}). Klepni pre analýzu.",
            )
        }

        prefs.saveSnapshot(trip.id, current)
        return true
    }

    /**
     * Notifikacia otvara appku priamo na analyze daneho miesta - bez
     * PendingIntent by klepnutie nerobilo nic. Kazdy spoj ma vlastne ID, aby si
     * upozornenia navzajom neprepisovali.
     */
    private fun notify(trip: WatchedTrip, title: String, text: String) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Zmeny miest", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val requestCode = trip.id.hashCode()
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_COACH, trip.coach)
            putExtra(EXTRA_SEAT, trip.seat)
            putExtra(EXTRA_TRIP_ID, trip.id)
        }
        val pending = PendingIntent.getActivity(
            applicationContext,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText("${trip.fromName} → ${trip.toName}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(applicationContext).notify(requestCode, notification)
    }

    /**
     * Nazov triedy do notifikacie. Kluce z API su technicke (C0, C1), takze sa
     * prekladaju - "Uvolnil sa Relax" povie viac ako "Uvolnil sa C1".
     */
    private fun classLabel(key: String): String = when (key) {
        "C0", "TRAIN_STANDARD_PL", "TRAIN_R23_STANDARD", "TRAIN_R8_STANDARD" -> "Standard"
        "C1", "TRAIN_R23_RELAX" -> "Relax"
        "C2", "TRAIN_1ST_CLASS", "TRAIN_R23_BUSINESS" -> "Business"
        "TRAIN_LOW_COST", "TRAIN_2ND_CLASS", "TRAIN_R23_LOW_COST" -> "Low cost"
        else -> if (key.startsWith("TRAIN_COUCHETTE")) "lôžko/ležadlo" else key
    }

    companion object {
        private const val CHANNEL = "seat-changes"
        private const val WORK_NAME = "rjseat-watch"

        /** Klepnutie na notifikaciu otvori analyzu tohto vozna a miesta. */
        const val EXTRA_COACH = "rjseat.coach"
        const val EXTRA_SEAT = "rjseat.seat"
        const val EXTRA_TRIP_ID = "rjseat.tripId"

        /**
         * 15 minut je minimum, ktore WorkManager pre periodicku pracu povoluje.
         * Kolko z prebudeni sa naozaj vyuzije, rozhoduje WatchSchedule.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<WatchWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}

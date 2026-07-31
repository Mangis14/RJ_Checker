package io.github.mangis14.rjchecker

import io.github.mangis14.rjchecker.core.JourneyLoader
import io.github.mangis14.rjchecker.core.RjClient
import io.github.mangis14.rjchecker.core.SeatChange
import io.github.mangis14.rjchecker.core.SeatSnapshot
import io.github.mangis14.rjchecker.core.SeatWatcher
import io.github.mangis14.rjchecker.core.WatchSchedule
import io.github.mangis14.rjchecker.core.WatchDecision
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
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Periodicka kontrola sledovaneho miesta.
 *
 * Notifikacia ide len pri skutocnej zmene voci poslednemu ulozenemu stavu -
 * preto sa snapshot uklada do prefs. Bez toho by appka hlasila "zmenu" pri
 * kazdom kole.
 */
class WatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = TripPrefs(applicationContext)
        val trip = prefs.load() ?: return Result.success()   // sledovanie vypnute

        // Jedna kontrola stiahne cca 223 kB a server negzipuje, takze sa najprv
        // rozhodne, ci je vobec potrebna.
        val tick = prefs.nextTick()
        when (
            WatchSchedule.decide(
                nowMinutes = System.currentTimeMillis() / 60_000,
                departureMinutes = trip.departureEpochMinutes(),
                tick = tick,
            )
        ) {
            WatchDecision.SKIP -> return Result.success()
            WatchDecision.STOP -> {
                cancel(applicationContext)
                prefs.clear()
                return Result.success()
            }
            WatchDecision.CHECK -> Unit
        }

        val loaded = try {
            withContext(Dispatchers.IO) {
                val client = RjClient(layoutStore = FileLayoutStore(applicationContext))
                // Na upozornenie staci obsadenost pre usek z nastupnej stanice do
                // ciela - jedno volanie. Plny prechod zastavkami by kazdych 15
                // minut znamenal cca 30 volani a odpoved na otazku "kde presne
                // ten clovek vystupuje", ktoru notifikacia nepotrebuje.
                // Nazvy stanic sa tiez nestahuju: /consts/locations je velky
                // payload a v notifikacii sa nepouziva.
                val journey = JourneyLoader(client).load(
                    routeId = trip.routeId,
                    fromStationId = trip.fromId,
                    toStationId = trip.toId,
                    date = trip.date,
                    departure = trip.departure,
                    firstStopOnly = true,
                )
                val analysis = journey.analyseSeat(trip.coach, trip.seat)
                val coachFree = journey.stops.firstOrNull()
                    ?.section?.vehicle(trip.coach)?.decks?.firstOrNull()
                    ?.freeSeats?.toSet() ?: emptySet()
                analysis?.let { it to coachFree }
            }
        } catch (e: Exception) {
            return Result.retry()
        } ?: return Result.success()

        val (analysis, coachFree) = loaded
        val current = SeatSnapshot.of(analysis, coachFree)
        val previous = prefs.loadSnapshot()

        val alerts = SeatWatcher.diff(previous, current)
        val freedInCoach = SeatWatcher.coachFreed(previous, current)

        // Susedne miesta maju prednost - to je to, na co sa uzivatel pyta.
        // Az potom zvysok vozna, a vzdy s cislami miest: samotny pocet
        // volnych cloveku nepovie, kam si ma sadnut.
        if (alerts.isNotEmpty()) {
            val freed = alerts.filter { it.change == SeatChange.FREED }.map { it.seat }
            val taken = alerts.filter { it.change == SeatChange.TAKEN }.map { it.seat }
            val parts = buildList {
                if (freed.isNotEmpty()) {
                    add("uvoľnilo sa miesto ${SeatWatcher.describeSeats(freed)}")
                }
                if (taken.isNotEmpty()) {
                    add("obsadilo sa ${SeatWatcher.describeSeats(taken)}")
                }
            }
            notify(
                title = "Vedľa teba sa niečo zmenilo",
                text = "Vozeň ${trip.coach}, tvoje miesto ${trip.seat}: " +
                    parts.joinToString("; ") + ". Klepni pre analýzu.",
                coach = trip.coach,
                seat = trip.seat,
            )
        } else if (freedInCoach.isNotEmpty()) {
            notify(
                title = "Vo vozni ${trip.coach} sa uvoľnilo miesto",
                text = "Voľné je teraz miesto ${SeatWatcher.describeSeats(freedInCoach)} " +
                    "(vo vozni spolu ${current.freeInCoach}). Klepni pre analýzu.",
                coach = trip.coach,
                seat = trip.seat,
            )
        }

        prefs.saveSnapshot(current)
        return Result.success()
    }

    /**
     * Notifikacia otvara appku priamo na analyze sledovaneho miesta - bez
     * PendingIntent by klepnutie nerobilo nic.
     */
    private fun notify(title: String, text: String, coach: Int, seat: Int) {
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
            return                                    // bez povolenia notifikovat nemozeme
        }
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_COACH, coach)
            putExtra(EXTRA_SEAT, seat)
        }
        val pending = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val CHANNEL = "seat-changes"
        private const val NOTIFICATION_ID = 1001
        private const val WORK_NAME = "rjseat-watch"

        /** Klepnutie na notifikaciu otvori analyzu tohto vozna a miesta. */
        const val EXTRA_COACH = "rjseat.coach"
        const val EXTRA_SEAT = "rjseat.seat"

        /**
         * 15 minut je minimum, ktore WorkManager pre periodicku pracu povoluje.
         * Castejsie kontroly by aj tak nemali zmysel - a API netreba zatazovat.
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

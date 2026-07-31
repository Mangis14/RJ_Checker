package io.github.mangis14.rjchecker

import io.github.mangis14.rjchecker.core.JourneyLoader
import io.github.mangis14.rjchecker.core.RjClient
import io.github.mangis14.rjchecker.core.SeatChange
import io.github.mangis14.rjchecker.core.SeatSnapshot
import io.github.mangis14.rjchecker.core.SeatWatcher
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
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

        val analysis = try {
            withContext(Dispatchers.IO) {
                val client = RjClient()
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
                journey.analyseSeat(trip.coach, trip.seat)
            }
        } catch (e: Exception) {
            return Result.retry()
        } ?: return Result.success()

        val current = SeatSnapshot(
            seats = analysis.neighbours.associate { it.seat to it.freeWholeWay },
            freeInCoach = analysis.freeInCoach,
        )
        val saved = prefs.loadSnapshot()
        val previous = saved?.let { SeatSnapshot(it.first, it.second) }

        val alerts = SeatWatcher.diff(previous, current)
        val soldOutOpened = previous != null && SeatWatcher.soldOutOpenedUp(previous, current)

        if (alerts.isNotEmpty()) {
            val freed = alerts.filter { it.change == SeatChange.FREED }.map { it.seat }
            val taken = alerts.filter { it.change == SeatChange.TAKEN }.map { it.seat }
            val parts = buildList {
                if (freed.isNotEmpty()) add("uvolnilo sa ${freed.joinToString(", ")}")
                if (taken.isNotEmpty()) add("obsadilo sa ${taken.joinToString(", ")}")
            }
            notify(
                title = "Vozen ${trip.coach}, miesto ${trip.seat}",
                text = parts.joinToString("; "),
            )
        } else if (soldOutOpened) {
            notify(
                title = "Vo vozni ${trip.coach} sa uvolnilo miesto",
                text = "volnych ${current.freeInCoach} - vypredany spoj sa otvoril",
            )
        }

        prefs.saveSnapshot(current.seats, current.freeInCoach)
        return Result.success()
    }

    private fun notify(title: String, text: String) {
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
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val CHANNEL = "seat-changes"
        private const val NOTIFICATION_ID = 1001
        private const val WORK_NAME = "rjseat-watch"

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

package it.iz2oii.cacciabuoni

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** Ogni ~6 ore: sincronizza e avvisa dei buoni che scadono oggi o domani (una volta al giorno). */
class DailyWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        Store.init(applicationContext)
        Store.sync()
        val today = LocalDate.now().toEpochDay()
        if (Store.keyValue("lastExpiryAlert") != today) {
            val due = Store.snapshot.value.buoni.filter { it.usato.isEmpty() && (it.giorni()?.let { g -> g in 0L..1L } == true) }
            if (due.isNotEmpty()) {
                Notifier.post(
                    applicationContext, Notifier.CH_SCADENZE, 1001,
                    "${due.size} buon${if (due.size == 1) "o scade" else "i scadono"} a breve",
                    due.joinToString("\n") { "${it.negozio} ${it.valore} – ${if (it.giorni() == 0L) "oggi" else "domani"}" }
                )
                Store.setKeyValue("lastExpiryAlert", today)
            }
        }
        Widget.update(applicationContext)
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<DailyWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("daily", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}

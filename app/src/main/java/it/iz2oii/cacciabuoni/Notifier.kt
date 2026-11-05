package it.iz2oii.cacciabuoni

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object Notifier {
    const val CH_SCADENZE = "scadenze"
    const val CH_NEGOZIO = "negozio"
    const val CH_CATTURATI = "catturati"
    const val EXTRA_STORE = "store"

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_SCADENZE, "Buoni in scadenza", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_NEGOZIO, "Hai buoni per questo negozio", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_CATTURATI, "Buoni salvati dalle notifiche", NotificationManager.IMPORTANCE_LOW))
    }

    fun canPost(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun openApp(ctx: Context, store: String? = null, req: Int = 0): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (store != null) i.putExtra(EXTRA_STORE, store)
        return PendingIntent.getActivity(ctx, req, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun post(ctx: Context, channel: String, id: Int, title: String, text: String, store: String? = null) {
        if (!canPost(ctx)) return
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(ctx, store, id))
            .setAutoCancel(true)
            .setPriority(if (channel == CH_CATTURATI) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .build()
        NotificationManagerCompat.from(ctx).notify(id, n)
    }

    /** Testo riassuntivo di cosa hai per un negozio. */
    fun summary(buoni: List<Buono>, offerte: List<Offerta>): String {
        val parts = mutableListOf<String>()
        buoni.forEach { b ->
            parts += listOfNotNull(b.valore.ifEmpty { "buono" }, b.codice.takeIf { it.isNotEmpty() }).joinToString(" ") +
                (b.giorni()?.let { if (it == 0L) " (scade oggi)" else if (it == 1L) " (scade domani)" else "" } ?: "")
        }
        offerte.filter { it.sconto.contains("cashback", true) }.forEach { parts += "${it.fonte} ${it.sconto}" }
        offerte.filter { !it.sconto.contains("cashback", true) && it.sconto.isNotEmpty() }.forEach { parts += "${it.fonte} ${it.sconto}" }
        var s = parts.joinToString(" · ")
        if (buoni.any { it.codice.isNotEmpty() } && offerte.any { it.sconto.contains("cashback", true) })
            s += "\nAttenzione: un codice esterno può annullare il cashback."
        return s
    }
}

package it.iz2oii.cacciabuoni

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Legge le notifiche delle app scelte e salva i buoni prima che spariscano.
 * Attivazione: Impostazioni › Notifiche › Accesso alle notifiche › Cacciabuoni.
 */
class NotifCatcher : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try { handle(sbn) } catch (_: Exception) { }
    }

    private fun handle(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName || sbn.isOngoing) return
        Store.init(this)
        if (sbn.packageName !in Store.watched) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val lines = ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n").orEmpty()
        val full = listOf(title, text, lines).filter { it.isNotBlank() }.joinToString("\n")
        if (full.isBlank()) return

        // niente doppioni (le app ripubblicano spesso la stessa notifica)
        val key = (sbn.packageName + full).hashCode().toLong()
        if (Store.keyValue("seen_$key") > 0) return

        val f = Parser.analyze(full)
        if (!f.pertinente) return
        Store.setKeyValue("seen_$key", System.currentTimeMillis())

        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)

        val b = Buono(
            id = "N" + System.currentTimeMillis(),
            data = java.time.LocalDate.now().toString(),
            negozio = label,
            codice = f.codice,
            valore = f.valore,
            scadenza = f.scadenza?.toString() ?: "",
            dove = if (f.codice.isEmpty()) "Apri l'app $label (il codice non era nella notifica)" else "Notifica dell'app $label",
            tipo = "notifica",
            oggetto = title.take(200),
            note = full.replace('\n', ' ').take(300),
            fonte = "Notifica $label"
        )
        Store.add(b)
        Notifier.post(
            this, Notifier.CH_CATTURATI, key.toInt(), "Salvato: $label",
            listOf(f.valore, f.codice, f.scadenza?.let { "fino al " + it.dayOfMonth + "/" + it.monthValue }).filter { !it.isNullOrEmpty() }
                .joinToString(" · ").ifEmpty { title.ifEmpty { "promemoria buono" } },
            label
        )
    }
}

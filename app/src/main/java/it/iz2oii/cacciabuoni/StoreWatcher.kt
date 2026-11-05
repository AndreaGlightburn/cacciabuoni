package it.iz2oii.cacciabuoni

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Si accorge di quale app hai aperto (solo il nome del pacchetto, non legge lo schermo).
 * Se è un negozio per cui hai buoni o cashback, ti avvisa.
 * Attivazione: Impostazioni › Accessibilità › App installate › Cacciabuoni.
 */
class StoreWatcher : AccessibilityService() {
    private var last = ""

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == last || pkg == packageName || pkg.startsWith("com.android.") || pkg == "android") return
        last = pkg
        try { check(pkg) } catch (_: Exception) { }
    }

    private fun check(pkg: String) {
        Store.init(this)
        if (!Store.storeAlerts) return
        if (isLauncher(pkg)) return
        val since = System.currentTimeMillis() - Store.keyValue("alert_$pkg")
        if (since < 4 * 3600_000L) return
        val label = packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        val (buoni, offerte) = Store.snapshot.value.perNegozio(label)
        if (buoni.isEmpty() && offerte.none { it.sconto.isNotEmpty() }) return
        Store.setKeyValue("alert_$pkg", System.currentTimeMillis())
        val title = if (buoni.isNotEmpty()) "$label: hai ${buoni.size} buon${if (buoni.size == 1) "o" else "i"}" else "$label: c'è un'offerta per te"
        Notifier.post(this, Notifier.CH_NEGOZIO, pkg.hashCode(), title, Notifier.summary(buoni, offerte), label)
    }

    private fun isLauncher(pkg: String): Boolean {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.queryIntentActivities(i, 0).any { it.activityInfo.packageName == pkg }
    }

    override fun onInterrupt() {}
}

package it.iz2oii.cacciabuoni

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews

/** Widget in home: i prossimi buoni in scadenza. */
class Widget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        Store.init(ctx)
        update(ctx)
    }

    companion object {
        fun update(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, Widget::class.java))
            if (ids.isEmpty()) return
            val list = Store.snapshot.value.buoni.filter { it.attivo }
                .sortedBy { it.scadenza.ifEmpty { "9999" } }.take(5)
            val body = if (list.isEmpty()) "Nessun buono attivo" else list.joinToString("\n") { b ->
                val g = b.giorni()
                val quando = when { g == null -> ""; g == 0L -> " · oggi"; g == 1L -> " · domani"; g <= 7 -> " · ${g}gg"; else -> "" }
                "${b.negozio}  ${b.valore}$quando"
            }
            val v = RemoteViews(ctx.packageName, R.layout.widget).apply {
                setTextViewText(R.id.w_title, "Buoni attivi: ${Store.snapshot.value.buoni.count { it.attivo }}")
                setTextViewText(R.id.w_body, body)
                setOnClickPendingIntent(R.id.w_root, Notifier.openApp(ctx, null, 7))
            }
            mgr.updateAppWidget(ids, v)
        }
    }
}

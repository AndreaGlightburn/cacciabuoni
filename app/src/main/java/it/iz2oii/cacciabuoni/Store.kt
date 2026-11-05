package it.iz2oii.cacciabuoni

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate

/**
 * Archivio centrale: dati dallo script Apps Script (cache su file) + modifiche locali in coda.
 * Le modifiche fatte offline (nuovi buoni, usato, elimina) restano in coda e partono alla prima sincronizzazione.
 */
object Store {
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val _snap = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snap
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status

    private var raw: JSONObject = JSONObject()
    private var queue: JSONArray = JSONArray()

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        raw = runCatching { JSONObject(cacheFile.readText()) }.getOrDefault(JSONObject())
        queue = runCatching { JSONArray(queueFile.readText()) }.getOrDefault(JSONArray())
        publish()
    }

    private val prefs get() = app.getSharedPreferences("cacciabuoni", Context.MODE_PRIVATE)
    private val cacheFile get() = File(app.filesDir, "snapshot.json")
    private val queueFile get() = File(app.filesDir, "queue.json")

    /* ---------- impostazioni ---------- */

    var url: String
        get() = prefs.getString("url", "") ?: ""
        set(v) = prefs.edit().putString("url", v.trim()).apply()
    var token: String
        get() = prefs.getString("token", "") ?: ""
        set(v) = prefs.edit().putString("token", v.trim()).apply()
    var storeAlerts: Boolean
        get() = prefs.getBoolean("storeAlerts", true)
        set(v) = prefs.edit().putBoolean("storeAlerts", v).apply()
    var watched: Set<String>
        get() = prefs.getStringSet("watched", emptySet())!!.toSet()
        set(v) = prefs.edit().putStringSet("watched", v).apply()
    val configured get() = url.startsWith("https://") && token.isNotEmpty()

    /** Accetta il link stampato da linkPWA (…#u=…&t=…) oppure solo l'URL /exec. */
    fun applyLink(link: String): Boolean {
        val hash = link.substringAfter('#', "")
        if (hash.isNotEmpty()) {
            val p = hash.split('&').mapNotNull { kv -> kv.split('=', limit = 2).takeIf { it.size == 2 } }
                .associate { (k, v) -> k to Uri.decode(v) }
            if (p["u"] != null && p["t"] != null) { url = p.getValue("u"); token = p.getValue("t"); return true }
        }
        if (link.trim().startsWith("https://script.google.com/")) { url = link.trim(); return true }
        return false
    }

    fun keyValue(k: String, def: Long = 0) = prefs.getLong(k, def)
    fun setKeyValue(k: String, v: Long) = prefs.edit().putLong(k, v).apply()

    /* ---------- dati ---------- */

    private fun publish() {
        var s = Snapshot.from(raw)
        for (i in 0 until queue.length()) s = applyOp(s, queue.getJSONObject(i))
        _snap.value = s
        Widget.update(app)
    }

    private fun applyOp(s: Snapshot, op: JSONObject): Snapshot = when (op.optString("action")) {
        "aggiungi" -> {
            val b = Buono.from(op.getJSONObject("item"))
            if (s.buoni.any { it.id == b.id }) s else s.copy(buoni = s.buoni + b)
        }
        "usato" -> s.copy(buoni = s.buoni.map {
            if (it.id == op.optString("id")) it.copy(usato = if (op.optBoolean("value")) LocalDate.now().toString() else "") else it
        })
        "elimina" -> s.copy(buoni = s.buoni.filter { it.id != op.optString("id") })
        else -> s
    }

    private fun enqueue(op: JSONObject) {
        synchronized(this) {
            queue.put(op)
            queueFile.writeText(queue.toString())
        }
        publish()
        scope.launch { runCatching { flush() } }
    }

    fun add(b: Buono) = enqueue(JSONObject().put("action", "aggiungi").put("item", b.toJson()))
    fun setUsed(id: String, used: Boolean) = enqueue(JSONObject().put("action", "usato").put("id", id).put("value", used))
    fun delete(id: String) = enqueue(JSONObject().put("action", "elimina").put("id", id))

    private suspend fun flush() = mutex.withLock {
        if (!configured || queue.length() == 0) return@withLock
        val rest = JSONArray()
        for (i in 0 until queue.length()) {
            val op = queue.getJSONObject(i)
            val ok = runCatching { post(JSONObject(op.toString()).put("token", token)).optBoolean("ok") }.getOrDefault(false)
            if (!ok) rest.put(op)
        }
        synchronized(this) { queue = rest; queueFile.writeText(queue.toString()) }
    }

    /** Scarica i dati aggiornati. Con scan=true chiede prima allo script di ricontrollare Gmail. */
    suspend fun sync(scan: Boolean = false): Result<String> = withContext(Dispatchers.IO) {
        if (!configured) return@withContext Result.failure(IllegalStateException("Collega prima lo script in Impostazioni"))
        _status.value = if (scan) "Controllo Gmail…" else "Aggiornamento…"
        runCatching {
            flush()
            var msg = ""
            if (scan) {
                val r = get(mapOf("action" to "scan"))
                val n = r.optJSONObject("nuovi")
                msg = if (n != null) "Nuovi: ${n.optInt("buoni")} buoni, ${n.optInt("offerte")} offerte" else ""
            }
            val j = get(emptyMap())
            if (!j.optBoolean("ok")) error(j.optString("error", "risposta non valida"))
            raw = j
            cacheFile.writeText(j.toString())
            publish()
            _status.value = "Aggiornato ora"
            msg
        }.onFailure { _status.value = "Offline: dati salvati" }
    }

    fun syncInBackground() = scope.launch { sync() }

    /* ---------- rete ---------- */

    private fun get(params: Map<String, String>): JSONObject {
        val q = (params + ("token" to token)).entries.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
        return request(url + (if (url.contains('?')) "&" else "?") + q, null)
    }

    private fun post(body: JSONObject): JSONObject = request(url, body.toString())

    /** Apps Script risponde con un redirect verso googleusercontent: lo seguiamo a mano (anche dopo un POST). */
    private fun request(start: String, body: String?): JSONObject {
        var target = start
        var payload = body
        repeat(5) {
            val c = (URL(target).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000; readTimeout = 330_000
                if (payload != null) {
                    requestMethod = "POST"; doOutput = true
                    setRequestProperty("Content-Type", "text/plain;charset=utf-8")
                }
            }
            try {
                if (payload != null) c.outputStream.use { it.write(payload!!.toByteArray()) }
                val code = c.responseCode
                if (code in 300..399) {
                    target = c.getHeaderField("Location") ?: error("redirect senza destinazione")
                    payload = null
                    return@repeat
                }
                val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
                if (code >= 400) error("HTTP $code")
                return JSONObject(text)
            } finally { c.disconnect() }
        }
        error("troppi redirect")
    }
}

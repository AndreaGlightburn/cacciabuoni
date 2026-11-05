package it.iz2oii.cacciabuoni

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class Buono(
    val id: String,
    val data: String = "",
    val negozio: String = "",
    val codice: String = "",
    val valore: String = "",
    val scadenza: String = "",
    val dove: String = "",
    val tipo: String = "",
    val oggetto: String = "",
    val link: String = "",
    val usato: String = "",
    val note: String = "",
    val fonte: String = ""
) {
    fun giorni(): Long? = runCatching { ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(scadenza)) }.getOrNull()
    val attivo: Boolean get() = usato.isEmpty() && (giorni()?.let { it >= 0 } ?: true)
    val scaduto: Boolean get() = usato.isEmpty() && (giorni()?.let { it < 0 } ?: false)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("data", data); put("negozio", negozio); put("codice", codice); put("valore", valore)
        put("scadenza", scadenza); put("dove", dove); put("tipo", tipo); put("oggetto", oggetto); put("link", link)
        put("usato", usato); put("note", note); put("fonte", fonte)
    }

    companion object {
        fun from(o: JSONObject) = Buono(
            id = o.optString("id"), data = o.optString("data"), negozio = o.optString("negozio"),
            codice = o.optString("codice"), valore = o.optString("valore"), scadenza = o.optString("scadenza"),
            dove = o.optString("dove"), tipo = o.optString("tipo"), oggetto = o.optString("oggetto"),
            link = o.optString("link"), usato = o.optString("usato"), note = o.optString("note"), fonte = o.optString("fonte")
        )
    }
}

data class Offerta(
    val id: String, val data: String, val fonte: String, val negozio: String, val sconto: String,
    val codice: String, val scadenza: String, val link: String, val note: String
) {
    companion object {
        fun from(o: JSONObject) = Offerta(
            o.optString("id"), o.optString("data"), o.optString("fonte"), o.optString("negozio"), o.optString("sconto"),
            o.optString("codice"), o.optString("scadenza"), o.optString("link"), o.optString("note")
        )
    }
}

data class CashEvent(val servizio: String, val negozio: String, val importo: String, val stato: String, val data: String)
data class Saldo(val servizio: String, val saldo: String, val al: String)

data class Snapshot(
    val buoni: List<Buono> = emptyList(),
    val offerte: List<Offerta> = emptyList(),
    val eventi: List<CashEvent> = emptyList(),
    val saldi: List<Saldo> = emptyList(),
    val portali: Map<String, String> = emptyMap(),
    val aggiornato: String = ""
) {
    /** Tutto quello che c'è per un negozio: i tuoi buoni attivi e le offerte (cashback, convenzioni). */
    fun perNegozio(nome: String): Pair<List<Buono>, List<Offerta>> =
        buoni.filter { it.attivo && Parser.sameStore(it.negozio, nome) } to
            offerte.filter { Parser.sameStore(it.negozio, nome) }

    companion object {
        fun from(j: JSONObject): Snapshot {
            fun <T> arr(a: JSONArray?, f: (JSONObject) -> T) = (0 until (a?.length() ?: 0)).map { f(a!!.getJSONObject(it)) }
            val cb = j.optJSONObject("cashback")
            val saldiObj = cb?.optJSONObject("saldi")
            val saldi = saldiObj?.keys()?.asSequence()?.map { k ->
                val s = saldiObj.getJSONObject(k); Saldo(k, s.optString("saldo"), s.optString("al"))
            }?.toList() ?: emptyList()
            val port = j.optJSONObject("portali")
            return Snapshot(
                buoni = arr(j.optJSONArray("buoni"), Buono::from),
                offerte = arr(j.optJSONArray("offerte"), Offerta::from),
                eventi = arr(cb?.optJSONArray("eventi")) {
                    CashEvent(it.optString("servizio"), it.optString("negozio"), it.optString("importo"), it.optString("stato"), it.optString("data"))
                },
                saldi = saldi,
                portali = port?.keys()?.asSequence()?.associateWith { port.optString(it) } ?: emptyMap(),
                aggiornato = j.optString("aggiornato")
            )
        }
    }
}

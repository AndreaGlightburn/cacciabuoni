package it.iz2oii.cacciabuoni

import java.time.LocalDate

/**
 * Riconoscimento di codici, valori e scadenze nel testo.
 * Stessa logica di Code.gs (Apps Script) e della PWA: se cambi qui, allinea anche là.
 */
object Parser {

    private val STOP = setOf(
        "CODICE", "SCONTO", "COUPON", "PROMO", "EXTRA", "GRATIS", "SUBITO", "ACQUISTA", "APPLICA",
        "CARRELLO", "CASSA", "ORDINA", "SCOPRI", "VALIDO", "BUONO", "VOUCHER", "OFFERTA", "SPECIALE", "ESCLUSIVO", "TUTTO",
        "NOVITA", "BLACK", "FRIDAY", "CLICCA", "SHOP", "ONLINE", "STORE", "EMAIL", "GIOCA", "SALDI", "DONNA", "UOMO",
        "BAMBINI", "REGALO", "ADESSO", "RISCATTA", "CONTROLLA", "DETTAGLI", "NEWSLETTER", "EURO", "CASHBACK", "ORDINE",
        "SPEDIZIONE", "CONSEGNA", "GRATUITA", "PREZZO", "PRODOTTI", "ARTICOLI", "ACCEDI", "REGISTRATI", "COPIA", "INSERISCI"
    )

    private val MESI = mapOf(
        "gennaio" to 1, "febbraio" to 2, "marzo" to 3, "aprile" to 4, "maggio" to 5, "giugno" to 6,
        "luglio" to 7, "agosto" to 8, "settembre" to 9, "ottobre" to 10, "novembre" to 11, "dicembre" to 12
    )

    fun cleanText(s: String?): String = (s ?: "")
        .replace(Regex("""https?://[^\s)\]>"'*]+"""), " ")
        .replace(Regex("""<[^>\n]{0,300}>"""), " ")
        .replace(Regex("""\[[^\]]*]\([^)]*\)"""), " ")
        .replace(Regex("[͏​-‍﻿­⁠]"), "")
        .replace(Regex("[ \t ]+"), " ")

    fun isCodeToken(t: String): Boolean {
        if (t.length < 5 || t.length > 25) return false
        if (!t.any { it.isLetter() }) return false
        if (Regex("""^\d+(gb|tb|mb|ml|kg|mm|cm|hz|mah|pz|cl)$""", RegexOption.IGNORE_CASE).matches(t)) return false
        if (t.any { it.isDigit() }) return true
        return t == t.uppercase() && t !in STOP
    }

    private fun codeScore(t: String, dist: Int): Int {
        val digits = t.count { it.isDigit() }
        return (if (digits >= 2) 3 else if (digits > 0) 1 else 0) +
            (if (t == t.uppercase()) 2 else 0) + (if (t.length >= 6) 1 else 0) + (if (dist < 40) 2 else 0)
    }

    private val KW = Regex(
        """(codice(?:\s+(?:sconto|promo(?:zionale)?|coupon|del tuo buono(?:\s+personale)?|esclusivo|personale))?|coupon(?:\s+code)?|promo\s*code|voucher\s*code|discount\s*code|usa il codice)""",
        RegexOption.IGNORE_CASE
    )
    private val NEG = Regex("""^\s*(?:modello|cliente|fiscale|carta|articolo|prodotto|ordine|postale|iban|per partecipare|gioco|N°)""", RegexOption.IGNORE_CASE)
    private val TOKEN = Regex("""[A-Za-z0-9][A-Za-z0-9-]*[A-Za-z0-9]""")

    fun extractCodes(text: String): List<String> {
        val best = LinkedHashMap<String, Int>()
        for (m in KW.findAll(text)) {
            val start = m.range.last + 1
            val after = text.substring(start, minOf(text.length, start + 110))
            if (NEG.containsMatchIn(after)) continue
            for (t in TOKEN.findAll(after)) {
                val tok = t.value
                if (!isCodeToken(tok)) continue
                val s = codeScore(tok, t.range.first)
                if (s >= 4 && (best[tok] ?: 0) < s) best[tok] = s
            }
        }
        return best.entries.sortedByDescending { it.value }.map { it.key }.take(2)
    }

    /** Ultima spiaggia per testi brevi (notifiche, post): token MAIUSCOLO con cifre. */
    fun looseCode(text: String): String? =
        Regex("""\b[A-Z0-9][A-Z0-9-]{3,20}[A-Z0-9]\b""").findAll(text).map { it.value }
            .firstOrNull { tok -> tok.any { it.isDigit() } && tok.any { it.isLetter() } && !Regex("""^\d+(GB|TB|MB|ML|KG|MM|CM|HZ|MAH)$""").matches(tok) }

    private fun mkDate(d: Int, mo: Int, y: Int?, ref: LocalDate): LocalDate? = try {
        var dt = LocalDate.of(y ?: ref.year, mo, d)
        if (y == null && dt.isBefore(ref.minusDays(1))) dt = dt.plusYears(1)
        dt
    } catch (e: Exception) { null }

    fun datesIn(s: String, ref: LocalDate): List<LocalDate> {
        val out = mutableListOf<LocalDate>()
        Regex("""(\d{1,2})[/.\-](\d{1,2})(?:[/.\-](\d{2,4}))?(?![\d,])""").findAll(s).forEach { m ->
            val d = m.groupValues[1].toInt(); val mo = m.groupValues[2].toInt()
            if (d !in 1..31 || mo !in 1..12) return@forEach
            var y = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()
            if (y != null && y < 100) y += 2000
            mkDate(d, mo, y, ref)?.let(out::add)
        }
        Regex("""(\d{1,2})°?\s+(gennaio|febbraio|marzo|aprile|maggio|giugno|luglio|agosto|settembre|ottobre|novembre|dicembre)(?:\s+(\d{4}))?""", RegexOption.IGNORE_CASE)
            .findAll(s).forEach { m ->
                mkDate(m.groupValues[1].toInt(), MESI.getValue(m.groupValues[2].lowercase()), m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt(), ref)?.let(out::add)
            }
        Regex("""(?:fra|tra)\s+(\d{1,3})\s+giorni""", RegexOption.IGNORE_CASE).findAll(s).forEach { m ->
            out.add(ref.plusDays(m.groupValues[1].toLong()))
        }
        Regex("""\b(oggi|domani)\b""", RegexOption.IGNORE_CASE).findAll(s).forEach { m ->
            out.add(if (m.value.equals("oggi", true)) ref else ref.plusDays(1))
        }
        return out
    }

    private val EXP_KW = Regex("""(scad\w*|valid\w*|fino\s+al?|entro|utilizzabile|spendibile|utilizzat\w+|termina|until|expires?)""", RegexOption.IGNORE_CASE)

    fun extractExpiry(text: String, ref: LocalDate = LocalDate.now()): LocalDate? {
        var best: LocalDate? = null
        for (m in EXP_KW.findAll(text)) {
            val win = text.substring(m.range.first, minOf(text.length, m.range.first + 70))
            for (d in datesIn(win, ref)) if (!d.isBefore(ref) && (best == null || d.isBefore(best))) best = d
        }
        return best
    }

    fun extractValue(s: String?): String {
        val t = s ?: return ""
        Regex("""(\d{1,3}(?:[.,]\d{1,2})?)\s?(%|€|euro\b|eur\b)""", RegexOption.IGNORE_CASE).find(t)?.let { m ->
            return if (m.groupValues[2] == "%") m.groupValues[1] + "%" else m.groupValues[1].replace('.', ',') + " €"
        }
        Regex("""€\s?(\d{1,4}(?:[.,]\d{1,2})?)""").find(t)?.let { return it.groupValues[1].replace('.', ',') + " €" }
        return ""
    }

    private val PROMO_KW = Regex(
        """(coupon|buono|voucher|codice sconto|codice promo|gift ?card|carta regalo|cashback|punti in scadenza|sconto (?:del|di|extra)|\d+\s?%\s?(?:di )?sconto|-\d+\s?%)""",
        RegexOption.IGNORE_CASE
    )

    private val PERSONAL = Regex("""(hai|tuo|tuoi|nuovo|nuovi|per te|ricevuto|accreditat\w*).{0,25}(coupon|buono|buoni|voucher|gift ?card|codice sconto)""", RegexOption.IGNORE_CASE)

    data class Found(val codice: String, val valore: String, val scadenza: LocalDate?, val pertinente: Boolean)

    /** Analisi di un testo breve (notifica, post condiviso, OCR). */
    fun analyze(raw: String, ref: LocalDate = LocalDate.now()): Found {
        val t = cleanText(raw)
        val code = extractCodes(t).firstOrNull() ?: looseCode(t).takeIf { PROMO_KW.containsMatchIn(t) } ?: ""
        val value = extractValue(t)
        val exp = extractExpiry(t, ref)
        val pertinent = code.isNotEmpty() || (PROMO_KW.containsMatchIn(t) && (value.isNotEmpty() || exp != null)) ||
            PERSONAL.containsMatchIn(t)
        return Found(code, value, exp, pertinent)
    }

    /* ---------- confronto nomi negozio / app ---------- */

    private val NOISE = Regex("""\b(shopping|shop|app|italia|italy|online|store|official|ufficiale|spa|s\.p\.a\.|srl|it|com|the|club|members|family|mobile)\b""")

    fun normStore(s: String?): String = (s ?: "").lowercase()
        .replace(Regex("""\.(it|com|eu|de|fr|es|net)\b"""), " ")
        .replace(NOISE, " ")
        .replace(Regex("""[^a-z0-9àèéìòù]+"""), " ")
        .trim()

    fun sameStore(a: String?, b: String?): Boolean {
        val x = normStore(a); val y = normStore(b)
        if (x.length < 3 || y.length < 3) return false
        if (x == y || x.contains(y) || y.contains(x)) return true
        val wx = x.split(' ').filter { it.length >= 4 }.toSet()
        val wy = y.split(' ').filter { it.length >= 4 }.toSet()
        return wx.intersect(wy).isNotEmpty()
    }
}

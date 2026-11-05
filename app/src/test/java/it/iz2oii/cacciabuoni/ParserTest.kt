package it.iz2oii.cacciabuoni

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ParserTest {
    private fun d(s: String) = LocalDate.parse(s)

    @Test fun shoprint() {
        val f = Parser.analyze("inserisci il seguente codice e premi il tasto Applica:\n\n COUPON5", d("2026-08-18"))
        assertEquals("COUPON5", f.codice)
    }

    @Test fun redcare() {
        val f = Parser.analyze("Il codice del tuo buono: soobit109431524\nIl tuo buono è valido solo fino al 09.11.2026", d("2026-10-26"))
        assertEquals("soobit109431524", f.codice)
        assertEquals(d("2026-11-09"), f.scadenza)
    }

    @Test fun samsungIgnoresModelCode() {
        val f = Parser.analyze("Usa il tuo codice sconto\nche vale 150€ su Galaxy Tab\nPI7-LY6T-6SZB-Z2EL (codice modello SM-X940N). Utilizzato entro e non oltre il 5 novembre 2026.", d("2026-10-14"))
        assertEquals("PI7-LY6T-6SZB-Z2EL", f.codice)
        assertEquals(d("2026-11-05"), f.scadenza)
    }

    @Test fun notificationTomorrow() {
        val f = Parser.analyze("Zalando: Il tuo coupon da 10€ scade domani! Usa ZAL10NOV al checkout", d("2026-11-05"))
        assertEquals("ZAL10NOV", f.codice)
        assertEquals(d("2026-11-06"), f.scadenza)
        assertEquals("10 €", f.valore)
    }

    @Test fun shippingIsNotACoupon() {
        assertFalse(Parser.analyze("Il tuo ordine è stato spedito! Traccia il pacco 1Z999AA10123456784").pertinente)
    }

    @Test fun reminderWithoutCode() {
        assertTrue(Parser.analyze("Hai un nuovo coupon nella sezione Buoni dell'app").pertinente)
    }

    @Test fun storeMatching() {
        assertTrue(Parser.sameStore("Amazon.it", "Amazon Shopping"))
        assertTrue(Parser.sameStore("Zalando", "Zalando Lounge"))
        assertFalse(Parser.sameStore("eBay", "Temu"))
    }
}

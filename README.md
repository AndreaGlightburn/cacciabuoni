# Cacciabuoni

Buoni, codici sconto, gift card e cashback in un posto solo.

| Parte | Cartella | A cosa serve |
|---|---|---|
| Script Apps Script | `apps-script/Code.gs` | Legge Gmail ogni mattina (buoni, LetyShops, Corporate Benefits), salva in un Foglio Google e fa da API |
| App Android | `app/` | Lista buoni, cattura dalle notifiche, avviso quando apri l'app di un negozio, OCR degli screenshot, widget, promemoria |
| PWA | `pwa/` | Accesso da PC (layout a colonne, scorciatoie `/` cerca, `n` nuovo, `r` aggiorna, `1-3` schede) |

Ogni push su `main` compila l'APK con GitHub Actions e lo pubblica tra le **Releases** insieme al pacchetto PWA.
Se la build fallisce, il log finisce in una issue.

## Installazione rapida
1. Script: vedi `apps-script/LEGGIMI-script-e-pwa.md` (setup → distribuisci come App web → linkPWA).
2. App: scarica `Cacciabuoni.apk` dall'ultima Release, installalo, apri ⚙ e incolla il link `cacciabuoni://setup#…` stampato da linkPWA.
3. Nelle impostazioni dell'app attiva:
   - **Leggere le notifiche** e scegli le app da ascoltare (Amazon, Zalando, Esselunga…);
   - **Riconoscere l'app aperta** (Accessibilità): avviso "hai 2 buoni qui" quando apri un negozio. Non legge lo schermo, solo il nome dell'app.
4. Widget: tieni premuto sulla home › Widget › Cacciabuoni.

La chiave di firma è fissa (`app/cacciabuoni.keystore.b64`): ogni nuova versione si installa sopra la precedente.

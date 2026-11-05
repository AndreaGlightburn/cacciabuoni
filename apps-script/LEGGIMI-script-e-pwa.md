# Cacciabuoni

Raccoglie in un'unica app i buoni sconto, i codici, le gift card e il cashback che ti arrivano per email. Funziona anche con i codici presi dai social, che puoi aggiungere con uno screenshot o con "Condividi".

## Com'è fatto

- **Code.gs**: è lo script Google Apps Script che lavora sul tuo account.
  - Ogni mattina alle 7 legge Gmail e salva tutto in un Foglio Google chiamato "Cacciabuoni".
  - Ti manda una mail quando un buono scade entro 3 giorni.
  - Fa da API per l'app.
- **index.html + manifest.webmanifest + sw.js + icone**: sono i file della PWA, da pubblicare su GitHub Pages.

## Installazione dello script (circa 5 minuti)

1. Vai su script.google.com e crea un **Nuovo progetto**. Incolla il contenuto di `Code.gs`.
2. Facoltativo: in `CFG.PWA_URL` metti l'indirizzo GitHub Pages dove pubblicherai la PWA.
3. Seleziona la funzione **setup**, premi **Esegui** e concedi i permessi (Gmail, Fogli, invio mail).
   - Lo script crea il foglio, programma la scansione giornaliera e fa la prima scansione degli ultimi 180 giorni.
4. Vai su **Distribuisci › Nuova distribuzione**, tipo **App web**.
   - Imposta "Esegui come: Me" e "Chi ha accesso: Chiunque".
5. Esegui **linkPWA**. Nel log trovi il link di collegamento: aprilo sul telefono, e l'app si collega da sola.

Il token protegge i dati: chi non lo conosce riceve solo "token non valido". Non condividere il link di collegamento.

## Cosa legge

| Fonte | Cosa estrae | Dove finisce |
|---|---|---|
| Mail con codici, buoni, voucher, gift card | codice, valore, scadenza, dove si trova (es. "app › sezione Buoni") | Buoni |
| LetyShops, notifiche (info@) | importo, stato (in sospeso o confermato), saldo | Cashback |
| LetyShops, promo (promo@) | negozio e % di cashback, valida 3 giorni | Offerte |
| Newsletter Corporate Benefits | link al portale, più marchi e % estratti in modo indicativo | Offerte |
| topnegozi.it | codici pubblici | Offerte |

Puoi aggiungere altre fonti in `CFG`, ad esempio un altro servizio di cashback in `CASHBACK_SOURCES` o un altro sito di codici pubblici in `PUBLIC_SOURCES`.

## Nell'app

- **Cerca un negozio** prima di comprare. Il pannello mostra:
  - i tuoi buoni per quel negozio
  - il cashback LetyShops attivo
  - le convenzioni aziendali
  - i link per cercare codici pubblici

  Se per lo stesso negozio hai sia un codice sia un cashback, l'app ti avvisa che il codice può annullare il cashback.
- **+**: per aggiungere un buono a mano.
  - "Da foto" legge uno screenshot con l'OCR, direttamente sul telefono.
  - "Incolla testo" estrae codice, valore e scadenza dal testo.
- **Condividi → Cacciabuoni**: dopo aver installato la PWA, compare nel menu "Condividi" di Android. Condividendo un post o un messaggio, si apre il modulo già compilato.
- **Segna usato** ed **Elimina** si sincronizzano col foglio. Se sei offline, le modifiche restano in coda e partono appena torni in linea.

## Limiti

- Corporate Benefits e LetyShops non hanno API pubbliche: i dati arrivano solo dalle mail che ti mandano. Il dettaglio delle convenzioni resta sul portale, dietro login.
- Instagram e TikTok non si possono leggere in automatico. Per quei codici usa screenshot o Condividi.
- Il riconoscimento dei codici è basato su regole, quindi controlla sempre il valore prima di usarli. Nel foglio puoi correggere qualsiasi campo.

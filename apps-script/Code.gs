/**
 * CACCIABUONI – raccoglie buoni, codici sconto, gift card e cashback dalla tua Gmail
 * e li espone alla PWA come API JSON protetta da token.
 *
 * Installazione (una volta sola):
 *   1. script.google.com › Nuovo progetto › incolla questo file come Code.gs
 *   2. Esegui la funzione  setup  e concedi i permessi (Gmail, Fogli, Mail)
 *   3. Distribuisci › Nuova distribuzione › App web
 *        Esegui come: Me    Chi ha accesso: Chiunque
 *   4. Esegui la funzione  linkPWA: stampa il link per la PWA e quello per l'app Android
 */

const CFG = {
  SHEET_NAME: 'Cacciabuoni',
  FIRST_SCAN_DAYS: 180,      // prima scansione: quanto indietro nel tempo
  DAILY_SCAN_DAYS: 4,        // scansioni successive (sovrapposte, i doppioni vengono scartati)
  MAX_THREADS: 200,          // per ogni ricerca
  TIME_BUDGET_MS: 5 * 60 * 1000,
  DIGEST_EMAIL: true,        // mail riepilogo dei buoni in scadenza
  DIGEST_DAYS: 3,
  OFFER_TTL_DAYS: 7,         // le offerte cashback senza scadenza spariscono dopo N giorni
  PWA_URL: '',               // es. https://andreaglightburn.github.io/cacciabuoni/  (per il link di setup)

  // Siti che mandano codici pubblici (non personali): finiscono in "Offerte"
  PUBLIC_SOURCES: ['topnegozi.it'],
  // Intermediari: il negozio vero va cercato nell'oggetto
  AGGREGATORS: ['sovendus.com', 'winwinit.eu', 'topnegozi.it', 'amilon', 'my-gate.eu'],

  // Notifiche cashback (saldo e ordini)
  CASHBACK_SOURCES: [
    { name: 'LetyShops', query: 'from:info@letyshops.com' }
  ],
  // Promo cashback per negozio
  CASHBACK_PROMO: [
    { name: 'LetyShops', query: 'from:(promo@letyshops.com OR promo@info.letyshops.com)' }
  ],
  // Portali convenzioni aziendali
  CORPORATE: [
    { name: 'Corporate Benefits', query: 'from:convenzioniaziendali.it' }
  ]
};

const COUPON_QUERY = '("codice sconto" OR coupon OR "buono sconto" OR "codice promo" OR voucher OR "gift card" ' +
  'OR "usa il codice" OR "il tuo codice" OR "il tuo buono" OR "buono da" OR "carta regalo") -in:sent -in:chats';

const HEAD = {
  Buoni:    ['id', 'msgId', 'data', 'negozio', 'codice', 'valore', 'scadenza', 'dove', 'tipo', 'oggetto', 'link', 'usato', 'note', 'fonte'],
  Offerte:  ['id', 'msgId', 'data', 'fonte', 'negozio', 'sconto', 'codice', 'scadenza', 'link', 'note'],
  Cashback: ['id', 'msgId', 'data', 'servizio', 'negozio', 'importo', 'stato', 'saldo', 'link']
};

/* ===================================================================== */
/*  SETUP                                                                 */
/* ===================================================================== */

function setup() {
  const props = PropertiesService.getScriptProperties();
  let id = props.getProperty('SHEET_ID');
  if (!id) {
    const ss = SpreadsheetApp.create(CFG.SHEET_NAME);
    Object.keys(HEAD).forEach((name, i) => {
      const sh = i === 0 ? ss.getSheets()[0].setName(name) : ss.insertSheet(name);
      sh.getRange(1, 1, 1, HEAD[name].length).setValues([HEAD[name]]).setFontWeight('bold');
      sh.setFrozenRows(1);
      sh.getRange('A:Z').setNumberFormat('@'); // tutto testo: niente conversioni automatiche delle date
    });
    id = ss.getId();
    props.setProperty('SHEET_ID', id);
  }
  if (!props.getProperty('TOKEN')) {
    props.setProperty('TOKEN', Utilities.getUuid().replace(/-/g, ''));
  }
  ScriptApp.getProjectTriggers()
    .filter(t => t.getHandlerFunction() === 'scan')
    .forEach(t => ScriptApp.deleteTrigger(t));
  ScriptApp.newTrigger('scan').timeBased().everyDays(1).atHour(7).create();

  Logger.log('Foglio: https://docs.google.com/spreadsheets/d/' + id);
  Logger.log('Avvio prima scansione…');
  scan();
  Logger.log('Fatto. Ora distribuisci come App web e poi esegui linkPWA.');
}

/** Stampa il link pronto per collegare la PWA (URL dell'app web + token). */
function linkPWA() {
  const token = PropertiesService.getScriptProperties().getProperty('TOKEN');
  const url = ScriptApp.getService().getUrl();
  if (!url) { Logger.log('Prima distribuisci il progetto come App web.'); return; }
  const hash = '#u=' + encodeURIComponent(url) + '&t=' + token;
  Logger.log('URL app web: ' + url);
  Logger.log('Token: ' + token);
  Logger.log('Link di collegamento PWA: ' + (CFG.PWA_URL || '<indirizzo-della-pwa>/') + hash);
  Logger.log('Link per l\'app Android (incollalo in Impostazioni): cacciabuoni://setup' + hash);
}

/* ===================================================================== */
/*  SCANSIONE                                                            */
/* ===================================================================== */

function scan() {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(10000)) return { ok: false, error: 'scansione già in corso' };
  const t0 = Date.now();
  try {
    const props = PropertiesService.getScriptProperties();
    const firstDone = props.getProperty('FIRST_DONE') === '1';
    const days = firstDone ? CFG.DAILY_SCAN_DAYS : CFG.FIRST_SCAN_DAYS;
    const since = ' newer_than:' + days + 'd';
    const ss = ss_();
    const seen = seenIds_(ss);
    const add = { Buoni: [], Offerte: [], Cashback: [] };
    const codesSeen = new Set(readObjects_(ss.getSheetByName('Buoni')).map(r => String(r.codice).toUpperCase()).filter(Boolean));
    let complete = true;

    const jobs = [];
    CFG.CASHBACK_SOURCES.forEach(s => jobs.push({ q: s.query, fn: m => parseCashback_(m, s.name) }));
    CFG.CASHBACK_PROMO.forEach(s => jobs.push({ q: s.query, fn: m => parseCashbackPromo_(m, s.name) }));
    CFG.CORPORATE.forEach(s => jobs.push({ q: s.query, fn: m => parseCorporate_(m, s.name) }));
    jobs.push({ q: COUPON_QUERY, fn: m => parseCoupon_(m, codesSeen) });

    outer:
    for (const job of jobs) {
      const threads = GmailApp.search(job.q + since, 0, CFG.MAX_THREADS);
      for (const th of threads) {
        for (const msg of th.getMessages()) {
          if (Date.now() - t0 > CFG.TIME_BUDGET_MS) { complete = false; break outer; }
          const id = msg.getId();
          if (seen.has(id)) continue;
          seen.add(id);
          try {
            const out = job.fn(msg);
            if (out) Object.keys(out).forEach(k => add[k].push(...out[k]));
          } catch (err) {
            console.warn('Errore su ' + id + ': ' + err);
          }
        }
      }
    }

    Object.keys(add).forEach(name => appendObjects_(ss.getSheetByName(name), add[name]));
    if (complete) props.setProperty('FIRST_DONE', '1');
    props.setProperty('LAST_SCAN', new Date().toISOString());
    if (CFG.DIGEST_EMAIL) sendDigest_(ss);
    const res = { ok: true, nuovi: { buoni: add.Buoni.length, offerte: add.Offerte.length, cashback: add.Cashback.length }, completa: complete };
    console.log(JSON.stringify(res));
    return res;
  } finally {
    lock.releaseLock();
  }
}

/* ---------- buoni e codici ---------- */

function parseCoupon_(msg, codesSeen) {
  const subject = msg.getSubject() || '';
  const raw = msg.getPlainBody() || htmlToText_(msg.getBody());
  const text = cleanText(raw);
  const from = senderOf_(msg);
  const ref = msg.getDate();
  const head = subject + '\n' + text.slice(0, 600);

  if (CFG.PUBLIC_SOURCES.some(d => from.domain.endsWith(d))) {
    const neg = merchantFromSubject(subject) || from.name;
    return { Offerte: [{
      id: 'O' + msg.getId(), msgId: msg.getId(), data: iso_(ref), fonte: from.name || from.domain,
      negozio: neg, sconto: extractValue(subject) || extractValue(text), codice: extractCodes(text)[0] || '',
      scadenza: extractExpiry(text, ref), link: gmailLink_(msg), note: subject
    }] };
  }

  const codes = extractCodes(text).filter(c => !codesSeen.has(c.toUpperCase()));
  const value = extractValue(subject) || extractValue(text.slice(0, 1500));
  const personal = /(il tuo|tuo|per te|tuoi|ecco il tuo)\s+(codice|buono|voucher|coupon|gift)|accreditat|gift ?card|carta regalo|buono (?:da|regalo|sconto)/i.test(head);
  if (!codes.length && !(personal && value)) return null;

  const isGift = /gift ?card|buono regalo|carta regalo/i.test(subject);
  const merchant = merchantOf(subject, text, from);
  let note = '';
  if (isGift) {
    const gl = (raw.match(/https?:\/\/[^\s*"<>)]*(?:gate|gift|voucher|card|amilon)[^\s*"<>)]*/i) || [])[0];
    if (gl) note = 'Link gift card: ' + gl;
  }
  const base = {
    msgId: msg.getId(), data: iso_(ref), negozio: merchant, valore: value,
    scadenza: extractExpiry(text, ref), dove: whereOf(text, merchant, isGift),
    tipo: isGift ? 'gift card' : (codes.length ? 'codice' : 'buono'),
    oggetto: subject.slice(0, 200), link: gmailLink_(msg), usato: '', note: note, fonte: 'Gmail'
  };
  const list = codes.length ? codes : [''];
  return { Buoni: list.map((c, i) => {
    if (c) codesSeen.add(c.toUpperCase());
    return Object.assign({}, base, { id: 'B' + msg.getId() + '_' + i, codice: c });
  }) };
}

/* ---------- cashback: saldo e ordini ---------- */

function parseCashback_(msg, service) {
  const subject = msg.getSubject() || '';
  const text = cleanText(msg.getPlainBody() || htmlToText_(msg.getBody()));
  const amount = (subject.match(/(\d+[.,]\d{2})\s?€/) || text.match(/(\d+[.,]\d{2})\s?€\s+(?:il\s+)?cashback/i) || [])[1];
  if (!amount) return null;
  const all = subject + ' ' + text;
  const stato = /annullat|rifiutat|respint/i.test(all) ? 'annullato'
    : /confermat|accreditat/i.test(all) ? 'confermato'
    : /sospeso|attesa/i.test(all) ? 'in sospeso' : '';
  const negozio = ((text.match(/\((?:ordine[^)]*?)(?:\bsu\b|negozio)\s+([^)]+)\)/i) || subject.match(/cashback da\s+(.+)$/i) || [])[1] || '').trim();
  const saldo = (text.match(/saldo attuale[^0-9]{0,15}(\d+[.,]\d{2})/i) || [])[1] || '';
  return { Cashback: [{
    id: 'C' + msg.getId(), msgId: msg.getId(), data: iso_(msg.getDate()), servizio: service,
    negozio: negozio, importo: amount.replace('.', ','), stato: stato, saldo: saldo.replace('.', ','), link: gmailLink_(msg)
  }] };
}

/* ---------- cashback: promo per negozio ---------- */

function parseCashbackPromo_(msg, service) {
  const subject = msg.getSubject() || '';
  const snippet = cleanText(msg.getPlainBody() || htmlToText_(msg.getBody())).slice(0, 400);
  const rate = cashbackRate(subject + ' ' + snippet);
  const store = storeFromPromoSubject(subject);
  if (!store) return null;
  const d = msg.getDate();
  return { Offerte: [{
    id: 'O' + msg.getId(), msgId: msg.getId(), data: iso_(d), fonte: service, negozio: store,
    sconto: rate ? 'cashback ' + rate : 'cashback', codice: '',
    scadenza: iso_(new Date(d.getTime() + 3 * 864e5)), link: gmailLink_(msg),
    note: 'Attiva il cashback prima di mettere nel carrello. Codici sconto esterni possono annullarlo.'
  }] };
}

/* ---------- convenzioni aziendali ---------- */

function parseCorporate_(msg, service) {
  const html = msg.getBody() || '';
  const text = cleanText(htmlToText_(html));
  const d = msg.getDate();
  const portal = (html.match(/https?:\/\/[a-z0-9-]+\.convenzioniaziendali\.it\/?[^"'\s<>]*/i) || [])[0] || '';
  const portalBase = portal ? portal.replace(/^(https?:\/\/[^/]+).*$/, '$1') : '';
  const eom = new Date(d.getFullYear(), d.getMonth() + 1, 0);
  const rows = [{
    id: 'O' + msg.getId(), msgId: msg.getId(), data: iso_(d), fonte: service, negozio: 'Convenzioni del mese',
    sconto: '', codice: '', scadenza: iso_(eom), link: gmailLink_(msg), note: portalBase ? 'Portale: ' + portalBase : ''
  }];
  corporateBrands(text).forEach((b, i) => rows.push({
    id: 'O' + msg.getId() + '_' + i, msgId: msg.getId() + '_' + i, data: iso_(d), fonte: service,
    negozio: b.brand, sconto: b.sconto, codice: '', scadenza: iso_(eom), link: portalBase || gmailLink_(msg),
    note: 'Estratto dalla newsletter: verifica sul portale'
  }));
  return { Offerte: rows };
}

/* ===================================================================== */
/*  PARSER (stessa logica usata anche nella PWA)                          */
/* ===================================================================== */

const STOP = new Set(['CODICE', 'SCONTO', 'COUPON', 'PROMO', 'EXTRA', 'GRATIS', 'SUBITO', 'ACQUISTA', 'APPLICA',
  'CARRELLO', 'CASSA', 'ORDINA', 'SCOPRI', 'VALIDO', 'BUONO', 'VOUCHER', 'OFFERTA', 'SPECIALE', 'ESCLUSIVO', 'TUTTO',
  'NOVITA', 'BLACK', 'FRIDAY', 'CLICCA', 'SHOP', 'ONLINE', 'STORE', 'EMAIL', 'GIOCA', 'SALDI', 'DONNA', 'UOMO',
  'BAMBINI', 'REGALO', 'ADESSO', 'RISCATTA', 'CONTROLLA', 'DETTAGLI', 'NEWSLETTER', 'EURO', 'CASHBACK', 'ORDINE',
  'SPEDIZIONE', 'CONSEGNA', 'GRATUITA', 'PREZZO', 'PRODOTTI', 'ARTICOLI', 'ACCEDI', 'REGISTRATI', 'COPIA', 'INSERISCI']);

function cleanText(s) {
  return String(s || '')
    .replace(/https?:\/\/[^\s)\]>"'*]+/g, ' ')
    .replace(/<[^>\n]{0,300}>/g, ' ')
    .replace(/\[[^\]]*\]\([^)]*\)/g, ' ')
    .replace(/[\u034F\u200B-\u200D\uFEFF\u00AD\u2060]/g, '')
    .replace(/[ \t\u00A0]+/g, ' ');
}

function htmlToText_(html) {
  return String(html || '')
    .replace(/<(style|script|head)[\s\S]*?<\/\1>/gi, ' ')
    .replace(/<img[^>]*alt="([^"]*)"[^>]*>/gi, ' $1 ')
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<\/(p|div|tr|td|th|h\d|li|table)>/gi, '\n')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&').replace(/&quot;/g, '"').replace(/&#39;|&rsquo;/g, "'")
    .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&euro;/g, '€').replace(/&#(\d+);/g, (m, n) => String.fromCharCode(+n));
}

function isCodeToken(t) {
  if (t.length < 5 || t.length > 25) return false;
  if (!/[A-Za-z]/.test(t)) return false;
  if (/^\d+(gb|tb|mb|ml|kg|mm|cm|hz|mah|pz|cl)$/i.test(t)) return false;
  if (/^(19|20)\d\d$/.test(t)) return false;
  if (/\d/.test(t)) return true;
  return t === t.toUpperCase() && !STOP.has(t);
}

function codeScore(t, dist) {
  const digits = (t.match(/\d/g) || []).length;
  return (digits >= 2 ? 3 : digits ? 1 : 0) + (t === t.toUpperCase() ? 2 : 0) + (t.length >= 6 ? 1 : 0) + (dist < 40 ? 2 : 0);
}

function extractCodes(text) {
  const kw = /(codice(?:\s+(?:sconto|promo(?:zionale)?|coupon|del tuo buono(?:\s+personale)?|esclusivo|personale))?|coupon(?:\s+code)?|promo\s*code|voucher\s*code|discount\s*code|usa il codice)/gi;
  const neg = /^\s*(?:modello|cliente|fiscale|carta|articolo|prodotto|ordine|postale|iban|per partecipare|gioco|N°)/i;
  const best = {};
  let m;
  while ((m = kw.exec(text))) {
    const after = text.substr(m.index + m[0].length, 110);
    if (neg.test(after)) continue;
    const re = /[A-Za-z0-9][A-Za-z0-9-]*[A-Za-z0-9]/g;
    let t;
    while ((t = re.exec(after))) {
      const tok = t[0];
      if (!isCodeToken(tok)) continue;
      const s = codeScore(tok, t.index);
      if (s >= 4 && (!best[tok] || best[tok] < s)) best[tok] = s;
    }
  }
  return Object.keys(best).sort((a, b) => best[b] - best[a]).slice(0, 2);
}

const MESI = { gennaio: 1, febbraio: 2, marzo: 3, aprile: 4, maggio: 5, giugno: 6, luglio: 7, agosto: 8, settembre: 9, ottobre: 10, novembre: 11, dicembre: 12 };

function mkDate_(d, mo, y, ref) {
  let yy = y || ref.getFullYear();
  let dt = new Date(yy, mo - 1, d);
  if (!y && dt < new Date(ref.getFullYear(), ref.getMonth(), ref.getDate() - 1)) dt = new Date(yy + 1, mo - 1, d);
  return dt;
}

function datesIn(s, ref) {
  const out = [];
  let m;
  const re1 = /(\d{1,2})[\/.\-](\d{1,2})(?:[\/.\-](\d{2,4}))?(?![\d,])/g;
  while ((m = re1.exec(s))) {
    const d = +m[1], mo = +m[2];
    if (d < 1 || d > 31 || mo < 1 || mo > 12) continue;
    let y = m[3] ? +m[3] : null;
    if (y !== null && y < 100) y += 2000;
    out.push(mkDate_(d, mo, y, ref));
  }
  const re2 = /(\d{1,2})°?\s+(gennaio|febbraio|marzo|aprile|maggio|giugno|luglio|agosto|settembre|ottobre|novembre|dicembre)(?:\s+(\d{4}))?/gi;
  while ((m = re2.exec(s))) out.push(mkDate_(+m[1], MESI[m[2].toLowerCase()], m[3] ? +m[3] : null, ref));
  const re3 = /(?:fra|tra)\s+(\d{1,3})\s+giorni/gi;
  while ((m = re3.exec(s))) out.push(new Date(ref.getFullYear(), ref.getMonth(), ref.getDate() + +m[1]));
  return out;
}

/** Scadenza: la data più vicina (non passata rispetto alla mail) vicino a parole come "scade", "valido fino", "entro". */
function extractExpiry(text, ref) {
  ref = ref || new Date();
  const kw = /(scad\w*|valid\w*|fino\s+al?|entro|utilizzabile|spendibile|utilizzat\w+|termina|until|expires?)/gi;
  const day0 = new Date(ref.getFullYear(), ref.getMonth(), ref.getDate());
  let best = null, m;
  while ((m = kw.exec(text))) {
    const win = text.substr(m.index, 70);
    datesIn(win, ref).forEach(d => { if (d >= day0 && (!best || d < best)) best = d; });
  }
  return best ? iso_(best) : '';
}

function extractValue(s) {
  s = String(s || '');
  let m = s.match(/(\d{1,3}(?:[.,]\d{1,2})?)\s?(%|€|euro\b|eur\b)/i);
  if (m) return m[2] === '%' ? m[1] + '%' : m[1].replace('.', ',') + ' €';
  m = s.match(/€\s?(\d{1,4}(?:[.,]\d{1,2})?)/);
  return m ? m[1].replace('.', ',') + ' €' : '';
}

function cashbackRate(s) {
  const m = String(s).match(/cashback(?:\s+X\d+)?\s+(?:fino\s+al\s+|del\s+|al\s+)?(\d{1,2}(?:[.,]\d{1,2})?)\s?%/i);
  return m ? m[1].replace(',', '.') + '%' : '';
}

const EMOJI_RE = /[\u2190-\u2BFF\u2600-\u27BF\u3000-\u303F\uFE0F]|[\uD83C-\uD83E][\uDC00-\uDFFF]/g;

function storeFromPromoSubject(subject) {
  const parts = String(subject).split(EMOJI_RE).map(p => p.trim()).filter(Boolean);
  if (!parts.length) return '';
  let s = parts[0].split(/[:!?]| - /)[0].trim();
  s = s.replace(/\b(Black Friday|Cyber Monday|Singles'? Day|\d+\.\d+)\b.*$/i, '').trim() || s;
  return (s.length > 40 || /cashback|letyshops|lety\b/i.test(s)) ? '' : s;
}

function merchantFromSubject(subject) {
  const s = String(subject).replace(/^\s*(fwd?|i|r|re)\s*:\s*/i, '').replace(EMOJI_RE, '').trim();
  const m = s.match(/^([^:]{2,30}):/);
  return m ? m[1].trim() : '';
}

function merchantOf(subject, text, from) {
  const gift = subject.match(/gift ?card\s+([A-Z][\w.]+)/i);
  if (gift) return gift[1];
  const fwd = text.match(/(?:^|\n)\s*(?:Da|From)\s*:\s*([^<\n]+?)\s*</i);
  const isAgg = CFG.AGGREGATORS.some(d => from.domain.indexOf(d) >= 0);
  if (isAgg || /^\s*(fwd?|i)\s*:/i.test(subject)) {
    const per = subject.match(/\bper\s+([A-Z][\w&' .-]{1,30}?)\s*$/);
    if (per) return per[1].trim();
    if (fwd && !/@/.test(fwd[1])) return fwd[1].trim();
    const c = merchantFromSubject(subject);
    if (c) return c;
  }
  return from.name || from.domain;
}

function whereOf(text, merchant, isGift) {
  const m = text.match(/sezione\s+["“']?(Buoni|Voucher|Gift Card|Coupon|Premi)["”']?/i);
  if (m) return 'App/sito ' + merchant + ' › sezione ' + m[1];
  if (/scheda cliente/i.test(text)) return 'Account ' + merchant + ' (scheda cliente)';
  if (/accreditat\w*.{0,60}\bapp\b/i.test(text)) return 'App ' + merchant;
  if (isGift && /link/i.test(text)) return 'Link nella mail';
  if (/(totem|in negozio|punto vendita)/i.test(text) && !/online|sito|carrello/i.test(text)) return 'In negozio';
  return '';
}

function corporateBrands(text) {
  const out = [], seen = new Set();
  const re = /([A-Z][A-Za-z0-9&'’.]+(?:\s[A-Z][A-Za-z0-9&'’.]+){0,3})\s*(?:[:|–-]\s*)?(?:fino al\s*|fino a\s*|sconto (?:del\s*)?)?-?\s?(\d{1,2})\s?%/g;
  const bad = /^(Sconto|Sconti|Fino|Extra|Offerta|Offerte|Risparmia|Ottieni|Speciale|Il|La|Le|Lo|Su|Per|Con|Fino Al|Solo|Tutti|Tutto|IVA)$/i;
  let m;
  while ((m = re.exec(text)) && out.length < 30) {
    const brand = m[1].trim();
    if (bad.test(brand) || brand.length < 2 || seen.has(brand.toLowerCase())) continue;
    seen.add(brand.toLowerCase());
    out.push({ brand: brand, sconto: m[2] + '%' });
  }
  return out;
}

/* ===================================================================== */
/*  API per la PWA                                                        */
/* ===================================================================== */

function doGet(e) {
  const p = (e && e.parameter) || {};
  if (!authOk_(p.token)) return json_({ ok: false, error: 'token non valido' });
  if (p.action === 'scan') return json_(scan());
  return json_(snapshot_());
}

function doPost(e) {
  let b = {};
  try { b = JSON.parse(e.postData.contents); } catch (err) { return json_({ ok: false, error: 'JSON non valido' }); }
  if (!authOk_(b.token)) return json_({ ok: false, error: 'token non valido' });
  const sh = ss_().getSheetByName('Buoni');
  if (b.action === 'aggiungi') {
    const r = Object.assign({ id: 'M' + Date.now(), msgId: '', data: iso_(new Date()), tipo: 'manuale', usato: '', fonte: 'Manuale', oggetto: '', link: '' }, pick_(b.item, HEAD.Buoni));
    appendObjects_(sh, [r]);
    return json_({ ok: true, item: r });
  }
  if (b.action === 'modifica' || b.action === 'usato') {
    const fields = b.action === 'usato' ? { usato: b.value ? iso_(new Date()) : '' } : pick_(b.fields, HEAD.Buoni);
    delete fields.id;
    return json_({ ok: updateRow_(sh, b.id, fields) });
  }
  if (b.action === 'elimina') return json_({ ok: deleteRow_(sh, b.id) });
  return json_({ ok: false, error: 'azione sconosciuta' });
}

function snapshot_() {
  const ss = ss_();
  const today = iso_(new Date());
  const cutoff = iso_(new Date(Date.now() - CFG.OFFER_TTL_DAYS * 864e5));
  const buoni = readObjects_(ss.getSheetByName('Buoni'));
  const offerte = readObjects_(ss.getSheetByName('Offerte'))
    .filter(o => o.scadenza ? o.scadenza >= today : o.data >= cutoff);
  const cash = readObjects_(ss.getSheetByName('Cashback')).sort((a, b) => a.data < b.data ? 1 : -1);
  const saldi = {};
  cash.forEach(c => { if (c.saldo && !saldi[c.servizio]) saldi[c.servizio] = { saldo: c.saldo, al: c.data }; });
  const portali = {};
  offerte.forEach(o => { const m = /Portale: (\S+)/.exec(o.note || ''); if (m) portali[o.fonte] = m[1]; });
  return { ok: true, aggiornato: PropertiesService.getScriptProperties().getProperty('LAST_SCAN'),
    buoni: buoni, offerte: offerte, cashback: { eventi: cash.slice(0, 50), saldi: saldi }, portali: portali };
}

/* ===================================================================== */
/*  RIEPILOGO EMAIL                                                       */
/* ===================================================================== */

function sendDigest_(ss) {
  const props = PropertiesService.getScriptProperties();
  const today = iso_(new Date());
  if (props.getProperty('DIGEST_DAY') === today) return;
  const limit = iso_(new Date(Date.now() + CFG.DIGEST_DAYS * 864e5));
  const due = readObjects_(ss.getSheetByName('Buoni'))
    .filter(r => !r.usato && r.scadenza && r.scadenza >= today && r.scadenza <= limit)
    .sort((a, b) => a.scadenza < b.scadenza ? -1 : 1);
  if (!due.length) return;
  const rows = due.map(r => '<tr><td style="padding:4px 8px"><b>' + esc_(r.negozio) + '</b></td><td style="padding:4px 8px">' +
    esc_(r.valore) + '</td><td style="padding:4px 8px;font-family:monospace">' + esc_(r.codice || r.dove || '—') +
    '</td><td style="padding:4px 8px">' + r.scadenza.split('-').reverse().join('/') + '</td></tr>').join('');
  MailApp.sendEmail({
    to: Session.getEffectiveUser().getEmail(),
    subject: '⏰ ' + due.length + ' buoni in scadenza',
    htmlBody: '<p>Questi buoni scadono nei prossimi ' + CFG.DIGEST_DAYS + ' giorni:</p><table style="border-collapse:collapse">' + rows + '</table>' +
      (CFG.PWA_URL ? '<p><a href="' + CFG.PWA_URL + '">Apri Cacciabuoni</a></p>' : '')
  });
  props.setProperty('DIGEST_DAY', today);
}

/* ===================================================================== */
/*  UTILITÀ                                                               */
/* ===================================================================== */

function ss_() {
  const id = PropertiesService.getScriptProperties().getProperty('SHEET_ID');
  if (!id) throw new Error('Esegui prima setup()');
  return SpreadsheetApp.openById(id);
}

function authOk_(t) {
  const tok = PropertiesService.getScriptProperties().getProperty('TOKEN');
  return tok && t === tok;
}

function json_(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}

function iso_(d) {
  return Utilities.formatDate(d, Session.getScriptTimeZone(), 'yyyy-MM-dd');
}

function gmailLink_(msg) {
  return 'https://mail.google.com/mail/u/0/#all/' + msg.getId();
}

function senderOf_(msg) {
  const f = msg.getFrom() || '';
  const name = ((f.match(/^\s*"?([^"<]+?)"?\s*</) || [])[1] || '').trim();
  const addr = ((f.match(/<([^>]+)>/) || [])[1] || f).trim().toLowerCase();
  return { name: name, addr: addr, domain: addr.split('@')[1] || '' };
}

function esc_(s) {
  return String(s || '').replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
}

function pick_(o, keys) {
  const r = {};
  keys.forEach(k => { if (o && o[k] !== undefined) r[k] = String(o[k]); });
  return r;
}

function readObjects_(sh) {
  const v = sh.getDataRange().getValues();
  const h = v.shift() || [];
  const tz = Session.getScriptTimeZone();
  return v.filter(r => r[0] !== '').map(r => {
    const o = {};
    h.forEach((k, i) => { const x = r[i]; o[k] = x instanceof Date ? Utilities.formatDate(x, tz, 'yyyy-MM-dd') : String(x); });
    return o;
  });
}

function appendObjects_(sh, objs) {
  if (!objs.length) return;
  const h = sh.getRange(1, 1, 1, sh.getLastColumn()).getValues()[0];
  const rows = objs.map(o => h.map(k => o[k] === undefined ? '' : String(o[k])));
  sh.getRange(sh.getLastRow() + 1, 1, rows.length, h.length).setNumberFormat('@').setValues(rows);
}

function findRow_(sh, id) {
  const ids = sh.getRange(1, 1, sh.getLastRow(), 1).getValues();
  for (let i = 1; i < ids.length; i++) if (String(ids[i][0]) === String(id)) return i + 1;
  return -1;
}

function updateRow_(sh, id, fields) {
  const row = findRow_(sh, id);
  if (row < 0) return false;
  const h = sh.getRange(1, 1, 1, sh.getLastColumn()).getValues()[0];
  Object.keys(fields).forEach(k => {
    const c = h.indexOf(k);
    if (c >= 0) sh.getRange(row, c + 1).setNumberFormat('@').setValue(fields[k]);
  });
  return true;
}

function deleteRow_(sh, id) {
  const row = findRow_(sh, id);
  if (row < 0) return false;
  sh.deleteRow(row);
  return true;
}

function seenIds_(ss) {
  const s = new Set();
  ['Buoni', 'Offerte', 'Cashback'].forEach(n => {
    const sh = ss.getSheetByName(n);
    if (sh.getLastRow() < 2) return;
    sh.getRange(2, 2, sh.getLastRow() - 1, 1).getValues().forEach(r => { if (r[0]) s.add(String(r[0]).split('_')[0]); });
  });
  return s;
}

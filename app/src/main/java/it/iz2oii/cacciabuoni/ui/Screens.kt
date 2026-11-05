@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package it.iz2oii.cacciabuoni.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.iz2oii.cacciabuoni.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/* ===================================================================== */
/*  Tema                                                                  */
/* ===================================================================== */

private val Teal = Color(0xFF0F766E)
private val TealLight = Color(0xFF2DD4BF)

@Composable
fun CacciabuoniTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) darkColorScheme(primary = TealLight, onPrimary = Color(0xFF062A26), secondaryContainer = Color(0xFF12302C), onSecondaryContainer = TealLight)
    else lightColorScheme(primary = Teal, onPrimary = Color.White, secondaryContainer = Color(0xFFE6F4F1), onSecondaryContainer = Teal, background = Color(0xFFF6F5F1), surface = Color(0xFFF6F5F1))
    MaterialTheme(colorScheme = scheme, content = content)
}

private val Hot = Color(0xFFDC2626)
private val Warn = Color(0xFFEA580C)

/* ===================================================================== */
/*  Navigazione                                                           */
/* ===================================================================== */

data class Prefill(val text: String? = null, val image: Uri? = null)

@Composable
fun AppRoot(launch: MutableStateFlow<Launch?>) {
    var screen by rememberSaveable { mutableStateOf("home") }
    var prefill by remember { mutableStateOf(Prefill()) }
    var query by rememberSaveable { mutableStateOf("") }
    val l by launch.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    LaunchedEffect(l) {
        val x = l ?: return@LaunchedEffect
        when {
            x.text != null || x.image != null -> { prefill = Prefill(x.text, x.image); screen = "add" }
            x.store != null -> { query = x.store; screen = "home" }
            x.link != null -> { if (Store.applyLink(x.link)) { toast(ctx, "Collegato allo script"); Store.syncInBackground() } }
        }
        launch.value = null
    }
    BackHandler(enabled = screen != "home") { screen = if (screen == "apps") "settings" else "home" }

    when (screen) {
        "settings" -> SettingsScreen(onBack = { screen = "home" }, onApps = { screen = "apps" })
        "apps" -> AppsScreen(onBack = { screen = "settings" })
        "add" -> AddScreen(prefill, onDone = { prefill = Prefill(); screen = "home" })
        else -> HomeScreen(query, { query = it }, onSettings = { screen = "settings" }, onAdd = { prefill = Prefill(); screen = "add" })
    }
}

fun toast(ctx: Context, s: String) = android.widget.Toast.makeText(ctx, s, android.widget.Toast.LENGTH_SHORT).show()

/* ===================================================================== */
/*  Home                                                                  */
/* ===================================================================== */

@Composable
fun HomeScreen(query: String, onQuery: (String) -> Unit, onSettings: () -> Unit, onAdd: () -> Unit) {
    val snap by Store.snapshot.collectAsStateWithLifecycle()
    val status by Store.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var filter by rememberSaveable { mutableStateOf("attivi") }
    var refreshing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { if (Store.configured) Store.sync() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cacciabuoni", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = {
                        scope.launch { refreshing = true; Store.sync().onFailure { toast(ctx, it.message ?: "Errore") }; refreshing = false }
                    }) { if (refreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Refresh, "Aggiorna") }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Impostazioni") }
                }
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = onAdd) { Icon(Icons.Default.Add, "Aggiungi") } }
    ) { pad ->
        Column(Modifier.padding(pad)) {
            OutlinedTextField(
                value = query, onValueChange = onQuery, singleLine = true,
                placeholder = { Text("Cerca negozio o codice…") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Default.Clear, "Cancella") } },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            )
            val active = snap.buoni.count { it.attivo }
            TabRow(selectedTabIndex = tab, modifier = Modifier.padding(top = 8.dp)) {
                listOf("Buoni" + if (active > 0) " $active" else "", "Offerte" + if (snap.offerte.isNotEmpty()) " ${snap.offerte.size}" else "", "Cashback")
                    .forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
            }
            LazyColumn(contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!Store.configured) item { InfoCard("Collega il tuo Gmail", "Vai in ⚙ e incolla il link stampato da linkPWA nello script. Intanto puoi aggiungere buoni a mano con +.") }
                if (query.isNotBlank()) item { BeforeBuy(query, snap) }
                when (tab) {
                    0 -> {
                        item { Filters(filter) { filter = it } }
                        val list = snap.buoni.filter {
                            when (filter) {
                                "scadenza" -> it.usato.isEmpty() && (it.giorni()?.let { g -> g in 0L..7L } == true)
                                "usati" -> it.usato.isNotEmpty()
                                "scaduti" -> it.scaduto
                                "tutti" -> true
                                else -> it.attivo
                            }
                        }.filter { matches(query, it.negozio, it.codice, it.oggetto, it.note, it.dove, it.fonte) }
                            .sortedBy { it.scadenza.ifEmpty { "9999" } }
                        if (list.isEmpty()) item { Empty(if (filter == "attivi") "Nessun buono attivo" else "Niente con questo filtro") }
                        items(list, key = { it.id }) { BuonoCard(it) }
                    }
                    1 -> {
                        val list = snap.offerte.filter { matches(query, it.negozio, it.fonte, it.codice, it.note) }.sortedByDescending { it.data }
                        if (list.isEmpty()) item { Empty("Nessuna offerta: compaiono dopo la scansione di Gmail") }
                        items(list, key = { it.id }) { OffertaCard(it) }
                    }
                    else -> {
                        items(snap.saldi) { s ->
                            ElevatedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(s.servizio.uppercase(), style = MaterialTheme.typography.labelMedium)
                                    Text("${s.saldo} €", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                                    Text("saldo al ${dmy(s.al)}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        if (snap.saldi.isEmpty()) item { Empty("Nessun saldo cashback ancora letto") }
                        items(snap.eventi) { e ->
                            OutlinedCard(Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(e.negozio.ifEmpty { e.servizio }, fontWeight = FontWeight.SemiBold)
                                        Text("${e.stato.ifEmpty { "—" }} · ${dmy(e.data)} · ${e.servizio}", style = MaterialTheme.typography.bodySmall)
                                    }
                                    ValueChip("${e.importo} €")
                                }
                            }
                        }
                    }
                }
                item { Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), color = MaterialTheme.colorScheme.outline) }
            }
        }
    }
}

private fun matches(q: String, vararg f: String) = q.isBlank() || f.joinToString(" ").contains(q.trim(), ignoreCase = true) ||
    Parser.sameStore(f.firstOrNull(), q)

private fun dmy(iso: String) = iso.split('-').reversed().joinToString("/")

@Composable
private fun Filters(cur: String, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("attivi" to "Attivi", "scadenza" to "In scadenza", "usati" to "Usati", "scaduti" to "Scaduti", "tutti" to "Tutti").forEach { (k, l) ->
            FilterChip(selected = cur == k, onClick = { onPick(k) }, label = { Text(l) })
        }
    }
}

@Composable
private fun InfoCard(title: String, text: String) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) { Text(title, fontWeight = FontWeight.Bold); Text(text, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun Empty(t: String) = Text(t, modifier = Modifier.fillMaxWidth().padding(32.dp), color = MaterialTheme.colorScheme.outline)

@Composable
private fun ValueChip(t: String) = Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(8.dp)) {
    Text(t, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.onSecondaryContainer, fontWeight = FontWeight.Bold)
}

@Composable
private fun ExpiryText(iso: String) {
    if (iso.isEmpty()) return
    val g = runCatching { java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(iso)) }.getOrNull() ?: return
    val (t, c) = when {
        g < 0 -> "scaduto ${dmy(iso)}" to MaterialTheme.colorScheme.outline
        g == 0L -> "scade oggi" to Hot
        g == 1L -> "scade domani" to Hot
        g <= 7 -> "tra $g giorni · ${dmy(iso)}" to Warn
        else -> "fino al ${dmy(iso)}" to MaterialTheme.colorScheme.outline
    }
    Text(t, color = c, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun CodeBox(code: String) {
    val clip = LocalClipboardManager.current
    val ctx = LocalContext.current
    Surface(
        shape = RoundedCornerShape(10.dp), border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clickable { clip.setText(AnnotatedString(code)); toast(ctx, "Codice copiato") }
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(code, fontFamily = FontFamily.Monospace, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("Copia", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun BuonoCard(b: Buono) {
    val ctx = LocalContext.current
    var ask by remember { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth().alpha(if (b.usato.isNotEmpty()) 0.55f else 1f)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(b.negozio.ifEmpty { "—" }, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (b.valore.isNotEmpty()) ValueChip(b.valore)
            }
            if (b.codice.isNotEmpty()) CodeBox(b.codice)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                ExpiryText(b.scadenza)
                if (b.tipo.isNotEmpty()) Text(b.tipo, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                if (b.usato.isNotEmpty()) Text("usato il ${dmy(b.usato)}", style = MaterialTheme.typography.bodySmall)
            }
            if (b.dove.isNotEmpty()) Text("📍 ${b.dove}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            if (b.oggetto.isNotEmpty()) Text(b.oggetto, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.outline)
            val giftLink = Regex("""https?://\S+""").find(b.note)?.value
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                if (b.usato.isEmpty()) Button(onClick = { Store.setUsed(b.id, true) }) { Text("Usato") }
                else OutlinedButton(onClick = { Store.setUsed(b.id, false) }) { Text("Riattiva") }
                val link = giftLink ?: b.link
                if (link.isNotEmpty()) OutlinedButton(onClick = { openUrl(ctx, link) }) { Text(if (giftLink != null) "Gift card" else "Mail") }
                TextButton(onClick = { ask = true }) { Text("Elimina") }
            }
        }
    }
    if (ask) AlertDialog(
        onDismissRequest = { ask = false },
        title = { Text("Eliminare il buono?") },
        text = { Text("${b.negozio} ${b.valore}".trim()) },
        confirmButton = { TextButton(onClick = { ask = false; Store.delete(b.id) }) { Text("Elimina") } },
        dismissButton = { TextButton(onClick = { ask = false }) { Text("Annulla") } }
    )
}

@Composable
fun OffertaCard(o: Offerta) {
    val ctx = LocalContext.current
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(o.fonte.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.negozio, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                if (o.sconto.isNotEmpty()) ValueChip(o.sconto)
            }
            if (o.codice.isNotEmpty()) CodeBox(o.codice)
            ExpiryText(o.scadenza)
            if (o.note.isNotEmpty()) Text(o.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            if (o.link.isNotEmpty()) TextButton(onClick = { openUrl(ctx, o.link) }, contentPadding = PaddingValues(0.dp)) { Text("Apri") }
        }
    }
}

@Composable
private fun BeforeBuy(q: String, snap: Snapshot) {
    val ctx = LocalContext.current
    val (mine, off) = snap.perNegozio(q)
    val lety = off.filter { it.sconto.contains("cashback", true) }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("Prima di comprare da «${q.trim()}»", fontWeight = FontWeight.Bold)
            if (mine.isEmpty() && off.isEmpty()) Text("Nessun buono tuo o cashback per questo negozio.", style = MaterialTheme.typography.bodyMedium)
            mine.forEach { Text("• ${it.valore.ifEmpty { "buono" }} ${it.negozio}" + if (it.codice.isNotEmpty()) " · ${it.codice}" else "") }
            off.forEach { Text("• ${it.fonte}: ${it.sconto} ${it.negozio}") }
            if (mine.any { it.codice.isNotEmpty() } && lety.isNotEmpty())
                Text("⚠ Un codice non LetyShops può annullare il cashback: confronta cosa conviene.", color = Warn, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                val g = { s: String -> "https://www.google.com/search?q=" + Uri.encode(s) }
                AssistChip(onClick = { openUrl(ctx, g("codice sconto ${q.trim()}")) }, label = { Text("Codici pubblici") })
                AssistChip(onClick = { openUrl(ctx, g("${q.trim()} cashback site:letyshops.com")) }, label = { Text("LetyShops") })
                snap.portali.values.firstOrNull()?.let { p -> AssistChip(onClick = { openUrl(ctx, p) }, label = { Text("Convenzioni aziendali") }) }
            }
        }
    }
}

fun openUrl(ctx: Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/* ===================================================================== */
/*  Aggiunta manuale / da condivisione / da foto                          */
/* ===================================================================== */

@Composable
fun AddScreen(prefill: Prefill, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var negozio by remember { mutableStateOf("") }
    var codice by remember { mutableStateOf("") }
    var valore by remember { mutableStateOf("") }
    var scadenza by remember { mutableStateOf("") }
    var dove by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("Per i codici dei social: fai uno screenshot o usa Condividi › Cacciabuoni.") }

    fun fill(raw: String) {
        val f = Parser.analyze(raw)
        if (codice.isEmpty()) codice = f.codice
        if (valore.isEmpty()) valore = f.valore
        if (scadenza.isEmpty()) scadenza = f.scadenza?.toString() ?: ""
        if (note.isEmpty()) note = raw.replace(Regex("\\s+"), " ").take(160)
        if (negozio.isEmpty()) {
            // indizio: @account o dominio nel testo
            negozio = Regex("""(?:www\.)?([a-z0-9-]{3,})\.(?:it|com|eu)\b""", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.get(1)
                ?.replaceFirstChar { it.uppercase() } ?: ""
        }
        msg = if (f.codice.isNotEmpty()) "Codice trovato: controlla i campi." else "Nessun codice riconosciuto: completalo a mano."
    }

    fun ocr(uri: Uri) {
        msg = "Lettura immagine…"
        scope.launch {
            runCatching { withContext(Dispatchers.Default) { Ocr.read(ctx, uri) } }
                .onSuccess { fill(it) }.onFailure { msg = "Lettura non riuscita: inserisci i dati a mano." }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(::ocr) }
    LaunchedEffect(prefill) {
        prefill.text?.let(::fill)
        prefill.image?.let(::ocr)
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Nuovo buono") }, navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.Default.ArrowBack, "Indietro") } })
    }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("📷 Da foto") }
                OutlinedButton(onClick = { clip.getText()?.text?.let(::fill) ?: toast(ctx, "Appunti vuoti") }) { Text("📋 Incolla") }
            }
            Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            OutlinedTextField(negozio, { negozio = it }, label = { Text("Negozio") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words))
            OutlinedTextField(codice, { codice = it }, label = { Text("Codice") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(valore, { valore = it }, label = { Text("Valore") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(scadenza, { scadenza = it }, label = { Text("Scadenza (AAAA-MM-GG)") }, singleLine = true, modifier = Modifier.weight(1.3f))
            }
            OutlinedTextField(dove, { dove = it }, label = { Text("Dove si trova / come si usa") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(note, { note = it }, label = { Text("Note / fonte") }, modifier = Modifier.fillMaxWidth())
            Button(
                onClick = {
                    val exp = scadenza.trim().let { s -> runCatching { LocalDate.parse(s).toString() }.getOrElse { Parser.datesIn(s, LocalDate.now()).firstOrNull()?.toString() ?: "" } }
                    Store.add(Buono(id = "A" + System.currentTimeMillis(), data = LocalDate.now().toString(), negozio = negozio.trim(),
                        codice = codice.trim(), valore = valore.trim(), scadenza = exp, dove = dove.trim(), tipo = "manuale",
                        note = note.trim(), fonte = "App"))
                    toast(ctx, "Buono salvato"); onDone()
                },
                enabled = negozio.isNotBlank(), modifier = Modifier.fillMaxWidth()
            ) { Text("Salva") }
        }
    }
}

/* ===================================================================== */
/*  Impostazioni e permessi                                               */
/* ===================================================================== */

private fun listenerOn(ctx: Context) = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
private fun accessibilityOn(ctx: Context) = (Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
    .contains(ComponentName(ctx, StoreWatcher::class.java).flattenToString())

@Composable
fun SettingsScreen(onBack: () -> Unit, onApps: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf("") }
    var url by remember { mutableStateOf(Store.url) }
    var token by remember { mutableStateOf(Store.token) }
    var alerts by remember { mutableStateOf(Store.storeAlerts) }
    var tick by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Impostazioni") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Indietro") } })
    }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Collegamento a Gmail", fontWeight = FontWeight.Bold)
            Text("Incolla il link stampato dalla funzione linkPWA dello script: URL e token si compilano da soli.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(link, { v -> link = v; if (Store.applyLink(v)) { url = Store.url; token = Store.token; toast(ctx, "Collegato") } },
                label = { Text("Link di collegamento") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(url, { url = it }, label = { Text("URL app web (/exec)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(token, { token = it }, label = { Text("Token") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { Store.url = url; Store.token = token; scope.launch { Store.sync().fold({ toast(ctx, "Collegato e aggiornato") }, { toast(ctx, it.message ?: "Errore") }) } }) { Text("Salva") }
                OutlinedButton(enabled = !busy, onClick = {
                    Store.url = url; Store.token = token; busy = true
                    scope.launch { Store.sync(scan = true).fold({ toast(ctx, it.ifEmpty { "Fatto" }) }, { toast(ctx, it.message ?: "Errore") }); busy = false }
                }) { Text(if (busy) "Controllo…" else "Ricontrolla Gmail ora") }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Permessi", fontWeight = FontWeight.Bold)
            key(tick) {
                if (Build.VERSION.SDK_INT >= 33) PermRow("Mostrare notifiche", "Per scadenze e avvisi negozio", Notifier.canPost(ctx)) {
                    notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                PermRow("Leggere le notifiche", "Salva i buoni dalle notifiche delle app scelte", listenerOn(ctx)) {
                    ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                PermRow("Riconoscere l'app aperta", "Accessibilità › Cacciabuoni: avvisa quando apri un negozio. Non legge lo schermo.", accessibilityOn(ctx)) {
                    ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Avvisami quando apro un negozio")
                    Text("Se hai buoni o cashback per quell'app (al massimo ogni 4 ore)", style = MaterialTheme.typography.bodySmall)
                }
                Switch(alerts, { alerts = it; Store.storeAlerts = it })
            }
            OutlinedButton(onClick = onApps, modifier = Modifier.fillMaxWidth()) { Text("App da ascoltare (${Store.watched.size})") }
        }
    }
}

@Composable
private fun PermRow(title: String, sub: String, ok: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) { Text(title); Text(sub, style = MaterialTheme.typography.bodySmall) }
        if (ok) Text("✓ attivo", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        else Button(onClick = onClick) { Text("Attiva") }
    }
}

/* ---------- scelta app da ascoltare ---------- */

private data class AppInfo(val pkg: String, val label: String, val suggested: Boolean)

private val SHOP_HINT = Regex("""(shop|store|amazon|zalando|temu|shein|ebay|aliexpress|esselunga|coop|conad|carrefour|lidl|ikea|decathlon|zara|h&m|mediaworld|unieuro|euronics|nespresso|samsung|letyshops|glovo|deliveroo|just ?eat|vinted|notino|douglas|sephora|redcare|farmacia|trenitalia|italo|payback|enilive|q8|ip |tamoil|mc ?donald|burger|kfc|starbucks|groupon|booking|satispay|hype|revolut|subito|wish|asos|primark|ovs|calzedonia|intimissimi|tigotà|acqua|sapone|pam|bennet|iper|eurospin|penny|aldi|tigros|famila|despar|unes|il gigante|cortilia|everli)""", RegexOption.IGNORE_CASE)

@Composable
fun AppsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val snap by Store.snapshot.collectAsStateWithLifecycle()
    var apps by remember { mutableStateOf<List<AppInfo>?>(null) }
    var sel by remember { mutableStateOf(Store.watched) }
    var q by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            val stores = snap.buoni.map { it.negozio } + snap.offerte.map { it.negozio }
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_ALL)
                .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                .distinctBy { it.first }
                .filter { it.first != ctx.packageName }
                .map { (p, l) -> AppInfo(p, l, SHOP_HINT.containsMatchIn(l) || SHOP_HINT.containsMatchIn(p) || stores.any { s -> Parser.sameStore(s, l) }) }
                .sortedWith(compareBy({ !it.suggested }, { it.label.lowercase() }))
        }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("App da ascoltare") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Indietro") } })
    }) { pad ->
        Column(Modifier.padding(pad)) {
            Text("Cacciabuoni legge solo le notifiche di queste app e salva solo quelle che parlano di buoni, coupon o sconti.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
            OutlinedTextField(q, { q = it }, placeholder = { Text("Cerca app") }, singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp))
            val list = apps
            if (list == null) Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                val shown = list.filter { q.isBlank() || it.label.contains(q, true) }
                if (q.isBlank() && shown.any { it.suggested }) item { Header("Suggerite") }
                items(shown, key = { it.pkg }) { a ->
                    ListItem(
                        headlineContent = { Text(a.label) },
                        supportingContent = { Text(a.pkg, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingContent = {
                            Switch(a.pkg in sel, { on -> sel = if (on) sel + a.pkg else sel - a.pkg; Store.watched = sel })
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(t: String) = Text(t, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp, 8.dp))

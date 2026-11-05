package it.iz2oii.cacciabuoni

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import kotlinx.coroutines.flow.MutableStateFlow
import it.iz2oii.cacciabuoni.ui.AppRoot
import it.iz2oii.cacciabuoni.ui.CacciabuoniTheme

/** Cosa ha aperto l'app: un testo o un'immagine condivisi, oppure una notifica di un negozio. */
data class Launch(val text: String? = null, val image: Uri? = null, val store: String? = null, val link: String? = null)

class MainActivity : ComponentActivity() {
    private val launch = MutableStateFlow<Launch?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Store.init(this)
        launch.value = parse(intent)
        setContent { CacciabuoniTheme { AppRoot(launch) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        launch.value = parse(intent)
    }

    private fun parse(i: Intent?): Launch? {
        i ?: return null
        return when (i.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val img = (i.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)?.takeIf { i.type?.startsWith("image/") == true }
                val txt = listOfNotNull(i.getStringExtra(Intent.EXTRA_SUBJECT), i.getStringExtra(Intent.EXTRA_TEXT)).joinToString("\n")
                Launch(text = txt.ifBlank { null }, image = img)
            }
            Intent.ACTION_VIEW -> i.data?.toString()?.let { Launch(link = it) }
            else -> i.getStringExtra(Notifier.EXTRA_STORE)?.let { Launch(store = it) }
        }
    }
}

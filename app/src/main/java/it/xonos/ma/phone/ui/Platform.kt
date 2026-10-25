package it.xonos.ma.phone.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap

// Le poche cose dello schermo che dipendono da Android. Il banco di prova (tools/screens) ha la
// sua versione di questo file per Compose Desktop.

/** Il tasto (o il gesto) indietro di Android. */
@Composable
internal fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = BackHandler(enabled, onBack)

/** I pixel di una copertina rimpicciolita, per prenderne il colore; null finché non ci sono. */
@Composable
internal fun rememberArtworkPixels(url: String?): IntArray? {
    val context = LocalPlatformContext.current
    var pixels by remember(url) { mutableStateOf<IntArray?>(null) }
    LaunchedEffect(url) {
        if (url == null) return@LaunchedEffect
        // Le bitmap "hardware" non si possono leggere: per questa serve una copia normale.
        val request = ImageRequest.Builder(context).data(url).size(SAMPLE_SIZE).allowHardware(false).build()
        val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return@LaunchedEffect
        val bitmap = result.image.toBitmap()
        val out = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(out, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        pixels = out
    }
    return pixels
}

private const val SAMPLE_SIZE = 32

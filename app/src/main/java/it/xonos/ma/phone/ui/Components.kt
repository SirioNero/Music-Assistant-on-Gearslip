package it.xonos.ma.phone.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import it.xonos.ma.R
import it.xonos.ma.data.MaItem
import it.xonos.ma.phone.model.Load
import it.xonos.ma.phone.model.PhoneModel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Le misure delle immagini chieste a Music Assistant: le versioni recenti accettano solo alcune misure. */
internal const val SMALL_ART = 256
internal const val LARGE_ART = 512

/** La forma della copertina: tonda per gli artisti, arrotondata per il resto. */
internal fun coverShape(mediaType: String?, corner: Dp = 8.dp): Shape =
    if (mediaType == "artist") CircleShape else RoundedCornerShape(corner)

/** La copertina di un elemento; finché non arriva (o se non c'è), l'icona del suo tipo su un riquadro. */
@Composable
internal fun Cover(
    url: String?,
    mediaType: String?,
    modifier: Modifier = Modifier,
    shape: Shape = coverShape(mediaType),
    iconSize: Dp = 28.dp,
) {
    Box(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        Icon(XIcons.of(mediaType), null, Modifier.size(iconSize), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f))
        if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

/** Una scheda con copertina, nome e sottotitolo: nelle righe orizzontali e nelle griglie. */
@Composable
internal fun ItemCard(item: MaItem, model: PhoneModel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val artist = item.mediaType == "artist"
    Column(
        modifier.testTag("card").clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(6.dp),
        horizontalAlignment = if (artist) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Cover(model.image(item.image, SMALL_ART), item.mediaType, Modifier.fillMaxWidth().aspectRatio(1f), coverShape(item.mediaType, 10.dp), 40.dp)
        Spacer(Modifier.height(8.dp))
        Text(
            item.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (artist) TextAlign.Center else TextAlign.Start,
        )
        val subtitle = cardSubtitle(item)
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Il sottotitolo di una scheda: quello di Music Assistant, o il tipo di elemento. */
@Composable
private fun cardSubtitle(item: MaItem): String? = item.subtitle ?: when (item.mediaType) {
    "artist" -> null
    else -> kindName(item.mediaType)
}

/** Una riga orizzontale di schede, con il suo titolo e, se serve, "Mostra tutto". */
@Composable
internal fun CardRow(
    title: String,
    items: List<MaItem>,
    model: PhoneModel,
    subtitle: String? = null,
    onSeeAll: (() -> Unit)? = null,
    onClick: (Int, MaItem) -> Unit,
) {
    SectionTitle(title, subtitle, onSeeAll)
    LazyRow(Modifier.testTag("row"), contentPadding = PaddingValues(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        itemsIndexed(items, key = { i, item -> "$i|${item.uri}" }) { i, item ->
            ItemCard(item, model, { onClick(i, item) }, Modifier.width(148.dp))
        }
    }
}

@Composable
internal fun SectionTitle(title: String, subtitle: String? = null, onSeeAll: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 20.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        if (onSeeAll != null) TextButton(onClick = onSeeAll) { Text(stringResource(R.string.see_all)) }
    }
}

/**
 * Una riga di elenco: copertina (o numero del brano), nome, sottotitolo, durata e menu.
 * [current] evidenzia il brano che sta suonando.
 */
@Composable
internal fun ItemRow(
    item: MaItem,
    model: PhoneModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    number: Int? = null,
    subtitle: String? = item.subtitle,
    current: Boolean = false,
    showAlbum: Boolean = true,
) {
    Row(
        modifier.testTag("item_row").fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val accent = MaterialTheme.colorScheme.primary
        if (number != null) {
            Box(Modifier.width(36.dp), contentAlignment = Alignment.CenterStart) {
                if (current) {
                    Icon(XIcons.Equalizer, null, Modifier.size(18.dp), tint = accent)
                } else {
                    Text("$number", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            Cover(model.image(item.image, SMALL_ART), item.mediaType, Modifier.size(52.dp), coverShape(item.mediaType, 6.dp), 22.dp)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                item.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (current) accent else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val line = rowSubtitle(item, subtitle)
            if (line != null) {
                Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (item.durationSec > 0 && item.mediaType == "track") {
            Text(
                clock(item.durationSec * 1000L),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        ItemMenu(item, model, showAlbum)
    }
}

/** Il sottotitolo di una riga: per gli episodi la durata e quanto resta, per il resto quello dato. */
@Composable
private fun rowSubtitle(item: MaItem, subtitle: String?): String? {
    if (item.mediaType != "podcast_episode" && item.mediaType != "audiobook") return subtitle ?: cardSubtitle(item)
    val total = item.durationSec * 1000L
    return when {
        item.fullyPlayed -> stringResource(R.string.episode_played)
        item.resumeMs > 0 && total > item.resumeMs -> ((total - item.resumeMs) / 60_000).coerceAtLeast(1).toInt().let { left ->
            pluralStringResource(R.plurals.episode_left, left, left)
        }
        total > 0 -> length(total)
        else -> subtitle
    }
}

/** I tre puntini di un elemento: suona dopo, in coda, vai all'album o all'artista. */
@Composable
internal fun ItemMenu(item: MaItem, model: PhoneModel, showAlbum: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(XIcons.More, stringResource(R.string.more), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (item.playable) {
                MenuEntry(XIcons.PlayNext, stringResource(R.string.play_next)) {
                    open = false
                    model.playNext(item)
                }
                MenuEntry(XIcons.AddToQueue, stringResource(R.string.add_to_queue)) {
                    open = false
                    model.addToQueue(item)
                }
            }
            val album = item.album
            if (showAlbum && album != null) {
                MenuEntry(XIcons.Album, stringResource(R.string.go_to_album)) {
                    open = false
                    model.openUri(album.uri)
                }
            }
            item.artists.firstOrNull()?.let { artist ->
                MenuEntry(XIcons.Artist, stringResource(R.string.go_to_artist)) {
                    open = false
                    model.openUri(artist.uri)
                }
            }
        }
    }
}

@Composable
internal fun MenuEntry(icon: ImageVector, text: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, onClick = onClick, leadingIcon = { Icon(icon, null, Modifier.size(20.dp)) })
}

/** I pulsanti di un album o di una playlist: preferito, menu, casuale e il grande play. */
@Composable
internal fun PlayBar(
    onPlay: () -> Unit,
    onShuffle: (() -> Unit)?,
    favorite: Boolean? = null,
    onFavorite: (Boolean) -> Unit = {},
    menu: @Composable () -> Unit = {},
) {
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (favorite != null) {
            IconButton(onClick = { onFavorite(!favorite) }) {
                Icon(
                    if (favorite) XIcons.Favorite else XIcons.FavoriteBorder,
                    stringResource(if (favorite) R.string.favorite_remove else R.string.favorite_add),
                    tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        menu()
        Spacer(Modifier.weight(1f))
        if (onShuffle != null) {
            IconButton(onClick = onShuffle) {
                Icon(XIcons.Shuffle, stringResource(R.string.shuffle), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(8.dp))
        FilledIconButton(
            onClick = onPlay,
            modifier = Modifier.size(56.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
            Icon(XIcons.Play, stringResource(R.string.play), Modifier.size(30.dp))
        }
    }
}

/** Mentre si carica: una rotella. Se non riesce: il motivo e "Riprova". */
@Composable
internal fun <T> Loaded(
    load: Load<T>,
    describe: (Throwable) -> String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    when (load) {
        Load.Loading -> Box(modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is Load.Failed -> Problem(describe(load.error), onRetry, modifier)
        is Load.Ready -> content(load.value)
    }
}

/** Un problema con il pulsante per riprovare. */
@Composable
internal fun Problem(message: String, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(XIcons.Offline, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (onRetry != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        }
    }
}

/** Un elenco vuoto. */
@Composable
internal fun Empty(text: String = stringResource(R.string.empty), modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.fillMaxWidth().padding(40.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/** Il nome di un tipo di elemento al singolare ("Album", "Artista"...). */
@Composable
internal fun kindName(mediaType: String?): String = stringResource(
    when (mediaType) {
        "artist" -> R.string.kind_artist
        "album" -> R.string.kind_album
        "playlist" -> R.string.kind_playlist
        "podcast" -> R.string.kind_podcast
        "podcast_episode" -> R.string.kind_episode
        "audiobook" -> R.string.kind_audiobook
        "radio" -> R.string.kind_radio
        "genre" -> R.string.kind_genre
        "folder" -> R.string.kind_folder
        else -> R.string.kind_track
    },
)

/** I minuti e i secondi di un brano: "3:07", "1:02:45". */
internal fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

/** Una durata lunga, a parole: "42 min", "1 h 05 min". */
@Composable
internal fun length(ms: Long): String {
    val minutes = ((ms + 30_000) / 60_000).toInt().coerceAtLeast(1)
    return if (minutes < 60) {
        stringResource(R.string.duration_minutes, minutes)
    } else {
        stringResource(R.string.duration_hours, minutes / 60, minutes % 60)
    }
}

/** Quanti brani e quanto durano: "4 brani · 4 min". */
@Composable
internal fun tracksSummary(tracks: List<MaItem>): String {
    val count = pluralStringResource(R.plurals.count_tracks, tracks.size, tracks.size)
    val total = tracks.sumOf { it.durationSec.toLong() } * 1000
    return if (total > 0) "$count · ${length(total)}" else count
}

/** Il colore che una copertina dà allo sfondo (si muove piano quando cambia). */
@Composable
internal fun backdropOf(url: String?): Color {
    val pixels = rememberArtworkPixels(url)
    val fallback = MaterialTheme.colorScheme.surfaceContainerHigh
    val target = remember(pixels) { pixels?.let(::backdropColor) } ?: fallback
    return animateColorAsState(target, tween(600), label = "backdrop").value
}

/**
 * Il colore di fondo preso da una copertina: tra i colori più frequenti vince quello più vivo,
 * poi lo si scurisce perché il testo bianco resti leggibile.
 */
internal fun backdropColor(pixels: IntArray): Color {
    // Si contano i colori a 4 bit per canale; i grigi pesano poco, quelli vivi di più.
    val weights = HashMap<Int, FloatArray>()
    for (pixel in pixels) {
        if ((pixel ushr 24) < 128) continue
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        val high = max(r, max(g, b))
        val low = min(r, min(g, b))
        val saturation = if (high == 0) 0f else (high - low) / high.toFloat()
        val light = (high + low) / 510f
        val weight = 0.1f + saturation * (1f - abs(light - 0.5f) * 1.4f).coerceAtLeast(0f)
        val key = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
        val sum = weights.getOrPut(key) { FloatArray(4) }
        sum[0] += weight
        sum[1] += r * weight
        sum[2] += g * weight
        sum[3] += b * weight
    }
    val best = weights.values.maxByOrNull { it[0] } ?: return Color(0xFF1A2333)
    val (hue, saturation) = hueAndSaturation(best[1] / best[0] / 255f, best[2] / best[0] / 255f, best[3] / best[0] / 255f)
    return Color.hsl(hue, saturation.coerceIn(0.12f, 0.55f), 0.24f)
}

/** Tinta (0-360) e saturazione (0-1) di un colore, come in HSL. */
private fun hueAndSaturation(r: Float, g: Float, b: Float): Pair<Float, Float> {
    val high = max(r, max(g, b))
    val low = min(r, min(g, b))
    val delta = high - low
    if (delta < 1e-4f) return 220f to 0f
    val light = (high + low) / 2
    val saturation = delta / (1 - abs(2 * light - 1))
    val hue = when (high) {
        r -> 60 * (((g - b) / delta) % 6)
        g -> 60 * ((b - r) / delta + 2)
        else -> 60 * ((r - g) / delta + 4)
    }
    return (if (hue < 0) hue + 360 else hue) to saturation.coerceIn(0f, 1f)
}

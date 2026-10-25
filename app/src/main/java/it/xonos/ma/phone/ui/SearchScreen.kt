package it.xonos.ma.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.xonos.ma.R
import it.xonos.ma.data.Library
import it.xonos.ma.data.MaItem
import it.xonos.ma.phone.model.Load
import it.xonos.ma.phone.model.PhoneModel
import it.xonos.ma.phone.model.SearchPage
import it.xonos.ma.phone.model.SearchResults

/** La ricerca in tutta la libreria e in tutti i servizi di Music Assistant, mentre si scrive. */
@Composable
internal fun SearchScreen(page: SearchPage, model: PhoneModel, host: Host) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text(
            stringResource(R.string.nav_search),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 12.dp),
        )
        SearchField(page.query) { model.search(page, it) }
        Box(Modifier.fillMaxWidth().height(3.dp).padding(horizontal = 16.dp)) {
            if (page.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        when (val results = page.results) {
            null -> Hint()
            else -> Loaded(results, host::describe, { model.search(page, page.query) }) { found ->
                if (found.byType.isEmpty()) Empty(stringResource(R.string.search_none, found.query)) else Results(found, model)
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    val focus = LocalFocusManager.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(52.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(XIcons.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(stringResource(R.string.search_hint), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                    modifier = Modifier.testTag("search_field").fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) { Icon(XIcons.Close, stringResource(R.string.clear)) }
            }
        }
    }
}

@Composable
private fun Hint() {
    Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(XIcons.Search, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.search_start),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Il risultato migliore in grande, poi i brani (i primi) e una riga di schede per ogni altro tipo. */
@Composable
private fun Results(found: SearchResults, model: PhoneModel) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        found.best?.let { best ->
            item(key = "best") {
                SectionTitle(stringResource(R.string.top_result))
                TopResult(best, model)
            }
        }
        val tracks = found.byType["tracks"].orEmpty()
        if (tracks.isNotEmpty()) {
            item(key = "tracks") {
                val title = stringResource(R.string.cat_tracks)
                SectionTitle(title, onSeeAll = if (tracks.size > SHOWN_TRACKS) ({ model.openResults(found, "tracks", title) }) else null)
            }
            itemsIndexed(tracks.take(SHOWN_TRACKS), key = { i, it -> "t$i|${it.uri}" }) { i, item ->
                ItemRow(item, model, { model.play(tracks, i, null) })
            }
        }
        for (type in Library.SEARCH_TYPES) {
            if (type == "tracks") continue
            val items = found.byType[type].orEmpty()
            if (items.isEmpty()) continue
            item(key = type) {
                val title = stringResource(typeName(type))
                CardRow(title, items, model, onSeeAll = { model.openResults(found, type, title) }) { _, item -> model.open(item) }
            }
        }
    }
}

/** Il risultato migliore: copertina grande, nome, tipo e un tasto per suonarlo subito. */
@Composable
private fun TopResult(item: MaItem, model: PhoneModel) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.testTag("top_result").fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(16.dp)).clickable { model.open(item) },
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(model.image(item.image, SMALL_ART), item.mediaType, Modifier.size(88.dp), coverShape(item.mediaType, 10.dp), 36.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        kindName(item.mediaType),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                    item.subtitle?.let {
                        Text(
                            "  $it",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (item.playable) {
                FilledIconButton(
                    onClick = { model.play(item) },
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) { Icon(XIcons.Play, stringResource(R.string.play)) }
            }
        }
    }
}

/** I tipi della ricerca si chiamano come le voci del menu. */
private fun typeName(type: String) = when (type) {
    "artists" -> R.string.cat_artists
    "albums" -> R.string.cat_albums
    "playlists" -> R.string.cat_playlists
    "podcasts" -> R.string.cat_podcasts
    "audiobooks" -> R.string.cat_audiobooks
    "radio" -> R.string.cat_radios
    "genres" -> R.string.cat_genres
    else -> R.string.cat_tracks
}

private const val SHOWN_TRACKS = 4

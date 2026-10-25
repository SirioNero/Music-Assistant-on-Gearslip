package it.xonos.ma.phone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import it.xonos.ma.R
import it.xonos.ma.phone.model.LibraryPage
import it.xonos.ma.phone.model.ORDER_ADDED
import it.xonos.ma.phone.model.ORDER_PLAYED
import it.xonos.ma.phone.model.PhoneModel
import it.xonos.ma.phone.model.Shelf
import it.xonos.ma.phone.model.ShelfList

/** La scheda Libreria: le voci in alto, e sotto quella scelta, a griglia o a elenco. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryScreen(page: LibraryPage, model: PhoneModel, host: Host) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        val shelves = page.shelves
        val list = if (shelves != null) model.shelf(page, page.shelf) else null
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.nav_library), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            if (list != null && list.shelf.orders.size > 1) OrderMenu(list, model)
            IconButton(onClick = model::openSettings) { Icon(XIcons.Settings, stringResource(R.string.settings)) }
        }
        if (shelves == null || list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(shelves, key = { it.name }) { shelf ->
                FilterChip(
                    selected = shelf == page.shelf,
                    onClick = { page.shelf = shelf },
                    label = { Text(shelfName(shelf)) },
                )
            }
        }
        // Ogni voce ricorda dove si era arrivati scorrendo.
        val positions = rememberSaveableStateHolder()
        positions.SaveableStateProvider("${list.shelf}") { ShelfContent(list, model, host) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShelfContent(list: ShelfList, model: PhoneModel, host: Host) {
    LaunchedEffect(list) { if (list.items.isEmpty()) model.loadMore(list) }
    PullToRefreshBox(isRefreshing = list.refreshing, onRefresh = { model.refresh(list) }) { ShelfItems(list, model, host) }
}

@Composable
private fun ShelfItems(list: ShelfList, model: PhoneModel, host: Host) {
    val footer: @Composable () -> Unit = {
        when {
            list.error != null -> Problem(host.describe(list.error!!), { model.loadMore(list) })
            list.loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.end && list.items.isEmpty() -> Empty()
        }
    }
    if (list.shelf.grid) {
        val state = rememberLazyGridState()
        LoadMoreWhenNear(state, list, model)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            state = state,
            contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = 16.dp),
            modifier = Modifier.fillMaxSize().testTag("library_list"),
        ) {
            itemsIndexed(list.items, key = { i, item -> "$i|${item.uri}" }) { _, item ->
                ItemCard(item, model, { model.open(item) })
            }
            item(span = { GridItemSpan(maxLineSpan) }) { footer() }
        }
    } else {
        val state = rememberLazyListState()
        LoadMoreWhenNear(state, list, model)
        LazyColumn(state = state, contentPadding = PaddingValues(bottom = 16.dp), modifier = Modifier.fillMaxSize().testTag("library_list")) {
            itemsIndexed(list.items, key = { i, item -> "$i|${item.uri}" }) { i, item ->
                ItemRow(item, model, { model.play(list.items, i, null) })
            }
            item { footer() }
        }
    }
}

/** Chiede la pagina successiva quando mancano pochi elementi alla fine. */
@Composable
private fun LoadMoreWhenNear(state: LazyGridState, list: ShelfList, model: PhoneModel) {
    LaunchedEffect(state, list) {
        snapshotFlow { (state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= list.items.size - NEAR_END }
            .collect { near -> if (near && list.items.isNotEmpty()) model.loadMore(list) }
    }
}

@Composable
private fun LoadMoreWhenNear(state: LazyListState, list: ShelfList, model: PhoneModel) {
    LaunchedEffect(state, list) {
        snapshotFlow { (state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= list.items.size - NEAR_END }
            .collect { near -> if (near && list.items.isNotEmpty()) model.loadMore(list) }
    }
}

private const val NEAR_END = 20

/** L'ordine dell'elenco: dalla A alla Z, aggiunti di recente, ascoltati di recente. */
@Composable
private fun OrderMenu(list: ShelfList, model: PhoneModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(XIcons.Sort, stringResource(R.string.sort)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (order in list.shelf.orders) {
                MenuEntry(if (order == list.order) XIcons.Check else XIcons.Sort, stringResource(orderName(order))) {
                    open = false
                    model.order(list, order)
                }
            }
        }
    }
}

private fun orderName(order: String) = when (order) {
    ORDER_ADDED -> R.string.sort_added
    ORDER_PLAYED -> R.string.sort_played
    else -> R.string.sort_name
}

@Composable
internal fun shelfName(shelf: Shelf): String = stringResource(
    when (shelf) {
        Shelf.PLAYLISTS -> R.string.cat_playlists
        Shelf.ALBUMS -> R.string.cat_albums
        Shelf.ARTISTS -> R.string.cat_artists
        Shelf.TRACKS -> R.string.cat_tracks
        Shelf.FAVORITES -> R.string.cat_favorites
        Shelf.PODCASTS -> R.string.cat_podcasts
        Shelf.AUDIOBOOKS -> R.string.cat_audiobooks
        Shelf.RADIOS -> R.string.cat_radios
        Shelf.GENRES -> R.string.cat_genres
        Shelf.BROWSE -> R.string.cat_browse
    },
)

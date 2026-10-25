package it.xonos.ma.phone.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.xonos.ma.R
import it.xonos.ma.data.MaItem
import it.xonos.ma.phone.model.Load
import it.xonos.ma.phone.model.PhoneModel
import it.xonos.ma.phone.model.PlaylistPicker

/**
 * "Aggiungi a una playlist": una playlist nuova (basta il nome) o una di quelle in cui si può
 * aggiungere. Toccandone una, ciò che si era scelto va in fondo e la scelta si chiude.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaylistSheet(picker: PlaylistPicker, model: PhoneModel, host: Host) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = model::closePlaylists, sheetState = state) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().imePadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            Text(stringResource(R.string.add_to_playlist), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp, bottom = 12.dp))
            if (naming) {
                val create = { model.createPlaylist(picker, name) }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.playlist_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { create() }),
                    modifier = Modifier.testTag("playlist_name").fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { naming = false }) { Text(stringResource(R.string.flow_cancel)) }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = create, enabled = name.isNotBlank() && !picker.creating, modifier = Modifier.testTag("playlist_create")) {
                        if (picker.creating) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text(stringResource(R.string.create))
                        }
                    }
                }
            } else {
                PickRow(null, stringResource(R.string.new_playlist), null, model, tag = "playlist_new") { naming = true }
            }
            when (val playlists = picker.playlists) {
                Load.Loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                is Load.Failed -> Problem(host.describe(playlists.error), { model.loadPlaylists(picker) })
                is Load.Ready -> {
                    if (playlists.value.isEmpty()) {
                        Text(
                            stringResource(R.string.playlists_none),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                    for (playlist in playlists.value) {
                        PickRow(playlist, playlist.name, playlist.subtitle, model, tag = "playlist") { model.addTo(picker, playlist) }
                    }
                }
            }
        }
    }
}

/** Una riga della scelta: la copertina della playlist (o il "+" per una nuova), il nome e chi l'ha fatta. */
@Composable
private fun PickRow(playlist: MaItem?, name: String, line: String?, model: PhoneModel, tag: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.testTag(tag).fillMaxWidth().padding(bottom = 8.dp),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (playlist != null) {
                Cover(model.image(playlist.image, SMALL_ART), "playlist", Modifier.size(44.dp), RoundedCornerShape(6.dp), 20.dp)
            } else {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    Icon(XIcons.Add, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                line?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

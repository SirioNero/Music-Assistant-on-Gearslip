package it.xonos.ma.phone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.xonos.ma.Language
import it.xonos.ma.R
import it.xonos.ma.phone.model.ConnectionCheck
import it.xonos.ma.phone.model.PhoneModel
import it.xonos.ma.phone.model.SettingsPage
import kotlinx.coroutines.delay

/** Il primo avvio: le impostazioni da sole, finché Xonos non sa a quale Music Assistant collegarsi. */
@Composable
internal fun Onboarding(model: PhoneModel, host: Host, onDone: () -> Unit) {
    val page = remember { model.settingsPage() }
    SettingsScreen(page, model, host, onboarding = true, onDone)
}

/**
 * Le impostazioni: lingua, i Music Assistant trovati nella rete, indirizzi, accesso (nome utente
 * e password, o un token), nome del lettore, la prova della connessione, la batteria, come si
 * usa in auto e come sostenere Xonos. Su un Music Assistant appena installato propone di creare il
 * primo account.
 */
@Composable
internal fun SettingsScreen(page: SettingsPage, model: PhoneModel, host: Host, onboarding: Boolean, onDone: () -> Unit) {
    val focus = LocalFocusManager.current
    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(bottom = 24.dp),
    ) {
        if (onboarding) {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 40.dp, bottom = 8.dp)) {
                Text(stringResource(R.string.welcome), style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.tagline), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDone) { Icon(XIcons.Back, stringResource(R.string.back)) }
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.settings), style = MaterialTheme.typography.titleLarge)
            }
        }
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            LanguagePicker(host)
            FoundServers(page, model)
            Field(page.home, { page.home = it }, R.string.label_home, R.string.help_home, stringResource(R.string.hint_home), KeyboardType.Uri, "field_home")
            Field(page.remote, { page.remote = it }, R.string.label_remote, R.string.help_remote, stringResource(R.string.hint_remote), KeyboardType.Uri, "field_remote")
            SignInChoice(page)
            if (page.useToken) {
                TokenField(page)
            } else {
                Field(
                    page.username,
                    { page.username = it },
                    R.string.label_username,
                    null,
                    "",
                    KeyboardType.Text,
                    "field_username",
                    help = page.signedIn?.let { stringResource(R.string.signed_in_as, it) } ?: stringResource(R.string.help_username),
                )
                PasswordField(page)
            }
            Field(page.name, { page.name = it }, R.string.label_name, R.string.help_name, stringResource(R.string.hint_name), KeyboardType.Text, "field_name")
            val signIn = !page.useToken && page.password.isNotEmpty()
            Button(
                onClick = {
                    focus.clearFocus()
                    model.save(page)
                },
                enabled = !page.checking,
                modifier = Modifier.testTag("save").fillMaxWidth().height(52.dp),
            ) {
                if (page.checking) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(12.dp))
                }
                Text(stringResource(if (page.checking) R.string.test_running else if (signIn) R.string.sign_in else R.string.save))
            }
            val incomplete = when {
                page.useToken -> !model.configured
                else -> page.username.isEmpty() || (page.password.isEmpty() && !model.configured)
            }
            if (!page.checking && page.results.isEmpty() && page.tried && incomplete) {
                Text(
                    stringResource(if (page.useToken) R.string.test_incomplete else R.string.test_incomplete_login),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (page.passwordShort) {
                Text(stringResource(R.string.password_short), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            page.results.forEach { result -> CheckLine(result, host) }
            if (page.setupNeeded) {
                InfoCard(
                    XIcons.Artist,
                    stringResource(R.string.setup_needed),
                    {
                        Button(
                            onClick = {
                                focus.clearFocus()
                                model.createAccount(page)
                            },
                            enabled = !page.checking,
                            modifier = Modifier.testTag("create_account"),
                        ) {
                            Text(stringResource(R.string.create_account))
                        }
                    },
                )
            }
            // Solo un amministratore di Music Assistant può aggiungere e configurare i servizi.
            if (!onboarding && rememberAdmin(model)) {
                Surface(
                    onClick = { model.services.open() },
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.testTag("open_services").fillMaxWidth(),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(XIcons.Services, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.services_title), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.services_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(XIcons.Chevron, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (onboarding && page.results.any { it is ConnectionCheck.Result.Ok }) {
                FilledTonalButton(onClick = onDone, modifier = Modifier.testTag("onboarding_done").fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.onboarding_done)) }
            }
            Spacer(Modifier.height(4.dp))
            val restricted = host.batteryRestricted
            InfoCard(
                XIcons.Battery,
                stringResource(if (restricted) R.string.battery_needed else R.string.battery_ok),
                if (restricted) ({ Button(onClick = host::askBatteryExemption) { Text(stringResource(R.string.battery_button)) } }) else null,
            )
            InfoCard(XIcons.Car, stringResource(R.string.help_car))
            if (!onboarding) Donation()
            Text(
                stringResource(R.string.version, host.version),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: Int,
    helpId: Int?,
    hint: String,
    keyboard: KeyboardType,
    tag: String,
    help: String? = helpId?.let { stringResource(it) },
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        placeholder = { if (hint.isNotEmpty()) Text(hint) },
        supportingText = help?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.testTag(tag).fillMaxWidth(),
    )
}

@Composable
private fun TokenField(page: SettingsPage) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = page.token,
        onValueChange = { page.token = it },
        label = { Text(stringResource(R.string.label_token)) },
        placeholder = { Text(stringResource(R.string.hint_token)) },
        supportingText = { Text(stringResource(R.string.help_token)) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) XIcons.Hidden else XIcons.Visible, stringResource(if (visible) R.string.hide_token else R.string.show_token))
            }
        },
        modifier = Modifier.testTag("field_token").fillMaxWidth(),
    )
}

/**
 * I Music Assistant che si annunciano nella rete (mDNS): toccandone uno, i suoi indirizzi vanno
 * nel modulo. Si cerca solo mentre questa schermata è aperta.
 */
@Composable
private fun FoundServers(page: SettingsPage, model: PhoneModel) {
    DisposableEffect(model) {
        model.finder.start()
        onDispose { model.finder.stop() }
    }
    val servers by model.finder.servers.collectAsState()
    val searching by model.finder.searching.collectAsState()
    // Dopo qualche secondo senza risultati si dice che non c'è niente, invece di girare per sempre.
    var waited by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(SEARCH_WAIT_MS)
        waited = true
    }
    if (servers.isEmpty() && !searching) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.discover_title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        for (server in servers) {
            val chosen = page.home.trimEnd('/') == server.url
            Surface(
                onClick = { model.choose(page, server) },
                color = if (chosen) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.testTag("server").fillMaxWidth(),
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(XIcons.Server, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(server.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${server.url.removePrefix("http://")} · ${server.version}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (chosen) Icon(XIcons.Check, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        if (servers.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!waited) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    stringResource(if (waited) R.string.discover_none else R.string.discover_searching),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Come si entra: con nome utente e password (Xonos si fa dare un token) o con un token copiato. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SignInChoice(page: SettingsPage) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = !page.useToken,
            onClick = { page.useToken = false },
            label = { Text(stringResource(R.string.login_password)) },
            modifier = Modifier.testTag("login_password"),
        )
        FilterChip(
            selected = page.useToken,
            onClick = { page.useToken = true },
            label = { Text(stringResource(R.string.login_token)) },
            modifier = Modifier.testTag("login_token"),
        )
    }
}

@Composable
private fun PasswordField(page: SettingsPage) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = page.password,
        onValueChange = { page.password = it },
        label = { Text(stringResource(R.string.label_password)) },
        supportingText = { Text(stringResource(R.string.help_password)) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) XIcons.Hidden else XIcons.Visible, stringResource(if (visible) R.string.hide_password else R.string.show_password))
            }
        },
        modifier = Modifier.testTag("field_password").fillMaxWidth(),
    )
}

private const val SEARCH_WAIT_MS = 6_000L

/** La lingua dell'app (e del menu in auto), ciascuna scritta nella sua lingua. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguagePicker(host: Host) {
    var open by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { open = true },
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(XIcons.Language, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.label_language), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(host.language.label, style = MaterialTheme.typography.bodyLarge)
                }
                Icon(XIcons.Down, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (language in Language.entries) {
                DropdownMenuItem(
                    text = { Text(language.label) },
                    onClick = {
                        open = false
                        if (language != host.language) host.changeLanguage(language)
                    },
                    trailingIcon = { if (language == host.language) Icon(XIcons.Check, null, tint = MaterialTheme.colorScheme.primary) },
                )
            }
        }
    }
}

/** Una riga della prova: "✓ Casa: Music Assistant 2.10.8 · 12 playlist · audio pronto". */
@Composable
private fun CheckLine(result: ConnectionCheck.Result, host: Host) {
    val place = stringResource(if (result.place == ConnectionCheck.Place.HOME) R.string.test_home else R.string.test_remote)
    val text = when (result) {
        is ConnectionCheck.Result.Ok -> listOfNotNull(
            stringResource(R.string.test_ok, place, result.version),
            result.playlists?.let { pluralStringResource(R.plurals.test_playlists, it, it) },
            audioText(result.audio),
        ).joinToString(" · ")
        is ConnectionCheck.Result.Failed -> stringResource(R.string.test_failed, place, host.describe(result.error))
    }
    val ok = result is ConnectionCheck.Result.Ok && result.audio == ConnectionCheck.Audio.Ready
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (ok) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun audioText(audio: ConnectionCheck.Audio): String = when (audio) {
    ConnectionCheck.Audio.Ready -> stringResource(R.string.audio_ready)
    ConnectionCheck.Audio.Unexpected -> stringResource(R.string.audio_unexpected)
    is ConnectionCheck.Audio.Closed -> audio.reason?.let { stringResource(R.string.audio_error, it) } ?: stringResource(R.string.audio_closed)
    ConnectionCheck.Audio.Denied -> stringResource(R.string.audio_denied)
    ConnectionCheck.Audio.TooOld -> stringResource(R.string.audio_too_old)
    is ConnectionCheck.Audio.Error -> stringResource(R.string.audio_error, audio.message)
    ConnectionCheck.Audio.Unreachable -> stringResource(R.string.audio_unreachable)
    ConnectionCheck.Audio.NoAnswer -> stringResource(R.string.audio_no_answer)
}

@Composable
private fun InfoCard(icon: ImageVector, text: String, action: (@Composable () -> Unit)? = null) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
            if (action != null) Box(Modifier.padding(start = 40.dp, top = 12.dp)) { action() }
        }
    }
}

/** L'indirizzo bitcoin per le donazioni: lo stesso del README e della ricetta di F-Droid. */
private const val DONATION_ADDRESS = "bc1qu8q2sc5kuccjj3zpzzfpj85xn7szp73nae9xlx"

/** Chi vuole sostenere Xonos: l'indirizzo bitcoin, da copiare o da aprire nel portafoglio. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Donation() {
    val uri = LocalUriHandler.current
    val copy = rememberCopy()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }
    val copyAddress = {
        copy(DONATION_ADDRESS)
        copied = true
    }
    InfoCard(XIcons.Favorite, stringResource(R.string.donate_text), {
        Column {
            // In due righe uguali, che stanno in ogni schermo: da qui non si copia (andrebbe a capo
            // anche la copia), c'è il pulsante.
            Text(
                DONATION_ADDRESS.chunked((DONATION_ADDRESS.length + 1) / 2).joinToString("\n"),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = copyAddress, modifier = Modifier.testTag("donate_copy")) {
                    Text(stringResource(if (copied) R.string.donate_copied else R.string.donate_copy))
                }
                // Senza un portafoglio sul telefono non si apre niente: allora l'indirizzo si copia.
                TextButton(
                    onClick = { if (runCatching { uri.openUri("bitcoin:$DONATION_ADDRESS") }.isFailure) copyAddress() },
                    modifier = Modifier.testTag("donate_open"),
                ) {
                    Text(stringResource(R.string.donate_open))
                }
            }
        }
    })
}

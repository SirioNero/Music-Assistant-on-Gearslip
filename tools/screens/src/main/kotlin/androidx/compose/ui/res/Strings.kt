package androidx.compose.ui.res

import androidx.compose.runtime.Composable
import screens.Texts

// Le funzioni di Android per le scritte, che Compose Desktop non ha: leggono gli strings.xml dell'app
// nella lingua scelta per la scena (vedi screens.Texts).

@Composable
fun stringResource(id: Int): String = Texts.string(id)

@Composable
fun stringResource(id: Int, vararg formatArgs: Any): String = Texts.string(id).format(*formatArgs)

@Composable
fun pluralStringResource(id: Int, count: Int): String = Texts.plural(id, count)

@Composable
fun pluralStringResource(id: Int, count: Int, vararg formatArgs: Any): String = Texts.plural(id, count).format(*formatArgs)

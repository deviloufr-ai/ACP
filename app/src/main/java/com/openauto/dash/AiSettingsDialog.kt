package com.openauto.dash

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Gemini key, engine, language and voice for the AI mechanic, with a one-tap test. */
@Composable
internal fun AiSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf(AiSettings.load(context)) }
    var testing by remember { mutableStateOf(false) }
    // (worked, message) from the last test.
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var code by remember { mutableStateOf("") }

    fun close() {
        AiSettings.save(context, config)
        // A new key, engine or language: redo the advice for any codes on the tile.
        AiMechanic.refresh()
        onDismiss()
    }

    /** Saves, then checks the key with a tiny request; [activated] says the key just came from the code. */
    fun test(activated: Boolean = false) {
        AiSettings.save(context, config)
        testing = true
        result = null
        scope.launch {
            val reply = GeminiClient.generate(config.apiKey.trim(), "Reply with the single word OK.")
            testing = false
            val language = config.language
            reply.onSuccess {
                val works = context.getString(if (activated) R.string.ai_test_activated_ok else R.string.ai_test_ok, it.model)
                // The missing voice is named in the screen's language ("allemand" on a French screen).
                val voice = if (CarVoice.canSpeak(language.locale) == false) {
                    " " + context.getString(R.string.ai_no_voice, language.locale.getDisplayLanguage(Locale.getDefault()))
                } else ""
                result = true to works + voice
                // Said in the mechanic's language, whatever the screen's.
                CarVoice.speak(language.resources(context).getString(R.string.ai_say_ready), language.locale)
            }.onFailure {
                // An activated key is saved either way; only reaching Gemini failed.
                result = activated to context.getString(
                    if (activated) R.string.ai_test_activated_unreachable else R.string.ai_test_failed,
                    AiMechanic.describe(context, it)
                )
            }
        }
    }

    fun activate() {
        testing = true
        result = null
        scope.launch {
            val key = withContext(Dispatchers.Default) { AiKeyVault.unlock(code) }
            testing = false
            if (key == null) {
                result = false to context.getString(R.string.ai_wrong_code)
            } else {
                config = config.copy(apiKey = key, keyFromCode = true)
                code = ""
                test(activated = true)
            }
        }
    }

    AlertDialog(
        onDismissRequest = ::close,
        containerColor = DashColors.Card,
        title = { Text(stringResource(R.string.ai_title), color = DashColors.TextPrimary) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    stringResource(R.string.ai_explanation),
                    color = DashColors.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )

                if (AiKeyVault.available) {
                    Label(stringResource(R.string.ai_activation_code))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = code,
                            onValueChange = { code = it; result = null },
                            placeholder = { Text(stringResource(R.string.ai_activation_hint), color = DashColors.Muted) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            colors = fieldColors()
                        )
                        Spacer(Modifier.size(12.dp))
                        Button(
                            enabled = !testing && code.isNotBlank(),
                            onClick = ::activate,
                            colors = buttonColors()
                        ) { Text(stringResource(R.string.ai_activate)) }
                    }
                }

                Label(stringResource(if (AiKeyVault.available) R.string.ai_own_key else R.string.ai_key))
                OutlinedTextField(
                    value = config.apiKey,
                    onValueChange = { config = config.copy(apiKey = it.trim(), keyFromCode = false); result = null },
                    // The built-in key stays hidden; typing replaces it with your own.
                    visualTransformation = if (config.keyFromCode) PasswordVisualTransformation() else VisualTransformation.None,
                    placeholder = { Text(stringResource(R.string.ai_key_hint), color = DashColors.Muted) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = fieldColors()
                )

                Label(stringResource(R.string.ai_engine))
                ChoiceRow(CarEngine.entries, config.engine, { it.label }) { config = config.copy(engine = it) }

                Label(stringResource(R.string.ai_language))
                // "Same as app" (null) first, then every language by its own name.
                val sameAsApp = stringResource(R.string.ai_language_auto)
                ChoiceRow(
                    listOf<AiLanguage?>(null) + AiLanguage.entries,
                    config.languageChoice,
                    { it?.label ?: sameAsApp },
                    perRow = 3
                ) { config = config.copy(languageChoice = it) }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.ai_speak), color = DashColors.TextPrimary)
                        Text(
                            stringResource(R.string.ai_speak_detail),
                            color = DashColors.TextSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = config.speak,
                        onCheckedChange = { config = config.copy(speak = it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = DashColors.OnAccent,
                            checkedTrackColor = DashColors.Accent
                        )
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        enabled = !testing && config.apiKey.isNotBlank(),
                        onClick = { test() },
                        colors = buttonColors()
                    ) { Text(stringResource(R.string.ai_test)) }
                    Spacer(Modifier.size(12.dp))
                    if (testing) CircularProgressIndicator(color = DashColors.Accent, modifier = Modifier.size(22.dp))
                    result?.let { (ok, message) ->
                        Text(
                            message,
                            color = if (ok) DashColors.Good else DashColors.Warning,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = ::close) { Text(stringResource(R.string.ai_done), color = DashColors.Accent) } }
    )
}

// Explicit disabled colours: the Material defaults vanish on the light theme cards.
@Composable
private fun buttonColors() = ButtonDefaults.buttonColors(
    containerColor = DashColors.Accent,
    contentColor = DashColors.OnAccent,
    disabledContainerColor = DashColors.CardHi,
    disabledContentColor = DashColors.Muted
)

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = DashColors.TextPrimary,
    unfocusedTextColor = DashColors.TextPrimary,
    focusedBorderColor = DashColors.Accent,
    unfocusedBorderColor = DashColors.Line,
    cursorColor = DashColors.Accent
)

@Composable
private fun Label(text: String) {
    Text(text, color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
}

/**
 * Segmented single choice; the picked segment wears the accent gradient.
 * Long lists wrap onto rows of [perRow] segments.
 */
@Composable
private fun <T> ChoiceRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    perRow: Int = options.size,
    onPick: (T) -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, DashColors.Line, shape)
            .background(DashColors.CardHi.copy(alpha = DashColors.CardHi.alpha * 0.5f), shape)
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.chunked(perRow.coerceAtLeast(1)).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { option ->
                    val chosen = option == selected
                    val segment = RoundedCornerShape(10.dp)
                    Text(
                        label(option),
                        color = if (chosen) DashColors.OnAccent else DashColors.TextPrimary,
                        fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clip(segment)
                            .then(if (chosen) Modifier.background(DashColors.AccentBrush, segment) else Modifier)
                            .clickable { onPick(option) }
                            .padding(vertical = 10.dp)
                    )
                }
                // Keep a short last row's segments the same width as the others.
                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

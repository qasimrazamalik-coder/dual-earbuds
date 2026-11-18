package com.dualearbuds.companion.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.dualearbuds.companion.assistant.Answer
import com.dualearbuds.companion.assistant.AskFacts
import com.dualearbuds.companion.assistant.Assistant
import com.dualearbuds.companion.assistant.HelpLibrary
import com.dualearbuds.companion.ui.components.ActionRow
import com.dualearbuds.companion.ui.components.Hint
import com.dualearbuds.companion.ui.components.Panel
import com.dualearbuds.companion.ui.components.PanelBody
import com.dualearbuds.companion.ui.components.PanelDivider
import com.dualearbuds.companion.ui.components.PanelRow
import com.dualearbuds.companion.ui.components.PrimaryButton
import com.dualearbuds.companion.ui.components.QuietButton
import com.dualearbuds.companion.ui.components.ScreenScaffold
import com.dualearbuds.companion.ui.components.Section
import com.dualearbuds.companion.ui.components.StepLine
import com.dualearbuds.companion.ui.theme.AppTheme
import com.dualearbuds.companion.ui.theme.Mono

/**
 * Ask: answers from the bundled help (docs/help) plus live facts about this session. Fully offline — no network
 * call of any kind; questions live only in this screen's state.
 */
@Composable
fun AskScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val assistant: Assistant = remember { HelpLibrary.get(context) }
    var question by rememberSaveable { mutableStateOf("") }
    // What is shown: the asked question, or a chunk opened from Related / Closest topics.
    var asked by rememberSaveable { mutableStateOf<String?>(null) }
    var shownChunk by rememberSaveable { mutableStateOf<String?>(null) }
    // Bumped on every Ask so the same question again re-reads the live facts.
    var tick by remember { mutableStateOf(0) }

    val answer: Answer? = remember(asked, shownChunk, tick) {
        val chunk = shownChunk?.let { assistant.chunk(it) }
        val q = asked
        when {
            chunk != null -> assistant.show(chunk, rightNow = assistant.matchFacts(chunk.title + " " + chunk.keywords.joinToString(" "), AskFacts.read(context)).map { it.text })
            q != null -> assistant.ask(q, AskFacts.read(context))
            else -> null
        }
    }

    fun submit(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        question = t
        shownChunk = null
        asked = t
        tick++
        focus.clearFocus()
    }

    val c = AppTheme.colors
    ScreenScaffold(title = "Ask", onBack = onBack, readout = "offline · ${assistant.chunks.size} answers") {
        Section("Question") {
            Panel {
                PanelBody {
                    OutlinedTextField(
                        value = question,
                        onValueChange = { question = it },
                        placeholder = { Text("Why is the sound late?", style = MaterialTheme.typography.bodyMedium, color = c.muted) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.text),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submit(question) }, onDone = { submit(question) }),
                        shape = MaterialTheme.shapes.small,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = c.accent,
                            unfocusedBorderColor = c.line,
                            focusedContainerColor = c.surface,
                            unfocusedContainerColor = c.surface,
                            cursorColor = c.accent
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    ActionRow {
                        PrimaryButton("Ask", onClick = { submit(question) }, enabled = question.isNotBlank())
                        if (asked != null || shownChunk != null) {
                            QuietButton("Clear", onClick = {
                                question = ""
                                asked = null
                                shownChunk = null
                            }, color = c.muted)
                        }
                    }
                    Hint("Answers come from the built-in help and this phone's live state. Nothing leaves the phone.")
                }
            }
            val suggestions = remember(assistant) { assistant.suggestions(5) }
            if (suggestions.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    suggestions.forEach { s -> SuggestionChip(s) { submit(s) } }
                }
            }
        }

        if (assistant.chunks.isEmpty()) {
            Section("Answer") {
                Panel { PanelBody { Hint("The help files are missing from this build.") } }
            }
        }

        answer?.let { AnswerSections(it, onOpen = { id -> shownChunk = id }) }
    }
}

@Composable
private fun AnswerSections(answer: Answer, onOpen: (String) -> Unit) {
    val c = AppTheme.colors
    val source = answer.source
    Section("Answer", count = source?.topic) {
        Panel(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
            PanelBody {
                if (source != null) {
                    Text(source.title, style = MaterialTheme.typography.labelMedium, color = c.text, modifier = Modifier.semantics { heading() })
                }
                Text(answer.text, style = MaterialTheme.typography.bodyMedium, color = c.text)
                answer.steps.forEachIndexed { i, s -> StepLine(i + 1, s) }
                answer.notes.forEach { Hint(it) }
                if (source != null) {
                    Text("Source: ${source.file} › ${source.title}", style = Mono.value, color = c.muted)
                }
            }
        }
    }
    if (answer.rightNow.isNotEmpty()) {
        Section("Right now", count = "${answer.rightNow.size}") {
            Panel {
                answer.rightNow.forEachIndexed { i, f ->
                    if (i > 0) PanelDivider()
                    Text(
                        f,
                        style = Mono.value,
                        color = c.text,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }
    if (answer.related.isNotEmpty()) {
        Section(if (answer.found) "Related" else "Closest topics", count = "${answer.related.size}") {
            Panel {
                answer.related.forEachIndexed { i, r ->
                    if (i > 0) PanelDivider()
                    PanelRow(r.title, subtitle = r.topic, chevron = true, onClick = { onOpen(r.id) })
                }
            }
        }
    }
}

/** A suggested question: 32 dp visual chip inside a 48 dp touch target. */
@Composable
private fun SuggestionChip(text: String, onClick: () -> Unit) {
    val c = AppTheme.colors
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClickLabel = "Ask this", onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = c.text,
            maxLines = 1,
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .background(c.surface)
                .border(1.dp, c.line, MaterialTheme.shapes.small)
                .heightIn(min = 32.dp)
                .padding(horizontal = 10.dp, vertical = 8.dp)
        )
    }
}

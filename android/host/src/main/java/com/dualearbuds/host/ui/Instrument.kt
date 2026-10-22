package com.dualearbuds.host.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dualearbuds.host.model.Readout
import com.dualearbuds.host.model.SyncLevel
import com.dualearbuds.host.setup.CheckLevel

/*
 * The "instrument" design system (docs/design-system.md) for the host screen, in Compose.
 * Every colour comes from the caller's MaterialTheme; this file only decides which role is used where:
 *   Bg = background, Surface = surface, Surface2 = surfaceContainerHigh, Line = outlineVariant,
 *   Text = onSurface, Muted = onSurfaceVariant, Accent = primary,
 *   Good = primary, Warn = tertiary, Bad = error.
 * Accent appears only for live state, the primary action, the selected segment and meters.
 */

@Immutable
internal data class Tokens(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val line: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val onAccent: Color,
    val good: Color,
    val warn: Color,
    val bad: Color
)

internal val tokens: Tokens
    @Composable
    @ReadOnlyComposable
    get() {
        val c = MaterialTheme.colorScheme
        return Tokens(
            bg = c.background,
            surface = c.surface,
            surface2 = c.surfaceContainerHigh,
            line = c.outlineVariant,
            text = c.onSurface,
            muted = c.onSurfaceVariant,
            accent = c.primary,
            onAccent = c.onPrimary,
            good = c.primary,
            warn = c.tertiary,
            bad = c.error
        )
    }

/** Type scale of the design system; letter-spacing 0 except mono readouts. */
internal object Type {
    val headline = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp)
    val page = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp)
    val body = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.sp)
    val rowTitle = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp)
    val label = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp)
    val hint = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.sp)
    val mono = TextStyle(
        fontSize = 12.sp, lineHeight = 16.sp, fontFamily = FontFamily.Monospace,
        letterSpacing = 0.2.sp, fontFeatureSettings = "tnum"
    )
    val monoLarge = mono.copy(fontSize = 14.sp, lineHeight = 20.sp)
}

private val PanelShape = RoundedCornerShape(8.dp)
private val ControlShape = RoundedCornerShape(6.dp)

/** Section label (sentence case), optional muted count, and a hairline to the right edge. */
@Composable
internal fun SectionLabel(label: String, count: String? = null, modifier: Modifier = Modifier) {
    val t = tokens
    Row(
        modifier
            .fillMaxWidth()
            .padding(top = 24.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = Type.label, color = t.text)
        if (count != null) {
            Spacer(Modifier.width(8.dp))
            Text(count, style = Type.mono, color = t.muted)
        }
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(t.line)
        )
    }
}

/** Rows of one kind in one 8 dp panel with a 1 dp border; children are separated by [PanelDivider]. */
@Composable
internal fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val t = tokens
    Column(
        modifier
            .fillMaxWidth()
            .border(1.dp, t.line, PanelShape)
            .background(t.surface, PanelShape)
    ) { content() }
}

@Composable
internal fun PanelDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(tokens.line)
    )
}

/** A 52 dp row (64 dp with a hint): title, optional muted hint, trailing content. */
@Composable
internal fun PanelRow(
    title: String,
    hint: String? = null,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    val t = tokens
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = if (hint == null) 52.dp else 64.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.rowTitle, color = t.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (hint != null) Text(hint, style = Type.hint, color = t.muted)
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/** A row whose whole area toggles a switch. Neutral track off, accent only when on. */
@Composable
internal fun ToggleRow(title: String, hint: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val t = tokens
    PanelRow(
        title,
        hint,
        Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = t.onAccent,
                    checkedTrackColor = t.accent,
                    checkedBorderColor = t.accent,
                    uncheckedThumbColor = t.muted,
                    uncheckedTrackColor = t.surface2,
                    uncheckedBorderColor = t.line
                )
            )
        }
    )
}

/** One-line muted explanation under a setting, inside or outside a panel. */
@Composable
internal fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = Type.hint, color = tokens.muted)
}

/** Mono, tabular value (ms, kHz, %, IP, PIN). */
@Composable
internal fun Mono(text: String, modifier: Modifier = Modifier, color: Color = tokens.muted, large: Boolean = false) {
    Text(text, modifier = modifier, style = if (large) Type.monoLarge else Type.mono, color = color, maxLines = 1)
}

/** 6 dp status dot + word: never colour alone. */
@Composable
internal fun StatusDot(level: CheckLevel, word: String) {
    val t = tokens
    val c = when (level) {
        CheckLevel.OK -> t.good
        CheckLevel.WARN -> t.warn
        CheckLevel.INFO -> t.muted
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(6.dp)
                .background(c, CircleShape)
        )
        Spacer(Modifier.width(6.dp))
        Text(word, style = Type.hint, color = t.muted)
    }
}

/** The one solid accent button of the view. */
@Composable
internal fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val t = tokens
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(36.dp),
        shape = ControlShape,
        contentPadding = PaddingValues(horizontal = 16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = t.accent, contentColor = t.onAccent)
    ) { Text(text, style = Type.rowTitle) }
}

/** Quiet secondary: outline, text colour, no fill. */
@Composable
internal fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false) {
    val t = tokens
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(32.dp),
        shape = ControlShape,
        contentPadding = PaddingValues(horizontal = 12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, t.line),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = if (danger) t.bad else t.text)
    ) { Text(text, style = Type.label) }
}

/** Text-only action inside a row ("Remove", "Change"). */
@Composable
internal fun RowAction(text: String, onClick: () -> Unit, danger: Boolean = false) {
    val t = tokens
    TextButton(
        onClick = onClick,
        modifier = Modifier.height(32.dp),
        shape = ControlShape,
        contentPadding = PaddingValues(horizontal = 8.dp)
    ) { Text(text, style = Type.label, color = if (danger) t.bad else t.text) }
}

/** Segmented control, 28 dp: selected segment = Surface2 + accent text, not a solid block. */
@Composable
internal fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val t = tokens
    Row(
        modifier
            .height(28.dp)
            .border(1.dp, t.line, ControlShape)
            .padding(1.dp)
    ) {
        options.forEachIndexed { i, (value, label) ->
            val on = value == selected
            if (i > 0) {
                Box(
                    Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(t.line)
                )
            }
            Box(
                Modifier
                    .fillMaxHeight()
                    .background(if (on) t.surface2 else Color.Transparent, ControlShape)
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onSelect(value) })
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(label, style = Type.label, color = if (on) t.accent else t.muted)
            }
        }
    }
}

/** Thin 2 dp slider with the value in mono at a fixed width on the right, so rows align. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ValueSlider(
    value: Float,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    modifier: Modifier = Modifier,
    description: String
) {
    val t = tokens
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Slider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onDone,
            valueRange = range,
            modifier = Modifier
                .weight(1f)
                .height(28.dp)
                .semantics { contentDescription = description },
            colors = SliderDefaults.colors(thumbColor = t.accent, activeTrackColor = t.accent, inactiveTrackColor = t.line),
            thumb = {
                Box(
                    Modifier
                        .size(12.dp)
                        .background(t.accent, CircleShape)
                )
            },
            track = { state ->
                val span = state.valueRange.endInclusive - state.valueRange.start
                val f = if (span <= 0f) 0f else ((state.value - state.valueRange.start) / span).coerceIn(0f, 1f)
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                ) {
                    val y = size.height / 2
                    drawLine(t.line, Offset(0f, y), Offset(size.width, y), strokeWidth = size.height)
                    drawLine(t.accent, Offset(0f, y), Offset(size.width * f, y), strokeWidth = size.height)
                }
            }
        )
        Spacer(Modifier.width(8.dp))
        Mono(valueText, Modifier.width(44.dp), color = t.text)
    }
}

/**
 * The sync scope: a ±50 ms scale with a tick at the deadline and a marker at this ear's offset.
 * Good (±5 ms) uses the accent, fair (±20 ms) Warn, beyond that Bad; unknown shows no marker.
 */
@Composable
internal fun SyncScope(offsetMs: Int?, modifier: Modifier = Modifier) {
    val t = tokens
    val level = Readout.level(offsetMs)
    val marker = when (level) {
        SyncLevel.GOOD -> t.good
        SyncLevel.FAIR -> t.warn
        SyncLevel.OFF -> t.bad
        SyncLevel.UNKNOWN -> Color.Transparent
    }
    val label = if (offsetMs == null) "sync unknown" else "${Readout.offset(offsetMs)} from the deadline"
    Canvas(
        modifier
            .width(88.dp)
            .height(14.dp)
            .semantics { contentDescription = label }
    ) {
        val mid = size.width / 2
        val y = size.height / 2
        val stroke = 1.dp.toPx()
        drawLine(t.line, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke)
        for (q in listOf(0f, 0.25f, 0.75f, 1f)) {
            val x = q * size.width
            drawLine(t.line, Offset(x, y - 2.dp.toPx()), Offset(x, y + 2.dp.toPx()), strokeWidth = stroke)
        }
        drawLine(t.muted, Offset(mid, 0f), Offset(mid, size.height), strokeWidth = stroke)
        if (offsetMs != null) {
            val x = mid + Readout.scopePosition(offsetMs) * (mid - 1.5.dp.toPx())
            drawLine(marker, Offset(x, 1.dp.toPx()), Offset(x, size.height - 1.dp.toPx()), strokeWidth = 3.dp.toPx())
        }
    }
}

/** A plain message line in a panel: what happened and what to do. Bad colour for errors. */
@Composable
internal fun Notice(text: String, error: Boolean = false, action: String? = null, onAction: (() -> Unit)? = null) {
    val t = tokens
    Panel {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .background(if (error) t.bad else t.warn, CircleShape)
            )
            Spacer(Modifier.width(10.dp))
            Text(text, style = Type.body, color = t.text, modifier = Modifier.weight(1f))
            if (action != null && onAction != null) {
                Spacer(Modifier.width(8.dp))
                RowAction(action, onAction)
            }
        }
    }
}

/** Two-column key/value line in mono for details ("Port 8790"). */
@Composable
internal fun KeyValue(key: String, value: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val t = tokens
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(key, style = Type.hint, color = t.muted)
        Mono(value, color = t.text)
    }
}

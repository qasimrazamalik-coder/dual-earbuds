package com.dualearbuds.companion.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dualearbuds.companion.ui.theme.AppTheme
import com.dualearbuds.companion.ui.theme.Mono
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.max
import kotlin.math.pow

// =====================================================================================================
// Structure: sections, panels, rows (docs/design-system.md "Structure")
// =====================================================================================================

/**
 * A section: 12 sp semibold sentence-case [label], a muted [count] ("2 of 3 on"), then a 1 dp hairline to the
 * right edge. 24 dp above, 8 dp between the label and the content.
 */
@Composable
fun Section(
    label: String,
    modifier: Modifier = Modifier,
    count: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier.fillMaxWidth().padding(top = 24.dp)) {
        SectionHeader(label, count, trailing)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
fun SectionHeader(label: String, count: String? = null, trailing: (@Composable () -> Unit)? = null) {
    val c = AppTheme.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = c.text, maxLines = 1)
        if (!count.isNullOrBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(count, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1)
        }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f).height(1.dp).background(c.line))
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/** Items of one kind as rows inside one panel: 8 dp radius, 1 dp Line border, no shadow. */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = AppTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(c.surface)
            .border(1.dp, c.line, MaterialTheme.shapes.medium),
        content = content
    )
}

/** 1 dp divider between rows of a panel. */
@Composable
fun PanelDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(AppTheme.colors.line))
}

/** Free content inside a panel (controls, hints), padded 12 dp. */
@Composable
fun PanelBody(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

/**
 * One row of a panel: 52 dp (64 dp with a subtitle). Optional 16 dp [leading] icon, a mono [value] at the
 * right, a [trailing] slot, and a chevron when it navigates.
 */
@Composable
fun PanelRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: Painter? = null,
    value: String? = null,
    valueColor: Color = Color.Unspecified,
    trailing: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    chevron: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val c = AppTheme.colors
    val base = modifier
        .fillMaxWidth()
        .heightIn(min = if (subtitle != null) 64.dp else 52.dp)
    val clickMod = if (onClick != null) base.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else base
    Row(clickMod.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (leading != null) {
            Icon(leading, contentDescription = null, tint = c.muted, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) c.text else c.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        if (value != null) {
            Spacer(Modifier.width(8.dp))
            Text(value, style = Mono.value, color = if (valueColor == Color.Unspecified) c.muted else valueColor, maxLines = 1)
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
        if (chevron) {
            Spacer(Modifier.width(4.dp))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = c.muted, modifier = Modifier.size(16.dp))
        }
    }
}

/** A section holding one panel of free content. Kept for the screens that group a few controls. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    @Suppress("UNUSED_PARAMETER") icon: Painter? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    if (title != null) {
        Section(title, modifier, trailing = trailing) {
            Panel { PanelBody(content = content) }
        }
    } else {
        Panel(modifier.padding(top = 24.dp)) { PanelBody(content = content) }
    }
}

/** A single navigation row in its own panel. */
@Composable
fun NavCard(icon: Painter, title: String, subtitle: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Panel(modifier) { PanelRow(title, subtitle = subtitle, leading = icon, chevron = true, onClick = onClick) }
}

/** Small sentence-case label inside a panel. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = AppTheme.colors.muted, modifier = modifier)
}

/** One-line muted hint under a setting. */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (color == Color.Unspecified) AppTheme.colors.muted else color,
        modifier = modifier
    )
}

// =====================================================================================================
// Status: 6 dp dot + word. Never colour alone.
// =====================================================================================================

enum class Tone { GOOD, WARN, BAD, LIVE, IDLE }

@Composable
fun toneColor(tone: Tone): Color {
    val c = AppTheme.colors
    return when (tone) {
        Tone.GOOD -> c.good
        Tone.WARN -> c.warn
        Tone.BAD -> c.bad
        Tone.LIVE -> c.accent
        Tone.IDLE -> c.muted
    }
}

@Composable
fun StatusDot(tone: Tone, modifier: Modifier = Modifier, size: Dp = 6.dp) {
    Box(modifier.size(size).clip(CircleShape).background(toneColor(tone)))
}

/** Dot + word, e.g. "● Needs attention". */
@Composable
fun StatusWord(tone: Tone, word: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        StatusDot(tone)
        Spacer(Modifier.width(6.dp))
        Text(
            word,
            style = MaterialTheme.typography.bodySmall,
            color = if (tone == Tone.IDLE) AppTheme.colors.muted else AppTheme.colors.text,
            maxLines = 1
        )
    }
}

/** A small status chip (dot + text), e.g. Home's "Setup: 2 to check". Tappable when [onClick] is set. */
@Composable
fun StatusChip(tone: Tone, text: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val c = AppTheme.colors
    val base = modifier
        .clip(MaterialTheme.shapes.small)
        .border(1.dp, c.line, MaterialTheme.shapes.small)
        .background(c.surface)
    val m = if (onClick != null) base.clickable(role = Role.Button, onClick = onClick) else base
    Row(m.heightIn(min = 28.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusDot(tone)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = c.text, maxLines = 1)
        if (onClick != null) {
            Spacer(Modifier.width(2.dp))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = c.muted, modifier = Modifier.size(14.dp))
        }
    }
}

/** Tag with a dot (host type, party, sharing app). The colour is the dot only. */
@Composable
fun TagChip(text: String, modifier: Modifier = Modifier, color: Color = AppTheme.colors.muted) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.text, maxLines = 1)
    }
}

/** A mono value, e.g. "buffer 60 ms". */
@Composable
fun StatPill(text: String, modifier: Modifier = Modifier, color: Color = AppTheme.colors.muted) {
    Text(text, style = Mono.value, color = color, modifier = modifier, maxLines = 1)
}

/** An inline notice: what happened and what to do, in one sentence. A dot carries the tone. */
@Composable
fun Banner(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = AppTheme.colors.bad,
    @Suppress("UNUSED_PARAMETER") icon: Painter? = null,
    onClick: (() -> Unit)? = null
) {
    val c = AppTheme.colors
    val base = modifier
        .fillMaxWidth()
        .clip(MaterialTheme.shapes.medium)
        .background(c.surface)
        .border(1.dp, color.copy(alpha = 0.55f), MaterialTheme.shapes.medium)
    val m = if (onClick != null) base.clickable(role = Role.Button, onClick = onClick) else base
    Row(m.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = c.text, modifier = Modifier.weight(1f))
        if (onClick != null) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = c.muted, modifier = Modifier.size(16.dp))
        }
    }
}

// =====================================================================================================
// Controls: buttons 32 dp / radius 6, segmented 28 dp, thin slider, neutral toggles.
// =====================================================================================================

@Composable
private fun ButtonBox(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    bg: Color,
    fg: Color,
    border: Color?,
    leading: Painter?
) {
    val shape = MaterialTheme.shapes.small
    var m = modifier
        .defaultMinSize(minHeight = 32.dp)
        .clip(shape)
        .background(bg)
    if (border != null) m = m.border(1.dp, border, shape)
    m = m.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    Row(
        m.padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val color = if (enabled) fg else fg.copy(alpha = 0.4f)
        if (leading != null) {
            Icon(leading, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = color, maxLines = 1, textAlign = TextAlign.Center)
    }
}

/** The one primary action of a view: solid accent. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, leading: Painter? = null) {
    val c = AppTheme.colors
    ButtonBox(text, onClick, modifier, enabled, if (enabled) c.accent else c.surface2, if (enabled) c.onAccent else c.muted, null, leading)
}

/** Secondary action: outline. */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, leading: Painter? = null) {
    val c = AppTheme.colors
    ButtonBox(text, onClick, modifier, enabled, c.surface, c.text, c.line, leading)
}

/** Quiet action: text only. */
@Composable
fun QuietButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = AppTheme.colors.accentText
) {
    ButtonBox(text, onClick, modifier, enabled, Color.Transparent, color, null, null)
}

/** Single choice: 28 dp, radius 6; the selected segment is Surface2 with accent text, not a solid block. */
@Composable
fun <T> Segmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    val c = AppTheme.colors
    val outer = MaterialTheme.shapes.small
    Row(
        modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(outer)
            .background(c.surface)
            .border(1.dp, c.line, outer)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        options.forEach { (value, label) ->
            val isSel = value == selected
            val bg by animateColorAsState(if (isSel) c.surface2 else Color.Transparent, tween(140), label = "seg")
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(bg)
                    .clickable(role = Role.RadioButton) { onSelect(value) }
                    .semantics { stateDescription = if (isSel) "Selected" else "Not selected" },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isSel) c.accentText else c.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Title + optional hint with a switch on the right; the whole row toggles. Accent only when on. */
@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val c = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Switch) { onCheckedChange(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = c.text)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.onAccent,
                checkedTrackColor = c.accent,
                checkedBorderColor = c.accent,
                uncheckedThumbColor = c.muted,
                uncheckedTrackColor = c.surface2,
                uncheckedBorderColor = c.line
            )
        )
    }
}

/** Thin slider: 2 dp track, accent fill, small thumb. Accessible (range info + set progress). */
@Composable
fun ThinSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    description: String = ""
) {
    val c = AppTheme.colors
    val latest by rememberUpdatedState(onValueChange)
    var widthPx by remember { mutableIntStateOf(1) }
    val thumbR = 7.dp
    val span = valueRange.endInclusive - valueRange.start
    val fraction = if (span > 0f) ((value - valueRange.start) / span).coerceIn(0f, 1f) else 0f
    Box(
        modifier
            .fillMaxWidth()
            .height(28.dp)
            .onSizeChanged { widthPx = max(1, it.width) }
            .pointerInput(enabled, valueRange) {
                if (!enabled) return@pointerInput
                val r = thumbR.toPx()
                detectTapGestures { p ->
                    val f = ((p.x - r) / (widthPx - 2 * r).coerceAtLeast(1f)).coerceIn(0f, 1f)
                    latest(valueRange.start + f * span)
                }
            }
            .pointerInput(enabled, valueRange) {
                if (!enabled) return@pointerInput
                val r = thumbR.toPx()
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    val f = ((change.position.x - r) / (widthPx - 2 * r).coerceAtLeast(1f)).coerceIn(0f, 1f)
                    latest(valueRange.start + f * span)
                }
            }
            .semantics {
                if (description.isNotBlank()) contentDescription = description
                progressBarRangeInfo = ProgressBarRangeInfo(value, valueRange)
                if (enabled) {
                    setProgress { target ->
                        latest(target.coerceIn(valueRange.start, valueRange.endInclusive))
                        true
                    }
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = thumbR.toPx()
            val y = size.height / 2f
            val left = r
            val right = size.width - r
            val x = left + (right - left) * fraction
            val stroke = 2.dp.toPx()
            drawRect(c.line, topLeft = Offset(left, y - stroke / 2), size = Size(right - left, stroke))
            drawRect(if (enabled) c.accent else c.muted, topLeft = Offset(left, y - stroke / 2), size = Size(x - left, stroke))
            drawCircle(if (enabled) c.accent else c.muted, radius = r * 0.85f, center = Offset(x, y))
        }
    }
}

/** Label, mono value at the right in a fixed width (so rows align), thin slider below, optional hint. */
@Composable
fun SliderRow(
    label: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    hint: String? = null,
    valueColor: Color = Color.Unspecified
) {
    val c = AppTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = c.text, modifier = Modifier.weight(1f))
            Text(
                valueText,
                style = Mono.large,
                color = if (valueColor == Color.Unspecified) c.text else valueColor,
                textAlign = TextAlign.End,
                modifier = Modifier.width(72.dp)
            )
        }
        ThinSlider(value, onValueChange, valueRange, description = label)
        if (hint != null) Hint(hint)
    }
}

/** One-line reason shown before a system permission prompt (or an optional setup step). */
@Composable
fun ReasonDialog(
    title: String,
    reason: String,
    confirmLabel: String = "Continue",
    dismissLabel: String = "Not now",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val c = AppTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleLarge, color = c.text) },
        text = { Text(reason, style = MaterialTheme.typography.bodyMedium, color = c.muted) },
        confirmButton = { PrimaryButton(confirmLabel, onConfirm) },
        dismissButton = { QuietButton(dismissLabel, onDismiss, color = c.muted) },
        containerColor = c.surface,
        shape = MaterialTheme.shapes.medium
    )
}

/** Numbered step: a mono number, no badge. */
@Composable
fun StepLine(number: Int, text: String) {
    val c = AppTheme.colors
    Row(verticalAlignment = Alignment.Top) {
        Text("$number", style = Mono.value, color = c.muted, modifier = Modifier.width(20.dp).padding(top = 2.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = c.text, modifier = Modifier.weight(1f))
    }
}

// =====================================================================================================
// Meters
// =====================================================================================================

/**
 * Live output meter: a short scrolling history of levels drawn as thin bars. Collects [level] itself and only
 * invalidates the draw phase, so it never recomposes the screen.
 */
@Composable
fun LevelMeter(level: StateFlow<Float>, active: Boolean, modifier: Modifier = Modifier, bars: Int = 48) {
    val history = remember(bars) { FloatArray(bars) }
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(level, bars) {
        level.collect { v ->
            System.arraycopy(history, 1, history, 0, bars - 1)
            history[bars - 1] = v
            frame++
        }
    }
    val c = AppTheme.colors
    Canvas(modifier.fillMaxWidth().height(24.dp)) {
        if (frame < 0) return@Canvas // reading `frame` here makes a new level only redraw, never recompose
        val slot = size.width / bars
        val barW = max(1f, slot * 0.4f)
        for (i in 0 until bars) {
            val v = if (active) history[i].coerceIn(0f, 1f) else 0f
            val h = max(1.dp.toPx(), size.height * v.pow(0.6f))
            drawRect(
                color = if (v > 0.02f) c.accent else c.line,
                topLeft = Offset(i * slot + (slot - barW) / 2f, size.height - h),
                size = Size(barW, h)
            )
        }
    }
}

/**
 * The sync scope: a ±50 ms scale with a tick at 0 and a marker at this ear's offset from the common deadline
 * (positive = late). Marker colour follows [ScopeMath.grade]; beyond the scale it sits at the edge.
 */
@Composable
fun SyncScope(offsetMs: Double?, modifier: Modifier = Modifier, rangeMs: Double = ScopeMath.RANGE_MS) {
    val c = AppTheme.colors
    val grade = ScopeMath.grade(offsetMs)
    val marker = when (grade) {
        SyncGrade.IN_SYNC -> c.good
        SyncGrade.CLOSE -> c.warn
        SyncGrade.OFF -> c.bad
        SyncGrade.UNKNOWN -> c.muted
    }
    val pos = ScopeMath.position(offsetMs ?: Double.NaN, rangeMs)
    val desc = if (offsetMs == null) "Sync offset unknown" else "Sync offset ${ScopeMath.signedMs(offsetMs)} ${ScopeMath.direction(offsetMs)}"
    Column(modifier.fillMaxWidth().semantics { contentDescription = desc }) {
        Canvas(Modifier.fillMaxWidth().height(18.dp)) {
            val y = size.height / 2f
            val w = size.width
            val line = 1.dp.toPx()
            drawRect(c.line, topLeft = Offset(0f, y - line / 2), size = Size(w, line))
            // Ticks at −50, −25, 0, +25, +50; 0 is taller.
            for (k in 0..4) {
                val x = (w - line) * k / 4f
                val th = if (k == 2) size.height else size.height * 0.45f
                drawRect(if (k == 2) c.muted else c.line, topLeft = Offset(x, y - th / 2), size = Size(line, th))
            }
            if (offsetMs != null) {
                val mw = 3.dp.toPx()
                val x = ((w - mw) * pos).coerceIn(0f, w - mw)
                drawRect(marker, topLeft = Offset(x, 0f), size = Size(mw, size.height))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text("−50", style = Mono.value, color = c.muted)
            Spacer(Modifier.weight(1f))
            Text("0", style = Mono.value, color = c.muted)
            Spacer(Modifier.weight(1f))
            Text("+50 ms", style = Mono.value, color = c.muted)
        }
    }
}

/** Row content helper for actions laid out left to right with 8 dp gaps. */
@Composable
fun ActionRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}

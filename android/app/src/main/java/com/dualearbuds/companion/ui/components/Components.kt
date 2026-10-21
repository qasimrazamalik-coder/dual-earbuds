package com.dualearbuds.companion.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dualearbuds.companion.ui.theme.AppTheme
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.max
import kotlin.math.pow

/** Rounded card with an optional icon + title header. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    icon: Painter? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (title != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (icon != null) {
                        IconBadge(icon)
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (trailing != null) trailing()
                }
            }
            content()
        }
    }
}

@Composable
fun IconBadge(icon: Painter, size: Dp = 32.dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(size * 0.56f))
    }
}

/** Small uppercase heading above a group of cards. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = AppTheme.colors.textDim,
        modifier = modifier.padding(start = 4.dp, top = 4.dp)
    )
}

/** Circular battery gauge. [percent] = -1 shows an empty ring with "--". */
@Composable
fun BatteryRing(percent: Int, modifier: Modifier = Modifier, diameter: Dp = 76.dp, stroke: Dp = 8.dp) {
    val known = percent in 0..100
    val target = if (known) percent / 100f else 0f
    val progress by animateFloatAsState(targetValue = target, animationSpec = tween(700), label = "battery")
    val colors = AppTheme.colors
    val color = when {
        !known -> colors.textDim
        percent <= 15 -> colors.danger
        percent <= 30 -> colors.warning
        else -> colors.accent
    }
    val track = colors.track
    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = stroke.toPx()
            val inset = sw / 2f
            val arcSize = Size(size.width - sw, size.height - sw)
            drawArc(
                color = track,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = sw)
            )
            if (known) {
                drawArc(
                    color = color,
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = sw, cap = StrokeCap.Round)
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (known) "$percent%" else "--",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (known) MaterialTheme.colorScheme.onSurface else colors.textDim
            )
            Text("battery", style = MaterialTheme.typography.labelSmall, color = colors.textDim)
        }
    }
}

/** Round Start / Stop button with a soft breathing ring while running. */
@Composable
fun StartStopButton(running: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, diameter: Dp = 128.dp) {
    val colors = AppTheme.colors
    val ring = if (running) {
        val transition = rememberInfiniteTransition(label = "pulse")
        val pulse by transition.animateFloat(
            initialValue = 1f,
            targetValue = 1.07f,
            animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "pulseScale"
        )
        pulse
    } else {
        1f
    }
    val fill by animateColorAsState(
        if (running) MaterialTheme.colorScheme.surfaceContainerHighest else colors.accent,
        animationSpec = tween(300),
        label = "fill"
    )
    val textColor by animateColorAsState(
        if (running) colors.accentText else colors.onAccent,
        animationSpec = tween(300),
        label = "text"
    )
    Box(modifier.size(diameter + 12.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(diameter + 8.dp)
                .scale(ring)
                .border(2.dp, colors.accent.copy(alpha = if (running) 0.55f else 0.2f), CircleShape)
        )
        Box(
            Modifier
                .size(diameter)
                .clip(CircleShape)
                .background(fill)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (running) "STOP" else "START",
                color = if (enabled) textColor else textColor.copy(alpha = 0.4f),
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 3.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Live output meter: a short scrolling history of levels drawn as rounded bars. Collects [level] itself and
 * only invalidates the draw phase, so it never recomposes the screen.
 */
@Composable
fun LevelMeter(level: StateFlow<Float>, active: Boolean, modifier: Modifier = Modifier, bars: Int = 32) {
    val history = remember(bars) { FloatArray(bars) }
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(level, bars) {
        level.collect { v ->
            System.arraycopy(history, 1, history, 0, bars - 1)
            history[bars - 1] = v
            frame++
        }
    }
    val colors = AppTheme.colors
    Canvas(modifier.fillMaxWidth().height(40.dp)) {
        if (frame < 0) return@Canvas // reading `frame` here makes a new level only redraw, never recompose
        val slot = size.width / bars
        val barW = slot * 0.58f
        for (i in 0 until bars) {
            val v = if (active) history[i].coerceIn(0f, 1f) else 0f
            val h = max(size.height * 0.1f, size.height * v.pow(0.6f))
            val alpha = if (v > 0.02f) 0.35f + 0.65f * (i + 1) / bars else 1f
            drawRoundRect(
                color = if (v > 0.02f) colors.accent.copy(alpha = alpha) else colors.track,
                topLeft = Offset(i * slot + (slot - barW) / 2f, (size.height - h) / 2f),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f, barW / 2f)
            )
        }
    }
}

/** Small rounded pill for stats. */
@Composable
fun StatPill(text: String, modifier: Modifier = Modifier, color: Color = AppTheme.colors.textDim) {
    Box(
        modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

/** Tinted chip with a dot, e.g. "● Laptop · LAPTOP-NAME" or "Party mode". */
@Composable
fun TagChip(text: String, modifier: Modifier = Modifier, color: Color = AppTheme.colors.accentText) {
    Row(
        modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
    }
}

/** Coloured banner for errors / warnings / tips. */
@Composable
fun Banner(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = AppTheme.colors.danger,
    icon: Painter? = null,
    onClick: (() -> Unit)? = null
) {
    val base = modifier
        .fillMaxWidth()
        .clip(MaterialTheme.shapes.medium)
        .background(color.copy(alpha = 0.10f))
        .border(1.dp, color.copy(alpha = 0.40f), MaterialTheme.shapes.medium)
    val clickableMod = if (onClick != null) base.clickable(onClick = onClick) else base
    Row(clickableMod.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Pill-shaped single-choice control (lighter than Material's segmented buttons, and not experimental). */
@Composable
fun <T> Segmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = AppTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEach { (value, label) ->
            val isSel = value == selected
            val bg by animateColorAsState(if (isSel) colors.accent else Color.Transparent, tween(200), label = "seg")
            val fg by animateColorAsState(
                if (isSel) colors.onAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                tween(200),
                label = "segText"
            )
            Box(
                Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(bg)
                    .clickable(role = Role.RadioButton) { onSelect(value) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
            }
        }
    }
}

/** Title + optional subtitle with a switch on the right. The whole row toggles. */
@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(role = Role.Switch) { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.textDim)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.onAccent,
                checkedTrackColor = colors.accent
            )
        )
    }
}

/** Tappable card row that navigates somewhere: icon, title, subtitle, chevron. */
@Composable
fun NavCard(icon: Painter, title: String, subtitle: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable(role = Role.Button, onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, size = 36.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.textDim)
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = AppTheme.colors.textDim
            )
        }
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = { Text(reason, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
        containerColor = MaterialTheme.colorScheme.surface
    )
}

/** Numbered step line used by the guides. */
@Composable
fun StepLine(number: Int, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text("$number", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

/** Soft vertical gradient used behind the status hero. */
@Composable
fun heroBrush(): Brush {
    val c = AppTheme.colors
    return Brush.verticalGradient(listOf(c.heroTop, c.heroBottom))
}

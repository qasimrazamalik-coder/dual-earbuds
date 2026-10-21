package com.dualearbuds.companion.ui.components

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
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
import com.dualearbuds.companion.ui.theme.Palette

/** Rounded navy card with an optional icon + title header. */
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
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        }
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

/** Circular battery gauge. [percent] = -1 shows an empty ring with "--". */
@Composable
fun BatteryRing(percent: Int, modifier: Modifier = Modifier, diameter: Dp = 88.dp, stroke: Dp = 9.dp) {
    val known = percent in 0..100
    val target = if (known) percent / 100f else 0f
    val progress by animateFloatAsState(targetValue = target, animationSpec = tween(700), label = "battery")
    val color = when {
        !known -> Palette.TextDim
        percent <= 15 -> Palette.Coral
        percent <= 30 -> Palette.Amber
        else -> Palette.Teal
    }
    val track = MaterialTheme.colorScheme.surfaceVariant
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
                    brush = Brush.sweepGradient(listOf(color.copy(alpha = 0.55f), color, color)),
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
                style = MaterialTheme.typography.titleLarge,
                color = if (known) MaterialTheme.colorScheme.onSurface else Palette.TextDim
            )
            Text("battery", style = MaterialTheme.typography.labelSmall, color = Palette.TextDim)
        }
    }
}

/** Big round Start / Stop button with a soft pulse while running. */
@Composable
fun StartStopButton(running: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulseScale"
    )
    val ringScale = if (running) pulse else 1f
    val fill = if (running) {
        Brush.radialGradient(listOf(Color(0xFF203154), Color(0xFF141F38)))
    } else {
        Brush.radialGradient(listOf(Palette.Teal, Palette.TealDeep))
    }
    val textColor = if (running) Palette.Teal else Palette.TealInk
    Box(modifier.size(196.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(188.dp)
                .scale(ringScale)
                .border(2.dp, Palette.Teal.copy(alpha = if (running) 0.55f else 0.18f), CircleShape)
        )
        Box(
            Modifier
                .size(164.dp)
                .clip(CircleShape)
                .background(fill)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (running) "STOP" else "START",
                color = if (enabled) textColor else textColor.copy(alpha = 0.4f),
                fontSize = 30.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 3.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** Small rounded pill for stats. */
@Composable
fun StatPill(text: String, modifier: Modifier = Modifier, color: Color = Palette.TextDim) {
    Box(
        modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

/** Coloured banner for errors / warnings. */
@Composable
fun Banner(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Palette.Coral,
    icon: Painter? = null,
    onClick: (() -> Unit)? = null
) {
    val base = modifier
        .fillMaxWidth()
        .clip(MaterialTheme.shapes.medium)
        .background(color.copy(alpha = 0.12f))
        .border(1.dp, color.copy(alpha = 0.45f), MaterialTheme.shapes.medium)
    val clickableMod = if (onClick != null) base.clickable(onClick = onClick) else base
    Row(clickableMod.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

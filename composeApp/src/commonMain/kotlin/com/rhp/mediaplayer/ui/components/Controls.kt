package com.rhp.mediaplayer.ui.components

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import kotlinx.coroutines.delay

/**
 * An icon button whose "on" state is a filled pill rather than a tint.
 *
 * Shuffle and repeat are toggles, so they need to read as on/off at a glance;
 * a bare colour change is easy to miss against artwork.
 *
 * [tooltip] is kept apart from [contentDescription] on purpose. The description
 * is read out by assistive technology and can afford a full sentence; a tooltip
 * is scanned at a glance and has to stay short. Buttons whose icon is
 * self-evident leave it null and get no tooltip at all.
 */
@Composable
fun IconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
    size: Int = 36,
    tooltip: String? = null,
) {
    if (tooltip == null) {
        IconActionButton(icon, contentDescription, onClick, modifier, enabled, active, size)
    } else {
        HoverTooltip(label = tooltip, modifier = modifier) { dismiss ->
            IconActionButton(
                icon = icon,
                contentDescription = contentDescription,
                // Activating the button dismisses its own tooltip, which is what
                // every desktop toolkit does -- the label has done its job by
                // then.
                onClick = {
                    dismiss()
                    onClick()
                },
                modifier = Modifier,
                enabled = enabled,
                active = active,
                size = size,
            )
        }
    }
}

/**
 * Runs [content] with a label that appears while the pointer rests on it.
 *
 * Material3's `TooltipBox` is deliberately not used here: on this version it
 * answers a long press but not a hover, and a hover is what a mouse needs.
 * Tracking the hover and showing a popup is a dozen lines, and it lets the delay
 * and the placement be the ones a desktop pointer expects.
 *
 * [content] is handed a dismiss callback so a control that is activated can put
 * its own label away: leaving it up under a pointer that has not moved reads as
 * a stuck tooltip.
 */
@Composable
fun HoverTooltip(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable (dismiss: () -> Unit) -> Unit,
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var shown by remember { mutableStateOf(false) }

    val gap = with(LocalDensity.current) { TOOLTIP_GAP.roundToPx() }
    val positionProvider = remember(gap) { BelowAnchor(gapPx = gap) }

    LaunchedEffect(hovered) {
        if (!hovered) {
            shown = false
            return@LaunchedEffect
        }
        // The delay is the whole point of the widget: without it the label
        // flashes up whenever the pointer merely crosses the toolbar.
        delay(TOOLTIP_DELAY_MS)
        shown = true
    }

    Box(modifier = modifier.hoverable(hover)) {
        content { shown = false }

        if (shown) {
            Popup(popupPositionProvider = positionProvider) { TooltipBubble(label) }
        }
    }
}

/**
 * A round action that floats over content rather than lining up in a toolbar.
 *
 * Used for the list's way back to the track that is playing. Floating is what
 * the job needs: the list scrolls, so a button pinned to a toolbar would be
 * wherever the user is not, and would have to be found before it could be used.
 *
 * Drawn round rather than as a Material FAB's rounded square, so that it sits
 * with the circles [IconAction] already puts along the transport bar. The
 * tooltip matters more here than on a toolbar, too -- an icon that only exists
 * in some scroll positions has nothing beside it to explain itself.
 */
@Composable
fun FloatingIconAction(
    icon: ImageVector,
    contentDescription: String,
    tooltip: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HoverTooltip(label = tooltip, modifier = modifier) { dismiss ->
        SmallFloatingActionButton(
            onClick = {
                dismiss()
                onClick()
            },
            shape = CircleShape,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(FAB_ICON),
            )
        }
    }
}

/**
 * Places the tooltip under the anchor, centred, and kept inside the window.
 *
 * The clamp matters for the last button in a toolbar: without it the label
 * would hang off the edge and be cut in half by the screen.
 */
private class BelowAnchor(private val gapPx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val centred = anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2
        return IntOffset(
            x = centred.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            y = (anchorBounds.bottom + gapPx)
                .coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
        )
    }
}

/**
 * The tooltip surface.
 *
 * `inverseSurface` is the M3 pairing for this: dark bubble on a light theme,
 * light bubble on a dark one, so no colour has to be chosen per theme.
 */
@Composable
private fun TooltipBubble(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shape = RoundedCornerShape(6.dp),
        shadowElevation = 4.dp,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun IconActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    active: Boolean,
    size: Int,
) {
    val container = when {
        active -> MaterialTheme.colorScheme.primaryContainer
        else -> Color.Transparent
    }
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        active -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> LocalContentColor.current
    }

    Surface(
        color = container,
        shape = RoundedCornerShape(percent = 50),
        modifier = modifier.clip(RoundedCornerShape(percent = 50)),
    ) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size((size * 0.58f).dp),
            )
        }
    }
}

/** How long the pointer has to rest before the label appears. */
private const val TOOLTIP_DELAY_MS = 420L

/** Space between the button and its label. */
private val TOOLTIP_GAP = 6.dp

/** Icon size inside the floating action button. */
private val FAB_ICON = 20.dp

/**
 * A compact text toggle.
 *
 * Used where an icon would be ambiguous and the label is short enough to just
 * say what it does -- which, in a Chinese-language interface, is usually
 * clearer than a glyph: "随机" and "单曲循环" need no legend.
 */
@Composable
fun TextToggle(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        color = if (active) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            Color.Transparent
        },
        contentColor = if (active) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        shape = RoundedCornerShape(8.dp),
        modifier = modifier,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/** Section heading used by the sidebar and the queue panel. */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 4.dp, vertical = 6.dp),
    )
}

/** A row of equally spaced controls, used for the transport cluster. */
@Composable
fun ControlCluster(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        content()
    }
}

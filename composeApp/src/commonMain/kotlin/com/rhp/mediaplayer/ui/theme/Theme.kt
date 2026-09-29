package com.rhp.mediaplayer.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Theme switching, animated.
 *
 * Only the two schemes are defined by hand; the transition between them is a
 * single float animating from 0 to 1, with each role interpolated in step.
 *
 * Animating one value rather than thirty-odd `animateColorAsState` calls keeps
 * the switch to a single animation driving a single recomposition per frame,
 * and -- more importantly -- makes it impossible for two roles to be caught
 * mid-transition on different schedules, which is what produces the muddy
 * in-between look that a partial theme change has.
 */
@Composable
fun AppTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    val progress by animateFloatAsState(
        targetValue = if (darkTheme) 1f else 0f,
        animationSpec = tween(durationMillis = THEME_TRANSITION_MS, easing = LinearEasing),
        label = "theme-progress",
    )

    MaterialTheme(
        colorScheme = blend(progress),
        typography = AppTypography,
        content = content,
    )
}

private const val THEME_TRANSITION_MS = 260

private fun blend(t: Float): ColorScheme = darkColorScheme(
    primary = lerp(Light.primary, Dark.primary, t),
    onPrimary = lerp(Light.onPrimary, Dark.onPrimary, t),
    primaryContainer = lerp(Light.primaryContainer, Dark.primaryContainer, t),
    onPrimaryContainer = lerp(Light.onPrimaryContainer, Dark.onPrimaryContainer, t),
    inversePrimary = lerp(Light.inversePrimary, Dark.inversePrimary, t),
    secondary = lerp(Light.secondary, Dark.secondary, t),
    onSecondary = lerp(Light.onSecondary, Dark.onSecondary, t),
    secondaryContainer = lerp(Light.secondaryContainer, Dark.secondaryContainer, t),
    onSecondaryContainer = lerp(Light.onSecondaryContainer, Dark.onSecondaryContainer, t),
    tertiary = lerp(Light.tertiary, Dark.tertiary, t),
    onTertiary = lerp(Light.onTertiary, Dark.onTertiary, t),
    tertiaryContainer = lerp(Light.tertiaryContainer, Dark.tertiaryContainer, t),
    onTertiaryContainer = lerp(Light.onTertiaryContainer, Dark.onTertiaryContainer, t),
    background = lerp(Light.background, Dark.background, t),
    onBackground = lerp(Light.onBackground, Dark.onBackground, t),
    surface = lerp(Light.surface, Dark.surface, t),
    onSurface = lerp(Light.onSurface, Dark.onSurface, t),
    surfaceVariant = lerp(Light.surfaceVariant, Dark.surfaceVariant, t),
    onSurfaceVariant = lerp(Light.onSurfaceVariant, Dark.onSurfaceVariant, t),
    surfaceTint = lerp(Light.surfaceTint, Dark.surfaceTint, t),
    inverseSurface = lerp(Light.inverseSurface, Dark.inverseSurface, t),
    inverseOnSurface = lerp(Light.inverseOnSurface, Dark.inverseOnSurface, t),
    error = lerp(Light.error, Dark.error, t),
    onError = lerp(Light.onError, Dark.onError, t),
    errorContainer = lerp(Light.errorContainer, Dark.errorContainer, t),
    onErrorContainer = lerp(Light.onErrorContainer, Dark.onErrorContainer, t),
    outline = lerp(Light.outline, Dark.outline, t),
    outlineVariant = lerp(Light.outlineVariant, Dark.outlineVariant, t),
    scrim = lerp(Light.scrim, Dark.scrim, t),
    surfaceBright = lerp(Light.surfaceBright, Dark.surfaceBright, t),
    surfaceDim = lerp(Light.surfaceDim, Dark.surfaceDim, t),
    surfaceContainer = lerp(Light.surfaceContainer, Dark.surfaceContainer, t),
    surfaceContainerHigh = lerp(Light.surfaceContainerHigh, Dark.surfaceContainerHigh, t),
    surfaceContainerHighest = lerp(Light.surfaceContainerHighest, Dark.surfaceContainerHighest, t),
    surfaceContainerLow = lerp(Light.surfaceContainerLow, Dark.surfaceContainerLow, t),
    surfaceContainerLowest = lerp(Light.surfaceContainerLowest, Dark.surfaceContainerLowest, t),
)

/**
 * A calm indigo/violet accent, chosen to sit behind album art rather than
 * compete with it: the artwork is the only saturated thing on screen.
 *
 * ## Panels are separated by tone, not by lines
 *
 * The window has four regions -- playlists, the song list, the play queue and
 * the transport -- and they are distinguished by walking the
 * `surfaceContainer*` scale rather than by drawing dividers between them:
 *
 * | region        | role                   |
 * |---------------|------------------------|
 * | song list     | `surface`              |
 * | side panels   | `surfaceContainer`     |
 * | transport     | `surfaceContainerHigh` |
 *
 * This works because M3's container scale is defined relative to `surface` in
 * both modes -- recessed in light, raised in dark -- so one set of roles reads
 * correctly either way, which hand-picked grays would not.
 *
 * Separators were tried first, then softened, then dropped. A 1 dp line always
 * terminates somewhere, and a hard edge running the full height of a low
 * contrast window draws the eye before the content does.
 */
private val Light = lightColorScheme(
    primary = Color(0xFF5B54C4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE3E0FF),
    onPrimaryContainer = Color(0xFF17134A),
    inversePrimary = Color(0xFFC5C0FF),
    secondary = Color(0xFF5D5C71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE3E1F9),
    onSecondaryContainer = Color(0xFF1A1A2C),
    tertiary = Color(0xFF7A5368),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD8EC),
    onTertiaryContainer = Color(0xFF2E1123),
    background = Color(0xFFFBF8FF),
    onBackground = Color(0xFF1B1B21),
    surface = Color(0xFFFBF8FF),
    onSurface = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFFE4E1EC),
    onSurfaceVariant = Color(0xFF47464F),
    surfaceTint = Color(0xFF5B54C4),
    inverseSurface = Color(0xFF303036),
    inverseOnSurface = Color(0xFFF3F0F7),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF787680),
    outlineVariant = Color(0xFFC8C5D0),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFBF8FF),
    surfaceDim = Color(0xFFDBD9E0),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F2FA),
    surfaceContainer = Color(0xFFEFEDF4),
    surfaceContainerHigh = Color(0xFFE9E7EF),
    surfaceContainerHighest = Color(0xFFE3E1E9),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFC5C0FF),
    onPrimary = Color(0xFF2B2673),
    primaryContainer = Color(0xFF433DAB),
    onPrimaryContainer = Color(0xFFE3E0FF),
    inversePrimary = Color(0xFF5B54C4),
    secondary = Color(0xFFC7C4DC),
    onSecondary = Color(0xFF2F2E41),
    secondaryContainer = Color(0xFF46455A),
    onSecondaryContainer = Color(0xFFE3E1F9),
    tertiary = Color(0xFFECB8D0),
    onTertiary = Color(0xFF47263A),
    tertiaryContainer = Color(0xFF613C51),
    onTertiaryContainer = Color(0xFFFFD8EC),
    background = Color(0xFF131318),
    onBackground = Color(0xFFE4E1E9),
    surface = Color(0xFF131318),
    onSurface = Color(0xFFE4E1E9),
    surfaceVariant = Color(0xFF47464F),
    onSurfaceVariant = Color(0xFFC8C5D0),
    surfaceTint = Color(0xFFC5C0FF),
    inverseSurface = Color(0xFFE4E1E9),
    inverseOnSurface = Color(0xFF303036),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF928F99),
    outlineVariant = Color(0xFF47464F),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF39383F),
    surfaceDim = Color(0xFF131318),
    surfaceContainerLowest = Color(0xFF0E0E13),
    surfaceContainerLow = Color(0xFF1B1B21),
    surfaceContainer = Color(0xFF1F1F25),
    surfaceContainerHigh = Color(0xFF2A2A30),
    surfaceContainerHighest = Color(0xFF35343B),
)

/** Default Material typography, with track titles given a touch more presence. */
private val AppTypography = Typography().let { base ->
    base.copy(
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium),
        bodyMedium = base.bodyMedium.copy(lineHeight = 20.sp),
    )
}

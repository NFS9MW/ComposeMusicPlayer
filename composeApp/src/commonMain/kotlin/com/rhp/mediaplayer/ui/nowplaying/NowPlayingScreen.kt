package com.rhp.mediaplayer.ui.nowplaying

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rhp.mediaplayer.model.Song
import com.rhp.mediaplayer.ui.components.IconAction
import com.rhp.mediaplayer.ui.icons.AppIcons

/**
 * The whole track, laid out over the library.
 *
 * Reachable by clicking the song's cover, name or artist in the transport bar,
 * and dismissed by the chevron in the corner, by Escape, or by clicking the same
 * block again. It is opaque rather than translucent: a scrim over a list of text
 * is noise, and the point of the screen is to take the library out of the way
 * for a moment.
 *
 * **It deliberately carries no transport controls.** The bar along the bottom
 * stays visible, so a second set of buttons and a second seek bar would be two
 * copies of the same thing on one screen, four inches apart. What this screen
 * is for is the part the bar cannot show: the sleeve at a size worth looking at.
 *
 * **What it is careful about is recomposition.** Everything read here -- the
 * track and the artwork -- changes once per track at most, and nothing reads the
 * playback position at all. The screen is composed only while it is open (the
 * caller wraps it in `AnimatedVisibility`), so a closed one costs exactly
 * nothing, and the backdrop below never invalidates anything because it depends
 * on the theme alone.
 */
@Composable
fun NowPlayingScreen(
    song: Song?,
    artwork: ImageBitmap?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxSize(),
    ) {
        Box(modifier = Modifier.fillMaxSize().geometricBackdrop()) {
            IconAction(
                icon = AppIcons.ChevronDown,
                contentDescription = "返回播放列表",
                tooltip = "返回播放列表（Esc）",
                onClick = onClose,
                size = 42,
                modifier = Modifier.align(Alignment.TopStart).padding(14.dp),
            )

            // The sleeve is sized against the space actually available, so the
            // screen works as well on a tall narrow window as on a wide one --
            // a fixed size either overflows or looks lost. What is under the
            // sleeve is a fixed height, so the sleeve gets whatever is left over
            // rather than a fraction that happens to suit one window size.
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp, vertical = 44.dp),
                contentAlignment = Alignment.Center,
            ) {
                val sleeve = minOf(
                    maxWidth * 0.46f,
                    (maxHeight - UNDER_THE_SLEEVE).coerceAtLeast(140.dp),
                    480.dp,
                )
                // Long titles read badly over the full width of a wide monitor.
                val width = minOf(maxWidth, 560.dp)

                Column(
                    modifier = Modifier.width(width),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Sleeve(artwork = artwork, side = sleeve)

                    Spacer(Modifier.height(34.dp))

                    Text(
                        text = song?.title ?: "未在播放",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Spacer(Modifier.height(12.dp))

                    Text(
                        text = subtitleOf(song),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * The height of everything below the sleeve -- title, artist and the gaps
 * between them.
 *
 * Kept as one number, next to the column that spends it, so that adding a row
 * there is an obvious reason to revisit this rather than a silent crowding.
 */
private val UNDER_THE_SLEEVE = 116.dp

/** The sleeve, or a placeholder when the file carries no artwork. */
@Composable
private fun Sleeve(artwork: ImageBitmap?, side: Dp) {
    Box(
        modifier = Modifier
            .size(side)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (artwork != null) {
            Image(
                bitmap = artwork,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = AppIcons.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(side * 0.2f),
            )
        }
    }
}

/**
 * Decorative geometry behind the sleeve.
 *
 * A flat field of one colour with a square in the middle of it reads as a
 * placeholder rather than as a screen, so this puts something behind it: two
 * soft glows and two large outlines, at opacities low enough that none of them
 * competes with the cover or the title.
 *
 * Three deliberate constraints, all of them about not paying for decoration:
 *
 *  * every colour is one of the theme's own, so both themes work without a
 *    second set of values to keep in step;
 *  * nothing is blurred -- `Modifier.blur` is on this project's banned list, and
 *    a radial gradient already looks soft for free;
 *  * nothing animates and nothing reads state, so it draws once per size change
 *    and never triggers a recomposition. `drawWithCache` is what makes that
 *    literal rather than hopeful: the gradients are built when the size changes,
 *    not on every frame.
 */
@Composable
private fun Modifier.geometricBackdrop(): Modifier {
    val scheme = MaterialTheme.colorScheme
    val primary = scheme.primary
    val secondary = scheme.secondary
    val tertiary = scheme.tertiary

    return drawWithCache {
        val span = maxOf(size.width, size.height)
        val width = size.width
        val height = size.height

        // Two glows on opposite corners, so the middle stays even and the
        // sleeve does not sit on a colour that changes under it.
        //
        // The alphas look high for "decoration", and are not: the theme's
        // `primary` is a light lavender in the dark scheme and a deep purple in
        // the light one, so the same figure against either surface lands with
        // roughly the same weight. That symmetry is what the tonal palette is
        // for, and it means one set of numbers serves both themes.
        val glowNear = Brush.radialGradient(
            colors = listOf(primary.copy(alpha = 0.30f), Color.Transparent),
            center = Offset(width * 0.88f, height * 0.04f),
            radius = span * 0.62f,
        )
        val glowFar = Brush.radialGradient(
            colors = listOf(tertiary.copy(alpha = 0.26f), Color.Transparent),
            center = Offset(width * 0.04f, height * 0.98f),
            radius = span * 0.55f,
        )

        val ringCentre = Offset(width * 0.91f, height * 0.87f)
        val squareCentre = Offset(width * 0.09f, height * 0.17f)
        val squareSide = span * 0.34f

        // Three weights of line on purpose: a drawn figure needs a hierarchy, or
        // it reads as three leftovers rather than as a composition.
        val outerRing = secondary.copy(alpha = 0.34f)
        val innerRing = secondary.copy(alpha = 0.17f)
        val squareStroke = primary.copy(alpha = 0.30f)
        val bold = 2.dp.toPx()
        val hairline = 1.5.dp.toPx()
        val fine = 1.dp.toPx()

        onDrawBehind {
            drawCircle(brush = glowNear, radius = span * 0.62f, center = Offset(width * 0.88f, height * 0.04f))
            drawCircle(brush = glowFar, radius = span * 0.55f, center = Offset(width * 0.04f, height * 0.98f))

            // A pair of concentric rings, mostly running off the corner: two
            // arcs read as a drawn thing where one reads as an accident.
            drawCircle(color = outerRing, radius = span * 0.30f, center = ringCentre, style = Stroke(bold))
            drawCircle(color = innerRing, radius = span * 0.38f, center = ringCentre, style = Stroke(fine))

            // ...and a square rotated off its axis, for the same reason.
            rotate(degrees = 18f, pivot = squareCentre) {
                drawRect(
                    color = squareStroke,
                    topLeft = Offset(squareCentre.x - squareSide / 2f, squareCentre.y - squareSide / 2f),
                    size = Size(squareSide, squareSide),
                    style = Stroke(hairline),
                )
            }
        }
    }
}

/** "Artist · Album" when there is an album, the artist alone otherwise. */
private fun subtitleOf(song: Song?): String {
    if (song == null) return ""
    val album = song.album
    return if (album.isNullOrBlank()) song.displayArtist else "${song.displayArtist} · $album"
}

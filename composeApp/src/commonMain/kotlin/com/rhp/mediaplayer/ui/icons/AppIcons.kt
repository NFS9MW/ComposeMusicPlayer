package com.rhp.mediaplayer.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * The icons this app needs, drawn by hand.
 *
 * Compose's bundled icon set (`compose.materialIconsExtended`) is pinned to an
 * old Compose version and officially frozen, which makes it a poor thing to
 * depend on in a project on 1.13. Pulling a second, stale copy of Compose UI
 * graphics just to get a play triangle is a worse trade than drawing the
 * fourteen glyphs actually used here.
 *
 * Everything is defined on a 24x24 viewport. The colour set here is a
 * placeholder: [androidx.compose.material3.Icon] tints whatever it draws, so
 * callers control the colour.
 */
object AppIcons {

    private fun filled(name: String, pathData: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(
            pathData = addPathNodes(pathData),
            fill = SolidColor(Color.Black),
        ).build()

    private fun stroked(
        name: String,
        pathData: String,
        width: Float = 1.9f,
    ): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(
            pathData = addPathNodes(pathData),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ).build()

    // ------------------------------------------------------------ transport

    val Play: ImageVector = filled("Play", "M8,5 L19.5,12 L8,19 Z")

    val Pause: ImageVector = filled("Pause", "M6,5 h4 v14 h-4 Z M14,5 h4 v14 h-4 Z")

    val Next: ImageVector = filled(
        "Next",
        "M5,5 L15,12 L5,19 Z M16.6,5 h2.6 v14 h-2.6 Z",
    )

    val Previous: ImageVector = filled(
        "Previous",
        "M4.8,5 h2.6 v14 h-2.6 Z M19,5 L9,12 L19,19 Z",
    )

    // ----------------------------------------------------------- playback mode

    /**
     * Two crossing paths with arrowheads: the exchange reads as "order will be
     * rearranged", which is exactly what the button does.
     */
    val Shuffle: ImageVector = stroked(
        "Shuffle",
        "M3,7 H7.4 C11,7 13,17 16.6,17 H19 " +
            "M3,17 H7.4 C11,17 13,7 16.6,7 H19 " +
            "M16.5,14.5 L19.3,17 L16.5,19.5 " +
            "M16.5,4.5 L19.3,7 L16.5,9.5",
    )

    /** Two hooked arrows chasing each other: the universal "repeat" glyph. */
    val Repeat: ImageVector = stroked(
        "Repeat",
        "M20,9.5 C20,7 18,5.5 15.5,5.5 H9.5 " +
            "M12,3 L9,5.5 L12,8 " +
            "M4,14.5 C4,17 6,18.5 8.5,18.5 H14.5 " +
            "M12,16 L15,18.5 L12,21",
    )

    val RepeatOne: ImageVector = stroked(
        "RepeatOne",
        "M20,9.5 C20,7 18,5.5 15.5,5.5 H9.5 " +
            "M12,3 L9,5.5 L12,8 " +
            "M4,14.5 C4,17 6,18.5 8.5,18.5 H14.5 " +
            "M12,16 L15,18.5 L12,21 " +
            "M11.3,10.6 L12.8,9.7 V14.4",
    )

    // ---------------------------------------------------------------- theme

    val Sun: ImageVector = stroked("Sun", sunPath(), width = 1.8f)

    val Moon: ImageVector = filled(
        "Moon",
        "M9.37,5.51C9.19,6.15 9.1,6.82 9.1,7.5c0,4.08 3.32,7.4 7.4,7.4" +
            "c0.68,0 1.35,-0.09 1.99,-0.27C17.45,17.19 14.93,19 12,19" +
            "c-3.86,0 -7,-3.14 -7,-7c0,-2.93 1.81,-5.45 4.37,-6.49z",
    )

    // --------------------------------------------------------------- actions

    val Plus: ImageVector = stroked("Plus", "M12,5 V19 M5,12 H19")

    /** Magnifier: a ring with a handle, the one glyph everyone already reads. */
    val Search: ImageVector = stroked(
        "Search",
        "M10.5,4.4 A6.1,6.1 0 1,0 10.5,16.6 A6.1,6.1 0 1,0 10.5,4.4 Z " +
            "M15,15 L20,20",
    )

    val Close: ImageVector = stroked("Close", "M6,6 L18,18 M18,6 L6,18")

    /**
     * A three-quarter arc with an arrowhead at its open end.
     *
     * The head is what separates this from [Repeat]: an arrow arriving at a gap
     * reads as "run it again", while two arrows chasing each other read as
     * "keep going round".
     */
    val Refresh: ImageVector = stroked(
        "Refresh",
        "M17.7,8.6 A6.9,6.9 0 1,1 12,5.6 " +
            "M9.6,3.3 L12,5.6 L9.6,7.9",
        width = 1.8f,
    )

    val ChevronLeft: ImageVector = stroked("ChevronLeft", "M14.5,5.5 L8,12 L14.5,18.5")

    val ChevronRight: ImageVector = stroked("ChevronRight", "M9.5,5.5 L16,12 L9.5,18.5")

    /** Points back down at the library, which is what dismissing the screen does. */
    val ChevronDown: ImageVector = stroked("ChevronDown", "M5.5,9.5 L12,16 L18.5,9.5")

    /**
     * A crosshair: ring, four ticks, and a solid centre.
     *
     * The centre is filled rather than stroked, which is what makes this read as
     * "this spot" instead of as a circle in a bracket -- and it is the reason
     * the glyph needs two paths. A filled centre is the shape everyone already
     * knows from a map's "my location", which is the same promise this button
     * makes about a list.
     */
    val Locate: ImageVector = ImageVector.Builder(
        name = "Locate",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        addPath(
            pathData = addPathNodes(
                "M12,5.4 A6.6,6.6 0 1,0 12,18.6 A6.6,6.6 0 1,0 12,5.4 Z " +
                    "M12,2.4 V5.6 M12,18.4 V21.6 M2.4,12 H5.6 M18.4,12 H21.6",
            ),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.9f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
        addPath(
            pathData = addPathNodes("M12,9.4 A2.6,2.6 0 1,0 12,14.6 A2.6,2.6 0 1,0 12,9.4 Z"),
            fill = SolidColor(Color.Black),
        )
    }.build()

    val Delete: ImageVector = filled(
        "Delete",
        "M9.5,2.5 h5 v2 h-5 Z " +
            "M4.5,5.5 h15 v2 h-15 Z " +
            "M6.5,8.5 h11 v10.5 a2,2 0 0,1 -2,2 h-7 a2,2 0 0,1 -2,-2 Z",
    )

    val Edit: ImageVector = filled(
        "Edit",
        "M3,17.25 V21 h3.75 L17.81,9.94 l-3.75,-3.75 Z " +
            "M20.71,7.04 a1,1 0 0,0 0,-1.41 l-2.34,-2.34 a1,1 0 0,0 -1.41,0 " +
            "l-1.83,1.83 3.75,3.75 Z",
    )

    val Folder: ImageVector = filled(
        "Folder",
        "M10,4 H4 A2,2 0 0,0 2,6 V18 A2,2 0 0,0 4,20 H20 A2,2 0 0,0 22,18 V8 A2,2 0 0,0 20,6 H12 Z",
    )

    val MusicNote: ImageVector = filled(
        "MusicNote",
        "M12,3v10.55c-0.59,-0.34 -1.27,-0.55 -2,-0.55 -2.21,0 -4,1.79 -4,4s1.79,4 4,4 " +
            "4,-1.79 4,-4V7h4V3h-6z",
    )

    val Queue: ImageVector = stroked(
        "Queue",
        "M4,6 h2 M9,6 h11 M4,12 h2 M9,12 h11 M4,18 h2 M9,18 h11",
        width = 2.1f,
    )

    val SortAscending: ImageVector = stroked(
        "SortAscending",
        "M3.5,7 H13 M3.5,12 H10 M3.5,17 H13 M18.5,17.5 V6.5 M16,9 L18.5,6.5 L21,9",
    )

    val SortDescending: ImageVector = stroked(
        "SortDescending",
        "M3.5,7 H13 M3.5,12 H10 M3.5,17 H13 M18.5,6.5 V17.5 M16,15 L18.5,17.5 L21,15",
    )

    /**
     * Speaker cone filled, sound waves stroked.
     *
     * Both parts cannot share one path: an unclosed path that is *filled* gets
     * implicitly closed, so the waves would render as wedges instead of arcs.
     */
    private fun speaker(name: String, wavePath: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M4,9 H7.5 L12.5,5 V19 L7.5,15 H4 Z"),
                fill = SolidColor(Color.Black),
            )
            addPath(
                pathData = addPathNodes(wavePath),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()

    val VolumeUp: ImageVector = speaker(
        "VolumeUp",
        "M15.4,9.2 C16.9,10.7 16.9,13.3 15.4,14.8 " +
            "M18.2,6.4 C21,9.2 21,14.8 18.2,17.6",
    )

    val VolumeMuted: ImageVector = speaker(
        "VolumeMuted",
        "M15.6,9.6 L20.4,14.4 M20.4,9.6 L15.6,14.4",
    )

    /**
     * A circle plus eight evenly spaced rays.
     *
     * Generated rather than hand-written: the ray angles are the sort of thing
     * that is easy to get subtly wrong by eye and trivial to get right with
     * trigonometry, and this only runs once per process.
     */
    private fun sunPath(): String {
        val builder = StringBuilder()
        // Full circle from two semicircular arcs.
        builder.append("M12,7.3 A4.7,4.7 0 1,0 12,16.7 A4.7,4.7 0 1,0 12,7.3 Z ")
        val inner = 6.4f
        val outer = 9.1f
        for (step in 0 until 8) {
            val angle = Math.toRadians(step * 45.0)
            val dx = cos(angle).toFloat()
            val dy = sin(angle).toFloat()
            builder.append("M${12f + dx * inner},${12f + dy * inner} ")
            builder.append("L${12f + dx * outer},${12f + dy * outer} ")
        }
        return builder.toString()
    }
}

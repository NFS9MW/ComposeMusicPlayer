package com.rhp.mediaplayer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.rhp.mediaplayer.audio.FfmpegAudioEngine
import com.rhp.mediaplayer.audio.FfmpegBinaries
import com.rhp.mediaplayer.coverart.CoverArtRepository
import com.rhp.mediaplayer.coverart.DesktopCoverArtRepository
import com.rhp.mediaplayer.library.DesktopLibraryService
import com.rhp.mediaplayer.metadata.FfprobeMetadataReader
import com.rhp.mediaplayer.platform.JsonAppStorage
import com.rhp.mediaplayer.platform.JvmTextCollator
import com.rhp.mediaplayer.player.PlayerController
import com.rhp.mediaplayer.resources.Res
import com.rhp.mediaplayer.resources.app_icon
import com.rhp.mediaplayer.ui.App
import com.rhp.mediaplayer.ui.icons.AppIcons
import com.rhp.mediaplayer.ui.theme.AppTheme
import java.awt.Frame
import org.jetbrains.compose.resources.painterResource

/**
 * Whether the app found what it needs to make sound.
 *
 * ffmpeg is bundled, so a miss means the installation is damaged rather than
 * the user having to go and install something -- but either way it has to be
 * said out loud. An app that opens to a silent, unresponsive player is worse
 * than one that explains itself.
 */
private sealed interface Startup {
    data class Ready(
        val controller: PlayerController,
        val coverArt: CoverArtRepository,
    ) : Startup

    data class MissingDecoder(val diagnostics: List<String>) : Startup
}

fun main() = application {
    val startup = rememberStartup()

    val windowState = rememberWindowState(
        size = DpSize(1280.dp, 820.dp),
        // Only the maximised flag is restored. Reopening at the old size and
        // position would be worse than not trying: the display the window was
        // last on may be gone, which would put it off-screen where the user
        // cannot reach it.
        placement = if ((startup as? Startup.Ready)?.controller?.windowMaximized == true) {
            WindowPlacement.Maximized
        } else {
            WindowPlacement.Floating
        },
    )

    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        icon = painterResource(Res.drawable.app_icon),
        title = "Compose Music Player",
        onPreviewKeyEvent = { event ->
            handleEscape(startup, event) || handlePlayPauseShortcut(startup, event)
        },
    ) {
        when (startup) {
            is Startup.Ready -> {
                ObserveWindowPlacement(windowState, startup.controller)
                App(controller = startup.controller, coverArt = startup.coverArt)
            }

            is Startup.MissingDecoder -> MissingDecoderScreen(startup.diagnostics)
        }
    }
}

/**
 * Escape leaves the now-playing screen, and does nothing at all otherwise.
 *
 * Intercepted in the *preview* pass, before whatever holds focus sees it. Escape
 * means "go back" everywhere in this app, and it is not a character anyone needs
 * to type into the search box, so letting a focused component have it first
 * would only ever be a way to lose the key.
 */
private fun handleEscape(startup: Startup, event: KeyEvent): Boolean {
    val controller = (startup as? Startup.Ready)?.controller ?: return false
    if (event.type != KeyEventType.KeyDown || event.key != Key.Escape) return false
    if (!controller.nowPlayingScreenOpen) return false
    controller.closeNowPlayingScreen()
    return true
}

/**
 * Space toggles playback, for the whole window.
 *
 * In the *preview* pass, unlike a shortcut that merely listens: everything
 * clickable in this app is also focusable, and a focused component answers a
 * space by activating itself. Left in the ordinary pass, pressing space after
 * clicking a song row would re-trigger that row rather than pause anything --
 * which is the sort of thing a shortcut has to be checked with the keyboard, not
 * by reading the code.
 *
 * The one case it must not take over is typing. Space is a character, so a
 * focused search box keeps it -- but that is the only component in this window
 * that takes text, and the playlist dialogs are separate windows, so one flag is
 * enough to tell "typing" from "playing".
 */
private fun handlePlayPauseShortcut(startup: Startup, event: KeyEvent): Boolean {
    val controller = (startup as? Startup.Ready)?.controller ?: return false
    if (event.type != KeyEventType.KeyDown || event.key != Key.Spacebar) return false
    if (controller.searchFieldFocused) return false
    controller.togglePlayPause()
    return true
}

/**
 * Remembers whether the window is maximised, so the next launch can open the
 * same way.
 *
 * The frame is asked about being iconified rather than trusting the placement
 * alone. Minimising a maximised window does not report `Maximized`: the AWT
 * extended state becomes `ICONIFIED`, which Compose reads as `Floating`. Taken
 * at face value that would forget the maximise as soon as the user clicked away
 * to another window and then closed the app from the taskbar.
 */
@Composable
private fun FrameWindowScope.ObserveWindowPlacement(
    state: WindowState,
    controller: PlayerController,
) {
    LaunchedEffect(state, controller) {
        snapshotFlow { state.placement }.collect { placement ->
            val iconified = window.extendedState and Frame.ICONIFIED != 0
            if (!iconified) {
                controller.updateWindowMaximized(placement == WindowPlacement.Maximized)
            }
        }
    }
}

@Composable
private fun rememberStartup(): Startup {
    val scope = rememberCoroutineScope()

    val startup = remember {
        val (binaries, diagnostics) = FfmpegBinaries.locateOrDiagnose()
        if (binaries == null) {
            Startup.MissingDecoder(diagnostics)
        } else {
            val storage = JsonAppStorage()
            val reader = FfprobeMetadataReader(binaries)
            val coverArt = DesktopCoverArtRepository(reader)
            Startup.Ready(
                controller = PlayerController(
                    engine = FfmpegAudioEngine(binaries),
                    library = DesktopLibraryService(reader),
                    storage = storage,
                    collator = JvmTextCollator(),
                    coverArt = coverArt,
                    scope = scope,
                ),
                coverArt = coverArt,
            )
        }
    }

    DisposableEffect(startup) {
        onDispose {
            if (startup is Startup.Ready) {
                startup.controller.shutdown()
                (startup.coverArt as? DesktopCoverArtRepository)?.shutdown()
            }
        }
    }
    return startup
}

@Composable
private fun MissingDecoderScreen(diagnostics: List<String>) {
    AppTheme(darkTheme = true) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(48.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = AppIcons.VolumeMuted,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "找不到音频解码器",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }

                Spacer(Modifier.height(14.dp))

                Text(
                    text = "本应用依靠随包附带的 ffmpeg 解码音频。它没有出现在预期的位置，" +
                        "所以现在无法播放任何音乐。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(20.dp))

                Text(
                    text = "可以在启动参数中指定 ffmpeg 所在目录：",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "-D${FfmpegBinaries.OVERRIDE_PROPERTY}=<目录>\n" +
                        "或设置环境变量 ${FfmpegBinaries.OVERRIDE_ENV}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(24.dp))

                Text(
                    text = "已查找的位置：",
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(6.dp))

                val report = if (diagnostics.isEmpty()) {
                    listOf("（没有可报告的查找记录）")
                } else {
                    diagnostics
                }
                for (line in report) {
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
            }
        }
    }
}

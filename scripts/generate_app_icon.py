#!/usr/bin/env python3
"""Regenerates the application icons from ``assets/app-icon.svg``.

The generated files are committed, so an ordinary build never needs this
script. Rerun it only when the artwork changes -- which is also why it is not
wired into the Gradle build: ImageMagick is a design-time tool, not a build
dependency.

The artwork is drawn in the colours that read on a **light** background: a dark
disc with the note knocked out of it. That fails on a dark one, and fails
completely rather than partially -- the note is a hole, so it shows whatever is
behind it, and on a dark taskbar the disc and the note disappear together. The
icons are therefore built from the same drawing with the two colours swapped: a
light disc with a dark note. That reads on a dark background, and still reads on
a light one, because the note itself stays dark.

Three outputs come out of the one drawing:

* the window icon the app hands to the platform at runtime, a PNG read through
  Compose resources;
* the Windows launcher icon, an ``.ico`` carrying one image per size Windows
  asks for;
* the Linux launcher icon, a single PNG.

Linux gets one file rather than a set because jpackage takes it that way: it
reads the pixel dimensions from the image itself and snaps them to one of
16/22/32/48/64/128, so a directory of sizes would not be consulted at all. The
size written here is therefore 128 -- already on that list, which keeps the
declared size and the actual pixels in agreement.

Usage:
    python scripts/generate_app_icon.py
"""

from __future__ import annotations

import re
import shutil
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
SOURCE_SVG = REPO_ROOT / "assets" / "app-icon.svg"

WINDOW_ICON = REPO_ROOT / "composeApp/src/commonMain/composeResources/drawable/app_icon.png"
LINUX_ICON = REPO_ROOT / "packaging/icons/linux/app.png"
WINDOWS_ICON = REPO_ROOT / "packaging/icons/windows/app.ico"

#: The disc is near-white rather than pure white so it does not glare against a
#: dark background, and the note keeps the artwork's original ink.
DISC_COLOUR = "#F1F0F6"
NOTE_COLOUR = "#272636"

#: The artwork's coordinate space, needed when rebuilding it with new colours.
VIEW_BOX = 1024

#: Rendered from the vector; every other size is derived from it by halving.
MASTER_SIZE = 1024

#: Sizes Windows picks from for the taskbar, Alt-Tab, Explorer and the installer.
ICO_SIZES = (16, 24, 32, 48, 64, 128, 256)

#: Comfortably above any on-screen use, including 200% scaling.
WINDOW_ICON_SIZE = 256

#: See the module docstring: jpackage normalises to this list, and 128 is its
#: largest member.
LINUX_ICON_SIZE = 128

#: The master is rasterised from a 200x200 SVG, so the DPI has to be scaled up
#: to reach MASTER_SIZE. Rendering above the target and shrinking once beats
#: rendering at the target directly, which would soften the curves.
RENDER_DENSITY = 600

CANDIDATE_MAGICK_PATHS = (
    r"C:\Program Files\ImageMagick-7.1.2-Q16-HDRI\magick.exe",
    r"C:\Program Files\ImageMagick-7.1.2-Q16\magick.exe",
)


def find_magick() -> str:
    found = shutil.which("magick")
    if found:
        return found
    for candidate in CANDIDATE_MAGICK_PATHS:
        if Path(candidate).is_file():
            return candidate
    sys.exit(
        "ImageMagick was not found.\n"
        "Install it (https://imagemagick.org) or put 'magick' on PATH, then rerun.\n"
        "A build with librsvg support is required to rasterise the SVG correctly."
    )


def build_inverted_svg(source: Path, target: Path) -> None:
    """Rewrites the artwork with the disc and the note in swapped colours.

    The source is one ``<path>`` holding two subpaths: the disc, and the note
    that is knocked out of it. Filling the note in its own colour is what turns
    the hole into a visible shape, so the two subpaths are split at the second
    move command and emitted as two paths instead. Splitting rather than
    redrawing keeps this in step with the source drawing: change the artwork and
    the inverted version follows.
    """
    source_text = source.read_text(encoding="utf-8")
    # Anchored on whitespace so it cannot match the `d` inside `p-id`.
    match = re.search(r'\sd="([^"]+)"', source_text)
    if match is None or len(match.group(1)) < 100:
        sys.exit(f"no path data found in {source}")

    path = match.group(1)
    split = path.find("M", 1)
    if split < 0:
        sys.exit(
            f"expected two subpaths in {source}, found one.\n"
            "The inverted icon is built by filling the knocked-out note, which "
            "needs the disc and the note to be separate subpaths."
        )
    disc, note = path[:split], path[split:]

    target.write_text(
        f'<svg xmlns="http://www.w3.org/2000/svg" '
        f'viewBox="0 0 {VIEW_BOX} {VIEW_BOX}" '
        f'width="{VIEW_BOX}" height="{VIEW_BOX}">'
        f'<path d="{disc}" fill="{DISC_COLOUR}"/>'
        f'<path d="{note}" fill="{NOTE_COLOUR}"/>'
        f"</svg>",
        encoding="utf-8",
    )


def run(magick: str, *args: str) -> None:
    result = subprocess.run(
        [magick, *args],
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        sys.exit(f"failed: magick {' '.join(args)}\n{result.stderr.strip()}")


def render_master(magick: str, artwork: Path, scratch: Path) -> Path:
    master = scratch / "master.png"
    run(
        magick,
        "-background", "none",
        "-density", str(RENDER_DENSITY),
        str(artwork),
        "-resize", f"{MASTER_SIZE}x{MASTER_SIZE}",
        # 8-bit output: the delegate renders 16-bit, which triples the file size
        # for no visible gain on a two-colour drawing.
        "-depth", "8",
        # PNG32 rather than plain .png: the drawing only has two colours, so
        # ImageMagick would otherwise write a palettised PNG, and a palette
        # carries no partial alpha -- every anti-aliased edge pixel would be
        # flattened to either fully opaque or fully transparent. That is what
        # turned the small .ico entries into jagged, hard-edged shapes.
        f"PNG32:{master}",
    )
    return master


def build_size_chain(magick: str, master: Path, scratch: Path) -> dict[int, Path]:
    """Produces every size, each from the size above it.

    Going straight from 1024 to 16 in one step is what turns a small icon to
    mush; halving repeatedly keeps the circle's edge and the note's stem
    readable at the sizes that actually matter.
    """
    wanted = sorted(set(ICO_SIZES) | {WINDOW_ICON_SIZE, LINUX_ICON_SIZE}, reverse=True)

    produced: dict[int, Path] = {}
    previous = master
    for size in wanted:
        target = scratch / f"icon-{size}.png"
        run(
            magick, str(previous),
            "-filter", "Lanczos",
            "-resize", f"{size}x{size}",
            "-depth", "8",
            # True-colour output, for the reason given in render_master: a
            # palettised PNG keeps no partial alpha, and the .ico entries built
            # from it would come out without an alpha channel.
            f"PNG32:{target}",
        )
        produced[size] = target
        previous = target
    return produced


def main() -> None:
    if not SOURCE_SVG.is_file():
        sys.exit(f"missing source artwork: {SOURCE_SVG}")

    magick = find_magick()
    scratch = REPO_ROOT / ".workbuddy" / "cache" / "icon"
    scratch.mkdir(parents=True, exist_ok=True)

    artwork = scratch / "app-icon-inverted.svg"
    build_inverted_svg(SOURCE_SVG, artwork)
    print(
        f"artwork      : {SOURCE_SVG.relative_to(REPO_ROOT)} "
        f"(inverted to disc {DISC_COLOUR} / note {NOTE_COLOUR})"
    )

    master = render_master(magick, artwork, scratch)
    sizes = build_size_chain(magick, master, scratch)

    WINDOW_ICON.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(sizes[WINDOW_ICON_SIZE], WINDOW_ICON)
    print(f"window icon  : {WINDOW_ICON.relative_to(REPO_ROOT)}  ({WINDOW_ICON_SIZE}px)")

    LINUX_ICON.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(sizes[LINUX_ICON_SIZE], LINUX_ICON)
    print(f"linux icon   : {LINUX_ICON.relative_to(REPO_ROOT)}  ({LINUX_ICON_SIZE}px)")

    WINDOWS_ICON.parent.mkdir(parents=True, exist_ok=True)
    # Ascending order: the convention for .ico entries, and the order Explorer's
    # icon picker lists them in.
    #
    # TrueColorAlpha is forced rather than left to the writer: an .ico entry
    # below 256px may be stored either as a 32-bit bitmap with an alpha channel
    # or as a palettised one whose transparency is a 1-bit mask. Only the first
    # renders with smooth edges. The mask form has no partial alpha at all, so
    # the circle's anti-aliased rim is quantised into hard steps -- and when
    # Windows scales such an icon, the masked-out pixels count as black, which
    # is what puts a dark ragged outline around a light icon.
    run(
        magick,
        *[str(sizes[size]) for size in sorted(ICO_SIZES)],
        "-alpha", "on",
        "-type", "TrueColorAlpha",
        str(WINDOWS_ICON),
    )
    print(
        f"windows icon : {WINDOWS_ICON.relative_to(REPO_ROOT)}  "
        f"({', '.join(str(size) for size in sorted(ICO_SIZES))}, all 32-bit with alpha)"
    )


if __name__ == "__main__":
    main()

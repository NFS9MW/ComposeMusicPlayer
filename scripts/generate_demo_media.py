#!/usr/bin/env python3
"""Generate a small tagged music library for trying the player out.

Produces real files with real ID3/FLAC/MP4 tags and embedded cover art, plus a
matching `library.json`, so the app can be started against a populated library
without clicking through a file dialog.

The point of the tag data is to exercise the parts of the UI that are hard to
eyeball otherwise: pinyin ordering of Chinese titles mixed with Latin ones,
missing album art, unknown artists, and a missing duration.

Usage:
    python scripts/generate_demo_media.py [output_dir]

Defaults to `.workbuddy/cache/demo/library`, and writes the config that goes
with it into `.workbuddy/cache/demo/config`, which is where the app should be
pointed via -Dcmp.config=.
"""

from __future__ import annotations

import json
import shutil
import subprocess
import sys
import wave
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
STAGED_FFMPEG = REPO_ROOT / "third_party" / "ffmpeg" / "windows-x64" / "ffmpeg.exe"

# title, artist, album, seconds, cover colour, extension
TRACKS = [
    ("稻香", "周杰伦", "魔杰座", 223, "1F6F5C", "mp3"),
    ("夜曲", "周杰伦", "十一月的萧邦", 256, "2E4A8B", "mp3"),
    ("晴天", "周杰伦", "叶惠美", 269, "8B5E2E", "flac"),
    ("星晴", "周杰伦", "范特西", 251, "6B2E8B", "m4a"),
    ("七里香", "周杰伦", "七里香", 299, "8B2E4A", "mp3"),
    ("Blue in Green", "Miles Davis", "Kind of Blue", 327, "24505A", "flac"),
    ("Amber Light", "Nova", "Single", 214, "B0762A", "m4a"),
    (    "The Anchor", "Harbour", "Tide", 184, "3A3F4B", "wav"),
]

FILLER_ARTISTS = ["Aurora Field", "Sable", "Kite Ensemble", "Mono Lake", "Vacant Hours"]


def ffmpeg_path() -> str:
    if STAGED_FFMPEG.exists():
        return str(STAGED_FFMPEG)
    found = shutil.which("ffmpeg")
    if not found:
        raise SystemExit("ffmpeg not found; run scripts/fetch_ffmpeg.py first")
    return found


def run(command: list[str]) -> None:
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode != 0:
        raise SystemExit(f"failed: {' '.join(command)}\n{result.stderr}")


def make_cover(ffmpeg: str, colour: str, target: Path, label: str) -> None:
    run([
        ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
        "-f", "lavfi", "-i", f"color=c=0x{colour}:s=600x600",
        # A band of a second colour so the thumbnail is not a flat square;
        # makes it obvious at a glance whether art loaded and from which file.
        "-vf", f"drawbox=x=0:y=420:w=600:h=180:color=0x111111@0.35:t=fill,"
               f"drawtext=text='{label}':fontcolor=white:fontsize=56:x=40:y=460",
        "-frames:v", "1", str(target),
    ])


def encode(ffmpeg: str, track: tuple, media_dir: Path, cover: Path | None) -> Path:
    title, artist, album, seconds, _, extension = track
    target = media_dir / f"{artist} - {title}.{extension}"

    codec = {
        "mp3": ["-c:a", "libmp3lame", "-b:a", "192k"],
        "flac": ["-c:a", "flac"],
        "m4a": ["-c:a", "aac", "-b:a", "192k"],
        "wav": ["-c:a", "pcm_s16le"],
    }[extension]

    # Only the compressed containers carry cover art; a WAV cannot, and filler
    # tracks are generated without any so the placeholder path gets exercised.
    with_cover = cover is not None and extension in {"mp3", "flac", "m4a"}

    command = [
        ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
        "-f", "lavfi", "-i", f"sine=frequency=440:sample_rate=44100:duration={seconds}",
    ]
    if with_cover:
        # The MP4/IPod muxer rejects a copied PNG stream, so m4a covers are
        # re-encoded to JPEG while ID3 and FLAC keep the PNG bytes as-is.
        cover_codec = "mjpeg" if extension == "m4a" else "copy"
        command += [
            "-i", str(cover),
            "-map", "0:a", "-map", "1:v",
            "-disposition:v", "attached_pic",
            "-c:v", cover_codec,
        ]

    command += ["-map_metadata", "-1"]
    command += codec
    command += [
        "-metadata", f"title={title}",
        "-metadata", f"artist={artist}",
        "-metadata", f"album={album}",
    ]
    if with_cover:
        command += [
            "-metadata:s:v", "title=Album cover",
            "-metadata:s:v", "comment=Cover (front)",
        ]
    if with_cover and extension == "mp3":
        command += ["-id3v2_version", "3"]
    command += [str(target)]
    run(command)
    return target


def duration_ms(path: Path) -> int:
    if path.suffix == ".wav":
        with wave.open(str(path), "rb") as handle:
            return int(handle.getnframes() / handle.getframerate() * 1000)
    return 0


def main() -> None:
    args = sys.argv[1:]

    # `--filler N` appends N short tracks. Eight songs do not fill a window, so
    # without this there is no way to look at how the list behaves when it
    # actually scrolls.
    filler = 0
    if "--filler" in args:
        index = args.index("--filler")
        filler = int(args[index + 1])
        del args[index : index + 2]

    root = Path(args[0]).resolve() if args else (
        REPO_ROOT / ".workbuddy" / "cache" / "demo" / "library"
    )
    media_dir = root / "media"
    config_dir = root.parent / "config"

    media_dir.mkdir(parents=True, exist_ok=True)
    config_dir.mkdir(parents=True, exist_ok=True)

    ffmpeg = ffmpeg_path()
    print(f"ffmpeg: {ffmpeg}")
    print(f"media:  {media_dir}")

    songs = []
    for index, track in enumerate(TRACKS):
        title, artist, album, seconds, colour, _ = track
        cover = media_dir / f".cover-{index}.png"
        make_cover(ffmpeg, colour, cover, album[:12])
        path = encode(ffmpeg, track, media_dir, cover)
        cover.unlink(missing_ok=True)

        songs.append({
            "id": str(path).replace("\\", "/"),
            "path": str(path).replace("\\", "/"),
            "title": title,
            "artist": artist,
            "album": album,
            "durationMs": duration_ms(path) or seconds * 1000,
            "trackNumber": index + 1,
        })
        print(f"  + {title} — {artist} ({album})")

    for index in range(filler):
        number = index + 1
        title = f"Filler {number:02d}"
        artist = FILLER_ARTISTS[index % len(FILLER_ARTISTS)]
        album = f"Padding {index // 10 + 1}"
        track = (title, artist, album, 12, "444444", "mp3")
        path = encode(ffmpeg, track, media_dir, cover=None)

        songs.append({
            "id": str(path).replace("\\", "/"),
            "path": str(path).replace("\\", "/"),
            "title": title,
            "artist": artist,
            "album": album,
            "durationMs": 12_000,
            "trackNumber": number,
        })

    if filler:
        print(f"  + {filler} filler tracks")

    library = {
        "playlists": [{
            "id": "pl-demo",
            "name": "演示歌单",
            "songs": songs,
            "createdAt": 0,
        }],
        "selectedPlaylistId": "pl-demo",
    }
    (config_dir / "library.json").write_text(
        json.dumps(library, ensure_ascii=False, indent=2), encoding="utf-8",
    )
    (config_dir / "settings.json").write_text(
        json.dumps({
            "darkTheme": True,
            "volume": 0.8,
            "sortDirection": "Ascending",
            "shuffle": False,
            "loop": "Off",
            "lastImportDirectory": str(media_dir),
            "queuePanelExpanded": True,
        }, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    print(f"\nconfig: {config_dir}")
    # Forward slashes and the `cmp.config` property name: Gradle's own -D only
    # reaches the Gradle JVM, and the build passes `cmp.config` through to the
    # application explicitly.
    print(
        "run with: ./gradlew :composeApp:run "
        f'-Dcmp.config="{str(config_dir).replace(chr(92), "/")}"'
    )


if __name__ == "__main__":
    main()

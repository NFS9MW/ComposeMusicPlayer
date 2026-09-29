#!/usr/bin/env python3
"""Build a cut-down, audio-only FFmpeg and stage it for bundling.

Why this exists
---------------
`fetch_ffmpeg.py` stages BtbN's general-purpose build, which contains *every*
codec FFmpeg ships -- including all the video ones. In that build
`avcodec-62.dll` alone is 91 MB out of a 160 MB runtime, and the app is a music
player: it never decodes a video frame. Building FFmpeg with everything switched
off and only the audio path switched back on takes each platform to about a
megabyte.

What is kept
------------
Exactly what the app asks FFmpeg to do, and nothing else:

  * **Decoding** to raw PCM (`-f s16le`) -- the demuxers, decoders and parsers
    for the formats listed in the README, plus resampling for `-ar` / `-ac`;
  * **Probing** a file's tags, duration and streams (`ffprobe`);
  * **Extracting embedded cover art**, which goes through the `image2pipe`
    muxer with `-c:v copy` -- so the image codecs are needed only as parsers,
    never as encoders.

Everything else -- video codecs, encoders, filters, devices, network protocols,
programs other than `ffmpeg`/`ffprobe` -- is off.

Static, not shared
------------------
Each platform gets two self-contained executables, and that choice is doing
real work rather than saving a few bytes. A shared build has to have its
`libav*`/`av*` libraries found at run time by a loader that knows nothing about
this app; getting that wrong does not fail loudly, it fails on the user's
machine with a decoder that will not start. Static binaries cannot have that
class of problem. They are also *smaller* here, because an audio-only codec set
is only a few hundred kilobytes -- less than the library-searching machinery the
alternative needs.

The LGPL obligation that comes with static linking is met the way the license
asks for: the build is reproducible from this script, and both it and the source
of the application it is linked into ship in this same repository.

Prerequisites (Fedora)
----------------------
    # Linux build
    sudo dnf install gcc make nasm pkgconf-pkg-config zlib-devel tar xz

    # Windows build as well, cross-compiled
    sudo dnf install mingw64-gcc mingw64-zlib

Usage
-----
    python scripts/build_minimal_ffmpeg.py                 # for this machine
    python scripts/build_minimal_ffmpeg.py linux-x64
    python scripts/build_minimal_ffmpeg.py windows-x64
    python scripts/build_minimal_ffmpeg.py all

The result lands in `third_party/ffmpeg/<platform>/`, exactly where
`fetch_ffmpeg.py` puts its copy, so nothing else in the build has to know which
of the two produced it. Deciding which one to ship is the caller's job; this
script only ever replaces the platform it was asked to build.
"""

from __future__ import annotations

import argparse
import os
import platform
import re
import shutil
import subprocess
import sys
import tarfile
import urllib.request
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
STAGE_ROOT = REPO_ROOT / "third_party" / "ffmpeg"
CACHE_DIR = REPO_ROOT / ".workbuddy" / "cache" / "ffmpeg"

# Same upstream revision the staged BtbN build came from, so switching between
# the two changes which codecs are on offer and nothing else.
FFMPEG_VERSION = os.environ.get("FFMPEG_VERSION", "8.1.3")
SOURCE_URL = f"https://ffmpeg.org/releases/ffmpeg-{FFMPEG_VERSION}.tar.xz"

PLATFORMS = ("linux-x64", "windows-x64")

# What the player can be pointed at, by name. Kept as one list per kind so the
# configure line reads like a statement of what the app supports.
DECODERS = [
    # compressed audio
    "aac", "aac_latm", "ac3", "alac", "ape", "dca", "eac3", "flac", "mlp",
    "mp3", "mp3float", "opus", "truehd", "tta", "vorbis", "wavpack",
    "wmav1", "wmav2", "wmalossless", "wmapro",
    # PCM, in every width and endianness a WAV or AIFF can hold
    "pcm_alaw", "pcm_mulaw", "pcm_s8", "pcm_u8",
    "pcm_s16be", "pcm_s16le", "pcm_s24be", "pcm_s24le",
    "pcm_s32be", "pcm_s32le", "pcm_f32be", "pcm_f32le", "pcm_f64be", "pcm_f64le",
    # ADPCM, which turns up inside AIFF and WAV more often than one would like
    "adpcm_ima_qt", "adpcm_ima_wav", "adpcm_ms",
    # ...and the image codecs an embedded cover can be in. They are never asked
    # to encode -- cover art is copied through -- but the stream has to be
    # understood well enough to hand back its bytes.
    "mjpeg", "png", "bmp", "gif", "webp",
]

DEMUXERS = [
    "aac", "ac3", "aiff", "amr", "ape", "asf", "au", "caf", "dts", "eac3",
    "flac", "matroska", "mov", "mp3", "ogg", "tta", "wav", "wv", "w64",
]

PARSERS = [
    "aac", "aac_latm", "ac3", "dca", "flac", "mpegaudio", "opus", "vorbis",
    "mjpeg", "png",
]

ENCODERS = ["pcm_s16le"]
# "s16le" is the name the CLI takes for -f; the configure component -- and so
# the name to switch on here -- is the codec-qualified pcm_s16le.
MUXERS = ["pcm_s16le", "image2pipe"]
BSFS = ["aac_adtstoasc", "extract_extradata"]

# platform key -> extra configure arguments and the executable suffix produced
TARGETS: dict[str, dict[str, object]] = {
    "linux-x64": {
        "cross": [],
        "exe_suffix": "",
        "runnable": True,
    },
    "windows-x64": {
        "cross": [
            "--enable-cross-compile",
            "--target-os=mingw32",
            "--arch=x86_64",
            "--cross-prefix=x86_64-w64-mingw32-",
        ],
        "exe_suffix": ".exe",
        "runnable": False,
    },
}


def log(message: str) -> None:
    print(f"[build-minimal-ffmpeg] {message}", flush=True)


def die(message: str) -> None:
    print(f"[build-minimal-ffmpeg] error: {message}", file=sys.stderr, flush=True)
    sys.exit(1)


def which(name: str) -> str | None:
    return shutil.which(name)


def host_platform() -> str:
    if platform.system().lower() == "linux":
        return "linux-x64"
    die(f"this machine is {platform.system()}, which nothing here targets")
    return ""


def run(command: list[str], cwd: Path | None = None) -> None:
    log("$ " + " ".join(command))
    subprocess.run(command, cwd=cwd, check=True)


def cross_prefix(platform_key: str) -> str:
    for flag in TARGETS[platform_key]["cross"]:
        text = str(flag)
        if text.startswith("--cross-prefix="):
            return text.split("=", 1)[1]
    return ""


def check_toolchain(platform_key: str) -> None:
    missing: list[str] = []
    for tool in ("make", "tar"):
        if not which(tool):
            missing.append(tool)

    if platform_key == "linux-x64":
        if not (which("gcc") or which("cc")):
            missing.append("gcc")
    else:
        compiler = f"{cross_prefix(platform_key)}gcc"
        if not which(compiler):
            missing.append(f"{compiler} (mingw64-gcc)")

    if missing:
        packages = "gcc make nasm pkgconf-pkg-config zlib-devel tar xz"
        if platform_key == "windows-x64":
            packages += " mingw64-gcc mingw64-zlib"
        die(f"missing build tools: {', '.join(missing)}\n  Fedora: sudo dnf install {packages}")


def fetch_source() -> Path:
    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    archive = CACHE_DIR / f"ffmpeg-{FFMPEG_VERSION}.tar.xz"
    if archive.is_file():
        log(f"source already cached: {archive.name}")
        return archive

    log(f"downloading ffmpeg {FFMPEG_VERSION}")
    with urllib.request.urlopen(SOURCE_URL) as response, archive.open("wb") as handle:
        shutil.copyfileobj(response, handle)
    return archive


def configure_flags(platform_key: str) -> list[str]:
    flags = [
        # Nothing on unless asked for. This one flag is where almost all of the
        # size saving comes from.
        "--disable-everything",
        "--enable-small",
        "--disable-autodetect",
        "--disable-doc",
        "--disable-debug",
        "--disable-network",

        # Self-contained, for the reasons in the module docstring.
        "--enable-static",
        "--disable-shared",

        # Just the two programs the app launches.
        "--enable-ffmpeg",
        "--enable-ffprobe",
        "--disable-ffplay",

        # Video is not merely unused here, it is most of the bytes, so scaling
        # and capture devices go. No --disable-postproc: upstream removed
        # libpostproc in 8.0, and passing the now-unknown option fails
        # configure outright.
        "--disable-swscale",
        "--disable-avdevice",

        # libavfilter cannot be switched off, though the app never names a
        # filter: the ffmpeg program declares it as a dependency
        # (ffmpeg_deps="avcodec avfilter avformat threads") and configure drops
        # the program silently when it is missing -- which stages an ffprobe
        # with no ffmpeg beside it. Every filter *can* be off, though; these
        # three are the ones the CLI inserts by itself for this app's command
        # line: "anull" is the default audio filter the mux layer fills in when
        # -af is absent, "aformat" pins the encoder's sample format, rate and
        # channel layout, and "aresample" is what libavfilter auto-inserts when
        # the file's rate or layout differs from the requested -ar/-ac.
        "--enable-avfilter",
        "--enable-filter=anull,aformat,aresample",

        # Compression, for the PNG covers the decoder list allows for.
        "--enable-zlib",

        # Local files in, a pipe out.
        "--enable-protocol=file,pipe",

        f"--enable-decoder={','.join(DECODERS)}",
        f"--enable-demuxer={','.join(DEMUXERS)}",
        f"--enable-parser={','.join(PARSERS)}",
        f"--enable-encoder={','.join(ENCODERS)}",
        f"--enable-muxer={','.join(MUXERS)}",
        f"--enable-bsf={','.join(BSFS)}",
    ]
    flags.extend(str(flag) for flag in TARGETS[platform_key]["cross"])

    # The hand-written x86 assembly needs an assembler. Without one the build
    # still works, just slower -- and the decoder runs hundreds of times faster
    # than realtime, so this is a warning rather than a hard requirement.
    if not (which("nasm") or which("yasm")):
        log("no nasm/yasm found: building without hand-written assembly")
        flags.append("--disable-x86asm")

    return flags


def build(platform_key: str, jobs: int) -> Path:
    archive = fetch_source()
    work = CACHE_DIR / f"build-{platform_key}-{FFMPEG_VERSION}"
    source = work / f"ffmpeg-{FFMPEG_VERSION}"
    prefix = work / "install"

    if work.exists():
        log(f"clearing previous build tree: {work}")
        shutil.rmtree(work)
    work.mkdir(parents=True)

    log(f"extracting {archive.name}")
    with tarfile.open(archive) as tar:
        # 3.12 deprecated unfiltered extraction; `data` is the safe default and
        # older interpreters do not take the argument at all.
        try:
            tar.extractall(work, filter="data")
        except TypeError:
            tar.extractall(work)

    run([str(source / "configure"), f"--prefix={prefix}", *configure_flags(platform_key)], cwd=source)
    run(["make", f"-j{jobs}"], cwd=source)
    run(["make", "install"], cwd=source)
    return prefix


def collect(platform_key: str, prefix: Path) -> Path:
    target = STAGE_ROOT / platform_key
    if target.exists():
        shutil.rmtree(target)
    target.mkdir(parents=True)

    suffix = str(TARGETS[platform_key]["exe_suffix"])
    for name in ("ffmpeg", "ffprobe"):
        produced = prefix / "bin" / f"{name}{suffix}"
        if not produced.is_file():
            die(f"{name}{suffix} was not produced -- the configure line is wrong")
        shutil.copy2(produced, target / produced.name)

    # The LGPL text, which is the license a build with no --enable-gpl code is
    # under. Deliberately not COPYING.GPLv2.
    source_root = prefix.parent / f"ffmpeg-{FFMPEG_VERSION}"
    for name in ("COPYING.LGPLv2.1", "LICENSE.md", "LICENSE"):
        candidate = source_root / name
        if candidate.is_file():
            shutil.copy2(candidate, target / "LICENSE")
            break
    else:
        log("warning: no license file found in the source tree to stage")

    log(f"staged into {target.relative_to(REPO_ROOT)}")
    return target


def verify_self_contained(target: Path, suffix: str) -> None:
    """Confirms the two executables really do carry everything they need.

    This is the check that catches a shared build staged without its libraries:
    the launchers look entirely healthy on their own, and the failure shows up
    only on the machine that installed them.
    """
    pattern = (
        re.compile(rb"av[a-z]+-\d+\.dll|sw[a-z]+-\d+\.dll")
        if suffix
        else re.compile(rb"lib(?:av|sw)[a-z]*\.so[.\d]*")
    )
    for name in ("ffmpeg", "ffprobe"):
        referenced = sorted({hit.decode() for hit in pattern.findall((target / f"{name}{suffix}").read_bytes())})
        if referenced:
            die(
                f"{name}{suffix} still needs {', '.join(referenced)} at run time, "
                "so this build is not self-contained"
            )


def verify(platform_key: str, target: Path) -> None:
    suffix = str(TARGETS[platform_key]["exe_suffix"])

    if not TARGETS[platform_key]["runnable"]:
        # Cross-built Windows binaries cannot be executed here, so size and the
        # absence of any external library reference is as far as this can go.
        for name in ("ffmpeg", "ffprobe"):
            size = (target / f"{name}{suffix}").stat().st_size
            if size < 100_000:
                die(f"{name}{suffix} is only {size} bytes -- it did not link properly")
        verify_self_contained(target, suffix)
        log("cannot execute Windows binaries here; checked what could be checked")
        return

    verify_self_contained(target, suffix)
    for name in ("ffmpeg", "ffprobe"):
        result = subprocess.run(
            [str(target / f"{name}{suffix}"), "-version"],
            capture_output=True,
            text=True,
            check=False,
        )
        if result.returncode != 0:
            die(f"{name} -version failed with {result.returncode}:\n{result.stderr}")
        log((result.stdout.splitlines() or ["?"])[0])


def report(target: Path) -> None:
    files = [item for item in target.iterdir() if item.is_file()]
    total = sum(item.stat().st_size for item in files)
    log(f"{target.relative_to(REPO_ROOT)}: {total / (1024 * 1024):.2f} MB across {len(files)} files")
    for item in sorted(files, key=lambda p: -p.stat().st_size):
        log(f"    {item.name:<24} {item.stat().st_size / (1024 * 1024):>7.2f} MB")


def main() -> None:
    parser = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument(
        "platform",
        nargs="?",
        default="host",
        choices=["host", "all", *PLATFORMS],
        help="which platform to build for (default: this machine)",
    )
    parser.add_argument("--jobs", type=int, default=os.cpu_count() or 4, help="parallel make jobs")
    parser.add_argument("--force", action="store_true", help="rebuild even if already staged")
    args = parser.parse_args()

    if args.platform == "all":
        wanted = list(PLATFORMS)
    elif args.platform == "host":
        wanted = [host_platform()]
    else:
        wanted = [args.platform]

    log(f"ffmpeg {FFMPEG_VERSION}, building for: {', '.join(wanted)}")

    for item in wanted:
        suffix = str(TARGETS[item]["exe_suffix"])
        target = STAGE_ROOT / item
        staged = all((target / f"{name}{suffix}").is_file() for name in ("ffmpeg", "ffprobe"))
        if staged and not args.force:
            log(f"{item} already staged; pass --force to rebuild")
            continue

        check_toolchain(item)
        prefix = build(item, args.jobs)
        built = collect(item, prefix)
        verify(item, built)
        report(built)

    log("done")


if __name__ == "__main__":
    main()

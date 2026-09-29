#!/usr/bin/env python3
"""Stage the bundled FFmpeg binaries used by ComposeMusicPlayer.

FFmpeg is only used as a *decoder* invoked as a subprocess, so we ship the
LGPL **shared** build: it is smaller than the static one (ffmpeg and ffprobe
reuse the same av*/sw* libraries) and, because the libraries stay dynamically
linked, it keeps us clear of LGPL's static-linking relink obligation.

Only the runtime pieces are staged (bin/ plus the license files); headers,
import libraries and docs are dropped to keep the installer small.

Usage:
    python scripts/fetch_ffmpeg.py                  # auto-detect host platform
    python scripts/fetch_ffmpeg.py windows-x64
    python scripts/fetch_ffmpeg.py linux-x64
    python scripts/fetch_ffmpeg.py all              # stage every supported platform

Idempotent: an already-staged platform is skipped unless --force is given.
"""

from __future__ import annotations

import argparse
import os
import shutil
import sys
import tarfile
import tempfile
import time
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
STAGE_ROOT = REPO_ROOT / "third_party" / "ffmpeg"
CACHE_DIR = REPO_ROOT / ".workbuddy" / "cache" / "ffmpeg"

# BtbN publishes one mutable `latest` release plus immutable dated
# `autobuild-<date>` tags. Pin the dated tag so a rebuild months from now
# stages byte-identical binaries.
#
# The asset names below belong to RELEASE_TAG, and they embed the upstream
# revision it was cut from (`n8.1.3-2-g45e8e0a3ff`). The rolling `latest`
# release is the one whose assets are named with a literal `latest`; asking a
# dated tag for those names is a 404. Bumping RELEASE_TAG therefore means
# updating these names in the same commit -- list the candidates with:
#   curl -s https://api.github.com/repos/BtbN/FFmpeg-Builds/releases/tags/<tag> \
#     | grep -o '"name": "[^"]*linux64-lgpl-shared[^"]*"'
RELEASE_TAG = os.environ.get("FFMPEG_RELEASE_TAG", "autobuild-2026-09-27-13-04")

RELEASE_BASE = f"https://github.com/BtbN/FFmpeg-Builds/releases/download/{RELEASE_TAG}"

# platform key -> (asset filename, archive kind, runtime file matcher)
PLATFORMS: dict[str, tuple[str, str, str]] = {
    "windows-x64": (
        "ffmpeg-n8.1.3-2-g45e8e0a3ff-win64-lgpl-shared-8.1.zip",
        "zip",
        "win64",
    ),
    "linux-x64": (
        "ffmpeg-n8.1.3-2-g45e8e0a3ff-linux64-lgpl-shared-8.1.tar.xz",
        "tarxz",
        "linux64",
    ),
}

# Files worth shipping from the archive's bin/ directory.
WANTED_EXECUTABLES = ("ffmpeg", "ffprobe")
LICENSE_HINTS = ("LICENSE", "COPYING", "README")


def log(msg: str) -> None:
    print(f"[fetch-ffmpeg] {msg}", flush=True)


def host_platform() -> str:
    if sys.platform.startswith("win"):
        return "windows-x64"
    if sys.platform.startswith("linux"):
        return "linux-x64"
    raise SystemExit(f"unsupported host platform: {sys.platform}")


def download(url: str, dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    part = dest.with_suffix(dest.suffix + ".part")

    # GitHub hands the payload to objects.githubusercontent.com via a redirect.
    # Some networks stall on the first hop, so retry the whole request.
    last_error: Exception | None = None
    for attempt in range(1, 6):
        try:
            log(f"downloading {url} (attempt {attempt})")
            request = urllib.request.Request(
                url, headers={"User-Agent": "ComposeMusicPlayer-build"}
            )
            with urllib.request.urlopen(request, timeout=120) as response:
                total = int(response.headers.get("Content-Length") or 0)
                received = 0
                with open(part, "wb") as handle:
                    while True:
                        chunk = response.read(1 << 20)
                        if not chunk:
                            break
                        handle.write(chunk)
                        received += len(chunk)
                        if total:
                            pct = received * 100 // total
                            print(f"\r  {pct:3d}%  {received/1048576:6.1f} MiB", end="")
                print()
            if received == 0:
                raise OSError("server returned an empty body")
            if total and received != total:
                raise OSError(f"truncated download: {received} of {total} bytes")
            part.replace(dest)
            log(f"cached {dest.name} ({received/1048576:.1f} MiB)")
            return
        except (urllib.error.URLError, OSError, TimeoutError) as exc:
            last_error = exc
            part.unlink(missing_ok=True)
            if attempt < 5:
                time.sleep(2 * attempt)
    raise SystemExit(f"failed to download {url}: {last_error}")


def is_runtime_file(name: str) -> bool:
    base = os.path.basename(name)
    lower = base.lower()
    if lower.endswith((".dll", ".so", ".so.0")) or ".so." in lower:
        return True
    if lower.endswith(".exe") and os.path.splitext(lower)[0] in WANTED_EXECUTABLES:
        return True
    if not lower.endswith(".exe") and os.path.splitext(lower)[0] in WANTED_EXECUTABLES:
        return True
    return False


def stage(platform: str, archive: Path, kind: str, force: bool) -> None:
    target = STAGE_ROOT / platform
    if target.exists() and not force:
        staged = sorted(p.name for p in target.iterdir())
        if staged:
            log(f"{platform}: already staged ({len(staged)} files), skipping")
            return
    if target.exists():
        shutil.rmtree(target)
    target.mkdir(parents=True, exist_ok=True)

    log(f"{platform}: extracting runtime files -> {target.relative_to(REPO_ROOT)}")
    runtime = 0
    licenses: list[str] = []

    if kind == "zip":
        with zipfile.ZipFile(archive) as zf:
            for info in zf.infolist():
                if info.is_dir():
                    continue
                name = info.filename
                # Guard against path traversal in the archive.
                if os.path.isabs(name) or ".." in Path(name).parts:
                    continue
                base = os.path.basename(name)
                parent = os.path.basename(os.path.dirname(name))
                if parent == "bin" and is_runtime_file(name):
                    with zf.open(info) as src, open(target / base, "wb") as dst:
                        shutil.copyfileobj(src, dst, 1 << 20)
                    runtime += 1
                elif any(base.upper().startswith(h) for h in LICENSE_HINTS):
                    licenses.append(name)
                    with zf.open(info) as src, open(target / base, "wb") as dst:
                        shutil.copyfileobj(src, dst, 1 << 20)
    else:
        with tarfile.open(archive, "r:xz") as tf:
            for member in tf.getmembers():
                if not member.isfile():
                    continue
                name = member.name
                if os.path.isabs(name) or ".." in Path(name).parts:
                    continue
                base = os.path.basename(name)
                parent = os.path.basename(os.path.dirname(name))
                if parent == "bin" and is_runtime_file(name):
                    src = tf.extractfile(member)
                    if src is None:
                        continue
                    with src, open(target / base, "wb") as dst:
                        shutil.copyfileobj(src, dst, 1 << 20)
                    runtime += 1
                elif any(base.upper().startswith(h) for h in LICENSE_HINTS):
                    src = tf.extractfile(member)
                    if src is None:
                        continue
                    with src, open(target / f"LICENSE-{base}", "wb") as dst:
                        shutil.copyfileobj(src, dst, 1 << 20)
                    licenses.append(base)

    if runtime == 0:
        raise SystemExit(f"{platform}: no runtime files found in {archive.name}")

    size = sum(p.stat().st_size for p in target.iterdir())
    log(f"{platform}: {runtime} runtime files, {size/1048576:.1f} MiB on disk")
    if licenses:
        log(f"{platform}: bundled license files -> {', '.join(sorted(set(licenses)))}")
    else:
        log(f"{platform}: WARNING no license file found; add one manually")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "platform",
        nargs="?",
        default="auto",
        choices=["auto", "all", *PLATFORMS],
        help="which build to stage (default: host platform)",
    )
    parser.add_argument("--force", action="store_true", help="re-stage even if present")
    parser.add_argument(
        "--keep-archive", action="store_true", help="keep the downloaded archive"
    )
    args = parser.parse_args()

    if args.platform == "auto":
        targets = [host_platform()]
    elif args.platform == "all":
        targets = list(PLATFORMS)
    else:
        targets = [args.platform]

    for platform in targets:
        filename, kind, _ = PLATFORMS[platform]
        archive = CACHE_DIR / filename
        if not archive.exists() or archive.stat().st_size == 0:
            archive.unlink(missing_ok=True)
            download(f"{RELEASE_BASE}/{filename}", archive)
        else:
            log(f"reusing cached {filename} ({archive.stat().st_size/1048576:.1f} MiB)")
        stage(platform, archive, kind, args.force)
        if not args.keep_archive:
            archive.unlink(missing_ok=True)

    log(f"release tag: {RELEASE_TAG}")
    log("done")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Fetch the licensed brand fonts design/brand-fonts.lock.json pins into its git-ignored directory."""

from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import re
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, NoReturn


REPO_ROOT = Path(__file__).resolve().parent.parent
LOCK_PATH = REPO_ROOT / "design" / "brand-fonts.lock.json"
BASE_URL = "https://static.put.io"
# The same rules as build-logic's BrandFonts, which also checks families and weights.
FILE_NAME = re.compile(r"(gt_america|berkeley_mono)_[a-z0-9_]*[a-z0-9]\.(ttf|otf)")
SOURCE_PATH = re.compile(r"fonts/[a-z0-9-]+(/[a-z0-9-]+)*/[a-z0-9-]+\.(ttf|otf)")
DIRECTORY = re.compile(r"[a-z0-9_-]+(/[a-z0-9_-]+)*")
SHA256 = re.compile(r"[0-9a-f]{64}")
FACE_KEYS = {"path", "sha256", "family", "weight", "weightRange"}
ATTEMPTS = 3


@dataclass(frozen=True)
class Font:
    name: str
    url: str
    sha256: str


@dataclass(frozen=True)
class Lock:
    directory: str
    fonts: tuple[Font, ...]


def fail(message: str) -> NoReturn:
    raise SystemExit(f"brand-fonts: {message}")


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def parse_lock(text: str) -> Lock:
    try:
        root = json.loads(text)
    except json.JSONDecodeError as error:
        fail(f"lock is not JSON: {error}")
    if not isinstance(root, dict) or set(root) != {"baseUrl", "directory", "files"}:
        fail("lock fields do not match the schema")
    if root["baseUrl"] != BASE_URL:
        fail(f"lock must fetch from {BASE_URL}")
    directory = root["directory"]
    if not isinstance(directory, str) or DIRECTORY.fullmatch(directory) is None:
        fail(f"lock directory must be a plain relative path: {directory!r}")
    files = root["files"]
    if not isinstance(files, dict) or not files:
        fail("lock files must be a non-empty object")
    fonts = []
    for name, entry in files.items():
        if FILE_NAME.fullmatch(name) is None:
            fail(f"{name!r} must be an Android font resource name")
        if not isinstance(entry, dict) or not {"path", "sha256"} <= set(entry) <= FACE_KEYS:
            fail(f"{name} fields do not match the schema")
        path, digest = entry["path"], entry["sha256"]
        if not isinstance(path, str) or SOURCE_PATH.fullmatch(path) is None:
            fail(f"{name} path must stay under {BASE_URL}/fonts: {path!r}")
        if path.rsplit(".", 1)[1] != name.rsplit(".", 1)[1]:
            fail(f"{name} and its path differ in format")
        if not isinstance(digest, str) or SHA256.fullmatch(digest) is None:
            fail(f"{name} sha256 must be a lowercase SHA-256 digest")
        fonts.append(Font(name, f"{BASE_URL}/{path}", digest))
    return Lock(directory, tuple(fonts))


class _NoRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args: object, **kwargs: object) -> None:
        return None


def fetch(url: str) -> bytes:
    """GET without redirects, retrying connection failures and server errors a bounded number of times."""
    opener = urllib.request.build_opener(_NoRedirects)
    for attempt in range(1, ATTEMPTS + 1):
        try:
            with opener.open(url, timeout=30) as response:
                return response.read()
        except urllib.error.HTTPError as error:
            if error.code < 500 or attempt == ATTEMPTS:
                fail(f"{url} returned {error.code}")
        except OSError as error:
            if attempt == ATTEMPTS:
                fail(f"{url} failed: {error}")
        time.sleep(2**attempt)
    raise AssertionError("unreachable")


def sync(lock: Lock, root: Path, download: Callable[[str], bytes] = fetch) -> None:
    directory = root / lock.directory
    directory.mkdir(parents=True, exist_ok=True)
    for font in lock.fonts:
        destination = directory / font.name
        if destination.is_file() and hmac.compare_digest(sha256(destination.read_bytes()), font.sha256):
            continue
        data = download(font.url)
        if not hmac.compare_digest(sha256(data), font.sha256):
            fail(f"{font.url} is {sha256(data)}, locked {font.sha256}; {font.name} not written")
        temporary = destination.with_name(f".{font.name}.download")
        temporary.write_bytes(data)
        temporary.replace(destination)
        print(f"downloaded {font.name}")
    print(f"{len(lock.fonts)} brand fonts installed and verified in {lock.directory}")


def main() -> None:
    argparse.ArgumentParser(description=__doc__).parse_args()
    sync(parse_lock(LOCK_PATH.read_text(encoding="utf-8")), REPO_ROOT)


if __name__ == "__main__":
    main()

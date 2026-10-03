#!/usr/bin/env python3
"""Sync and check the repository's locked @putdotio/design assets."""

from __future__ import annotations

import argparse
import base64
import binascii
import hashlib
import hmac
import io
import json
import re
import shutil
import subprocess
import tarfile
import tempfile
import urllib.request
import xml.etree.ElementTree as ElementTree
from dataclasses import dataclass
from pathlib import Path
from typing import NoReturn
from xml.sax.saxutils import escape


REPO_ROOT = Path(__file__).resolve().parent.parent
LOCK_PATH = REPO_ROOT / "design" / "putio-design.lock.json"
TOKENS_PATH = Path("design") / "tokens.dtcg.json"
WORDMARK_PATH = Path("app/src/mobile/res/drawable/putio_wordmark.xml")
SVG_NAMESPACE = "{http://www.w3.org/2000/svg}"
SVG_FILLS = {"white": "#FFFFFFFF"}
HEX_COLOR_PATTERN = re.compile(r"#([0-9A-Fa-f]{6})")
NUMBER_PATTERN = re.compile(r"-?[0-9]+(?:\.[0-9]+)?")
NIGHTLY_ICON_DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
SHA256_PATTERN = re.compile(r"[0-9a-f]{64}")
VERSION_PATTERN = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+")


@dataclass(frozen=True)
class PackageLock:
    name: str
    version: str
    tarball: str
    integrity: str


@dataclass(frozen=True)
class AssetLock:
    asset: str
    source_sha256: str


@dataclass(frozen=True)
class IconOutputLock:
    density: str
    size: int
    sha256: str

    @property
    def path(self) -> Path:
        return Path("app/src/nightly/res") / f"drawable-{self.density}" / "putio_icon.png"


@dataclass(frozen=True)
class DesignLock:
    package: PackageLock
    tokens: AssetLock
    nightly_icon: AssetLock
    nightly_icon_outputs: tuple[IconOutputLock, ...]
    wordmark: AssetLock
    wordmark_output_sha256: str


def fail(message: str) -> NoReturn:
    raise SystemExit(f"design-assets: {message}")


def require_string(value: object, field: str) -> str:
    if not isinstance(value, str) or not value:
        fail(f"{field} must be a non-empty string")
    return value


def require_sha256(value: object, field: str) -> str:
    digest = require_string(value, field)
    if SHA256_PATTERN.fullmatch(digest) is None:
        fail(f"{field} must be a lowercase SHA-256 digest")
    return digest


def require_object(value: object, field: str, keys: set[str]) -> dict[str, object]:
    if not isinstance(value, dict) or set(value) != keys:
        fail(f"{field} fields do not match the schema")
    return value


def reject_duplicate_keys(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            fail(f"lock contains duplicate key {key!r}")
        result[key] = value
    return result


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def load_package(value: object) -> PackageLock:
    raw = require_object(value, "package", {"name", "version", "tarball", "integrity"})
    package = PackageLock(
        name=require_string(raw["name"], "package.name"),
        version=require_string(raw["version"], "package.version"),
        tarball=require_string(raw["tarball"], "package.tarball"),
        integrity=require_string(raw["integrity"], "package.integrity"),
    )
    if package.name != "@putdotio/design" or VERSION_PATTERN.fullmatch(package.version) is None:
        fail("package must be @putdotio/design at an exact x.y.z version")
    if package.tarball != (
        f"https://registry.npmjs.org/@putdotio/design/-/design-{package.version}.tgz"
    ):
        fail("package.tarball does not match the locked version")
    digest = package.integrity.removeprefix("sha512-")
    if digest == package.integrity:
        fail("package.integrity must use sha512 SRI")
    try:
        decoded = base64.b64decode(digest, validate=True)
    except (ValueError, binascii.Error):
        fail("package.integrity must contain valid base64")
    if len(decoded) != hashlib.sha512().digest_size:
        fail("package.integrity must contain a SHA-512 digest")
    return package


def load_asset(value: object, field: str, extra: frozenset[str] = frozenset()) -> tuple[AssetLock, dict[str, object]]:
    raw = require_object(value, field, {"asset", "sourceSha256"} | extra)
    asset = require_string(raw["asset"], f"{field}.asset")
    if asset.startswith("/") or ".." in Path(asset).parts:
        fail(f"{field}.asset must be a relative package path")
    return AssetLock(asset, require_sha256(raw["sourceSha256"], f"{field}.sourceSha256")), raw


def load_lock() -> DesignLock:
    try:
        document: object = json.loads(
            LOCK_PATH.read_text(encoding="utf-8"),
            object_pairs_hook=reject_duplicate_keys,
        )
    except (OSError, json.JSONDecodeError) as error:
        fail(f"cannot read {LOCK_PATH.relative_to(REPO_ROOT)}: {error}")

    root = require_object(document, "lock", {"schemaVersion", "package", "tokens", "nightlyIcon", "wordmark"})
    if type(root["schemaVersion"]) is not int or root["schemaVersion"] != 1:
        fail("lock must use schemaVersion 1")
    tokens, _ = load_asset(root["tokens"], "tokens")
    nightly_icon, raw_icon = load_asset(root["nightlyIcon"], "nightlyIcon", frozenset({"outputs"}))
    wordmark, raw_wordmark = load_asset(root["wordmark"], "wordmark", frozenset({"outputSha256"}))

    raw_outputs = raw_icon["outputs"]
    if not isinstance(raw_outputs, list):
        fail("nightlyIcon.outputs must be an array")
    outputs: list[IconOutputLock] = []
    for index, raw_output in enumerate(raw_outputs):
        field = f"nightlyIcon.outputs[{index}]"
        output_value = require_object(raw_output, field, {"density", "size", "sha256"})
        density = require_string(output_value["density"], f"{field}.density")
        size = output_value["size"]
        if type(size) is not int or NIGHTLY_ICON_DENSITIES.get(density) != size:
            fail(f"{field} must pair a launcher density with its icon size")
        outputs.append(IconOutputLock(density, size, require_sha256(output_value["sha256"], f"{field}.sha256")))
    if sorted(output.density for output in outputs) != sorted(NIGHTLY_ICON_DENSITIES):
        fail("nightlyIcon.outputs must list each launcher density exactly once")

    return DesignLock(
        load_package(root["package"]),
        tokens,
        nightly_icon,
        tuple(outputs),
        wordmark,
        require_sha256(raw_wordmark["outputSha256"], "wordmark.outputSha256"),
    )


def locked_outputs(lock: DesignLock) -> tuple[tuple[Path, str], ...]:
    return (
        ((TOKENS_PATH, lock.tokens.source_sha256),)
        + tuple((output.path, output.sha256) for output in lock.nightly_icon_outputs)
        + ((WORDMARK_PATH, lock.wordmark_output_sha256),)
    )


def check_outputs(lock: DesignLock) -> None:
    failures: list[str] = []
    for path, expected in locked_outputs(lock):
        try:
            actual = sha256((REPO_ROOT / path).read_bytes())
        except OSError:
            failures.append(f"missing {path}")
            continue
        if not hmac.compare_digest(actual, expected):
            failures.append(f"drifted {path}")
    if failures:
        fail("design asset drift detected:\n  " + "\n  ".join(failures))
    print(
        f"Design asset drift check passed "
        f"({len(locked_outputs(lock))} files, "
        f"{lock.package.name}@{lock.package.version})"
    )


def fetch_package(package: PackageLock) -> bytes:
    try:
        with urllib.request.urlopen(package.tarball, timeout=30) as response:
            archive = response.read()
    except OSError as error:
        fail(f"package download failed: {error}")
    expected = package.integrity.removeprefix("sha512-")
    actual = base64.b64encode(hashlib.sha512(archive).digest()).decode("ascii")
    if not hmac.compare_digest(actual, expected):
        fail("downloaded package does not match locked sha512 SRI")
    return archive


def read_asset(package_tar: tarfile.TarFile, asset: AssetLock) -> bytes:
    try:
        member = package_tar.extractfile(f"package/{asset.asset}")
    except KeyError:
        member = None
    if member is None:
        fail(f"locked package is missing {asset.asset}")
    return member.read()


def render_nightly_icon(source: bytes, outputs: tuple[IconOutputLock, ...]) -> dict[Path, bytes]:
    # The starfield is baked into the art: downscale with a smooth filter
    # (sips uses Lanczos-class resampling), never re-render the stars.
    if shutil.which("sips") is None:
        fail("sips not found; nightly icon regeneration needs macOS")
    rendered: dict[Path, bytes] = {}
    with tempfile.TemporaryDirectory() as directory:
        source_path = Path(directory) / "source.png"
        source_path.write_bytes(source)
        for output in outputs:
            target = Path(directory) / f"{output.density}.png"
            subprocess.run(
                ["sips", "-s", "format", "png", "-z", str(output.size), str(output.size),
                 str(source_path), "--out", str(target)],
                check=True,
                stdout=subprocess.DEVNULL,
            )
            rendered[output.path] = target.read_bytes()
    return rendered


def android_color(fill: str, asset: str) -> str:
    if fill in SVG_FILLS:
        return SVG_FILLS[fill]
    match = HEX_COLOR_PATTERN.fullmatch(fill)
    if match is None:
        fail(f"{asset} uses unsupported fill {fill!r}")
    return f"#FF{match.group(1).upper()}"


def render_wordmark(source: bytes, asset: str, version: str) -> bytes:
    # Fails on anything the converter does not model (transforms, strokes,
    # non-path shapes) so a design release cannot silently mis-render.
    try:
        root = ElementTree.fromstring(source)
    except ElementTree.ParseError as error:
        fail(f"{asset} is invalid XML: {error}")
    view_box = root.attrib.get("viewBox", "").split()
    if len(view_box) != 4 or not all(NUMBER_PATTERN.fullmatch(value) for value in view_box):
        fail(f"{asset} needs a numeric viewBox")
    min_x, min_y, width, height = view_box
    paths: list[str] = []
    for element in root:
        if element.tag != f"{SVG_NAMESPACE}path":
            fail(f"{asset} contains unsupported element {element.tag}")
        unsupported = set(element.attrib) - {"d", "fill", "fill-rule", "clip-rule"}
        if unsupported or "d" not in element.attrib or "fill" not in element.attrib:
            fail(f"{asset} has a path the converter cannot model")
        fill_rule = element.attrib.get("fill-rule", "nonzero")
        if fill_rule not in {"nonzero", "evenodd"}:
            fail(f"{asset} uses unsupported fill-rule {fill_rule!r}")
        fill_type = '\n            android:fillType="evenOdd"' if fill_rule == "evenodd" else ""
        paths.append(
            "        <path\n"
            f'            android:fillColor="{android_color(element.attrib["fill"], asset)}"{fill_type}\n'
            f'            android:pathData="{escape(element.attrib["d"], {chr(34): "&quot;"})}" />'
        )
    if not paths:
        fail(f"{asset} contains no path data")
    translate_x = -float(min_x) if float(min_x) else 0.0
    translate_y = -float(min_y) if float(min_y) else 0.0
    return (
        f"<!-- put.io retro wordmark, @putdotio/design v{version} {asset}. "
        "Generated by scripts/sync-design-assets.sh; do not edit. -->\n"
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        f'    android:width="{float(width) / 4:g}dp"\n'
        f'    android:height="{float(height) / 4:g}dp"\n'
        f'    android:viewportWidth="{width}"\n'
        f'    android:viewportHeight="{height}">\n'
        "    <group\n"
        f'        android:translateX="{translate_x:g}"\n'
        f'        android:translateY="{translate_y:g}">\n'
        + "\n".join(paths)
        + "\n    </group>\n</vector>\n"
    ).encode("utf-8")


def sync(lock: DesignLock) -> None:
    archive = fetch_package(lock.package)
    try:
        package_tar = tarfile.open(fileobj=io.BytesIO(archive), mode="r:gz")
    except tarfile.TarError as error:
        fail(f"locked package is not a readable tarball: {error}")
    with package_tar:
        tokens = read_asset(package_tar, lock.tokens)
        nightly_icon = read_asset(package_tar, lock.nightly_icon)
        wordmark = read_asset(package_tar, lock.wordmark)

    # Every digest is compared before anything is written, and all mismatches
    # are reported together so a release bump needs one sync to re-pin.
    mismatches = [
        f"{label} is {sha256(data)}, locked {expected}"
        for label, data, expected in (
            (lock.tokens.asset, tokens, lock.tokens.source_sha256),
            (lock.nightly_icon.asset, nightly_icon, lock.nightly_icon.source_sha256),
            (lock.wordmark.asset, wordmark, lock.wordmark.source_sha256),
        )
        if not hmac.compare_digest(sha256(data), expected)
    ]
    outputs = {
        TOKENS_PATH: tokens,
        **render_nightly_icon(nightly_icon, lock.nightly_icon_outputs),
        WORDMARK_PATH: render_wordmark(wordmark, lock.wordmark.asset, lock.package.version),
    }
    mismatches += [
        f"generated {path} is {sha256(outputs[path])}, locked {expected}"
        for path, expected in locked_outputs(lock)[1:]
        if not hmac.compare_digest(sha256(outputs[path]), expected)
    ]
    if mismatches:
        fail("unlocked design assets; nothing written:\n  " + "\n  ".join(mismatches))
    for path, data in outputs.items():
        (REPO_ROOT / path).parent.mkdir(parents=True, exist_ok=True)
        (REPO_ROOT / path).write_bytes(data)
        print(f"wrote {path}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--check",
        action="store_true",
        help="verify checked-in design assets from the lock without network access",
    )
    arguments = parser.parse_args()
    lock = load_lock()
    if arguments.check:
        check_outputs(lock)
    else:
        sync(lock)


if __name__ == "__main__":
    main()

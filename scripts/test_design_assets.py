#!/usr/bin/env python3
"""Hermetic tests for the @putdotio/design lock and offline drift check."""

from __future__ import annotations

import base64
import contextlib
import hashlib
import io
import json
import sys
import tarfile
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.dont_write_bytecode = True

import design_assets


def tarball(members: dict[str, bytes]) -> bytes:
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w:gz") as archive:
        for name, data in members.items():
            info = tarfile.TarInfo(f"package/{name}")
            info.size = len(data)
            archive.addfile(info, io.BytesIO(data))
    return buffer.getvalue()


WORDMARK_ASSET = "system/assets/logo-retro-dark.svg"
WORDMARK_SVG = (
    b'<svg width="40" height="20" viewBox="0 -4 40 20" fill="none" xmlns="http://www.w3.org/2000/svg">'
    b'<path fill-rule="evenodd" clip-rule="evenodd" d="M0 0H10V10H0Z" fill="white"></path>'
    b'<path d="M20 0H30V10H20Z" fill="#FDCE45"></path>'
    b"</svg>"
)
MONOCHROME_ASSET = "system/assets/app-icon-monochrome.svg"
MONOCHROME_SVG = (
    b"<!-- comment -->"
    b'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108">'
    b'<path fill-rule="evenodd" d="M30 30H78V78H30ZM40 40V68H68V40Z"/>'
    b"</svg>"
)


class DesignAssetLockTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.repo_root = Path(self.temporary_directory.name)
        self.lock_path = self.repo_root / "design" / "putio-design.lock.json"
        self.tokens = b'{"tokens": true}\n'
        self.production_source = b"production art"
        self.icon_source = b"icon art"
        self.dev_source = b"dev art"
        self.production_outputs = self.channel_outputs("production")
        self.icon_outputs = self.channel_outputs("nightly")
        self.dev_outputs = self.channel_outputs("dev")
        self.wordmark = design_assets.render_wordmark(WORDMARK_SVG, WORDMARK_ASSET, "3.3.0")
        self.monochrome = design_assets.render_monochrome_icon(MONOCHROME_SVG, MONOCHROME_ASSET, "3.3.0")
        self.document: dict[str, object] = {
            "schemaVersion": 1,
            "package": {
                "name": "@putdotio/design",
                "version": "3.3.0",
                "tarball": "https://registry.npmjs.org/@putdotio/design/-/design-3.3.0.tgz",
                "integrity": "sha512-"
                + base64.b64encode(hashlib.sha512(b"archive").digest()).decode("ascii"),
            },
            "tokens": {
                "asset": "dist/tokens.dtcg.json",
                "sourceSha256": hashlib.sha256(self.tokens).hexdigest(),
            },
            "productionIcon": self.icon_lock(
                "system/assets/app-icon-prod-dark.png", self.production_source, self.production_outputs,
            ),
            "nightlyIcon": self.icon_lock(
                "system/assets/app-icon-nightly-stars.png", self.icon_source, self.icon_outputs,
            ),
            "devIcon": self.icon_lock(
                "system/assets/app-icon-dev-blueprint.png", self.dev_source, self.dev_outputs,
            ),
            "wordmark": {
                "asset": WORDMARK_ASSET,
                "sourceSha256": hashlib.sha256(WORDMARK_SVG).hexdigest(),
                "outputSha256": hashlib.sha256(self.wordmark).hexdigest(),
            },
            "monochromeIcon": {
                "asset": MONOCHROME_ASSET,
                "sourceSha256": hashlib.sha256(MONOCHROME_SVG).hexdigest(),
                "outputSha256": hashlib.sha256(self.monochrome).hexdigest(),
            },
        }
        self.lock_path.parent.mkdir(parents=True)
        (self.repo_root / design_assets.TOKENS_PATH).write_bytes(self.tokens)
        for path, data in self.rendered_icons().items():
            (self.repo_root / path).parent.mkdir(parents=True, exist_ok=True)
            (self.repo_root / path).write_bytes(data)
        self.wordmark_path.parent.mkdir(parents=True, exist_ok=True)
        self.wordmark_path.write_bytes(self.wordmark)
        self.monochrome_path.parent.mkdir(parents=True, exist_ok=True)
        self.monochrome_path.write_bytes(self.monochrome)
        self.repo_patch = patch.multiple(
            design_assets,
            REPO_ROOT=self.repo_root,
            LOCK_PATH=self.lock_path,
        )
        self.repo_patch.start()
        self.addCleanup(self.repo_patch.stop)

    @staticmethod
    def channel_outputs(channel: str) -> dict[str, dict[str, bytes]]:
        return {
            kind: {density: f"{density} {channel} {kind}".encode("ascii") for density in densities}
            for kind, densities in (
                ("outputs", design_assets.LAUNCHER_ICON_DENSITIES),
                ("artOutputs", design_assets.LAUNCHER_ART_DENSITIES),
            )
        }

    @staticmethod
    def icon_lock(asset: str, source: bytes, outputs: dict[str, dict[str, bytes]]) -> dict[str, object]:
        return {
            "asset": asset,
            "sourceSha256": hashlib.sha256(source).hexdigest(),
            **{
                kind: [
                    {"density": density, "size": size, "sha256": hashlib.sha256(outputs[kind][density]).hexdigest()}
                    for density, size in densities.items()
                ]
                for kind, densities in (
                    ("outputs", design_assets.LAUNCHER_ICON_DENSITIES),
                    ("artOutputs", design_assets.LAUNCHER_ART_DENSITIES),
                )
            },
        }

    @property
    def wordmark_path(self) -> Path:
        return self.repo_root / design_assets.WORDMARK_PATH

    @property
    def monochrome_path(self) -> Path:
        return self.repo_root / design_assets.MONOCHROME_ICON_PATH

    def icon_path(self, density: str, res: str = "mobile/src/nightly/res") -> Path:
        return self.repo_root / res / f"drawable-{density}" / "putio_icon.png"

    def icon_paths(self) -> list[Path]:
        return [self.repo_root / path for path in self.rendered_icons()]

    def write_lock(self, document: object | None = None) -> None:
        self.lock_path.write_text(
            json.dumps(self.document if document is None else document),
            encoding="utf-8",
        )

    def check(self) -> None:
        with contextlib.redirect_stdout(io.StringIO()):
            design_assets.check_outputs(design_assets.load_lock())

    def test_duplicate_key_is_rejected(self) -> None:
        self.write_lock()
        content = self.lock_path.read_text(encoding="utf-8")
        self.lock_path.write_text(
            content.replace('"schemaVersion": 1', '"schemaVersion": 1, "schemaVersion": 1'),
            encoding="utf-8",
        )
        with self.assertRaisesRegex(SystemExit, "duplicate key 'schemaVersion'"):
            self.check()

    def test_tarball_must_match_version(self) -> None:
        document = json.loads(json.dumps(self.document))
        document["package"]["version"] = "3.4.0"
        self.write_lock(document)
        with self.assertRaisesRegex(SystemExit, "tarball does not match the locked version"):
            self.check()

    def test_every_density_must_be_locked(self) -> None:
        document = json.loads(json.dumps(self.document))
        document["nightlyIcon"]["outputs"].pop()
        self.write_lock(document)
        with self.assertRaisesRegex(SystemExit, "each launcher density exactly once"):
            self.check()

    def test_drifted_outputs_are_rejected(self) -> None:
        self.write_lock()
        for path in (
            self.repo_root / design_assets.TOKENS_PATH,
            self.icon_path("xhdpi"),
            self.icon_path("xxhdpi", "tv/src/nightly/res"),
            self.icon_path("hdpi", "core/design/src/main/res"),
            self.wordmark_path,
        ):
            relative = path.relative_to(self.repo_root).as_posix()
            with self.subTest(path=relative):
                original = path.read_bytes()
                path.write_bytes(b"edited")
                with self.assertRaisesRegex(SystemExit, f"drifted {relative}"):
                    self.check()
                path.write_bytes(original)

    def test_missing_launcher_icons_are_rejected(self) -> None:
        self.write_lock()
        self.icon_path("mdpi").unlink()
        self.icon_path("xxxhdpi", "core/design/src/main/res").unlink()
        with self.assertRaises(SystemExit) as raised:
            self.check()
        self.assertIn("missing mobile/src/nightly/res/drawable-mdpi/putio_icon.png", str(raised.exception))
        self.assertIn("missing core/design/src/main/res/drawable-xxxhdpi/putio_icon.png", str(raised.exception))

    def test_art_outputs_must_pair_each_density_with_its_art_size(self) -> None:
        document = json.loads(json.dumps(self.document))
        document["devIcon"]["artOutputs"][0]["size"] = 48
        self.write_lock(document)
        with self.assertRaisesRegex(SystemExit, r"devIcon.artOutputs\[0\] must pair a launcher density"):
            self.check()

    def test_drifted_art_and_dev_icons_are_rejected(self) -> None:
        self.write_lock()
        for relative in (
            "mobile/src/main/res/drawable-xxhdpi/putio_launcher_art.png",
            "mobile/src/debug/res/drawable-mdpi/putio_icon.png",
            "mobile/src/debug/res/drawable-xxxhdpi/putio_launcher_art.png",
        ):
            with self.subTest(path=relative):
                path = self.repo_root / relative
                original = path.read_bytes()
                path.write_bytes(b"edited")
                with self.assertRaisesRegex(SystemExit, f"drifted {relative}"):
                    self.check()
                path.write_bytes(original)

    def test_production_icon_must_lock_every_density(self) -> None:
        document = json.loads(json.dumps(self.document))
        document["productionIcon"]["outputs"].pop()
        self.write_lock(document)
        with self.assertRaisesRegex(SystemExit, "productionIcon.outputs must list each launcher density"):
            self.check()

    def test_wordmark_keeps_fills_and_viewbox_offset(self) -> None:
        drawable = self.wordmark.decode("utf-8")
        self.assertIn('android:viewportHeight="20"', drawable)
        self.assertIn('android:translateY="4"', drawable)
        self.assertIn('android:fillColor="#FFFFFFFF"\n            android:fillType="evenOdd"', drawable)
        self.assertIn('android:fillColor="#FFFDCE45"\n            android:pathData="M20 0H30V10H20Z"', drawable)
        self.assertEqual(drawable.count("fillType"), 1)
        self.assertNotIn("clip", drawable)

    def test_wordmark_rejects_shapes_it_cannot_model(self) -> None:
        source = WORDMARK_SVG.replace(b"<path d=", b'<path transform="scale(2)" d=')
        with self.assertRaisesRegex(SystemExit, "path the converter cannot model"):
            design_assets.render_wordmark(source, WORDMARK_ASSET, "3.3.0")

    def test_monochrome_mark_fills_unfilled_paths_on_the_adaptive_grid(self) -> None:
        drawable = self.monochrome.decode("utf-8")
        self.assertIn('android:width="108dp"', drawable)
        self.assertIn('android:viewportWidth="108"', drawable)
        self.assertIn('android:fillColor="#FFFFFFFF"\n            android:fillType="evenOdd"', drawable)
        unfilled_wordmark = WORDMARK_SVG.replace(b' fill="#FDCE45"', b"")
        with self.assertRaisesRegex(SystemExit, "path the converter cannot model"):
            design_assets.render_wordmark(unfilled_wordmark, WORDMARK_ASSET, "3.3.0")

    def fetch(self, archive: bytes) -> object:
        class Response(io.BytesIO):
            def __enter__(self) -> "Response":
                return self

            def __exit__(self, *_: object) -> None:
                return None

        return patch.object(design_assets.urllib.request, "urlopen", return_value=Response(archive))

    def test_sync_rejects_unlocked_assets_without_writing(self) -> None:
        archive = self.lock_archive(tokens=b"tampered")
        rendered = self.rendered_icons()
        rendered[Path("mobile/src/nightly/res/drawable-hdpi/putio_icon.png")] = b"resampled"
        paths = self.replace_outputs(b"previous")

        with self.fetch(archive), self.render(rendered):
            with self.assertRaises(SystemExit) as raised:
                design_assets.sync(design_assets.load_lock())

        message = str(raised.exception)
        self.assertIn(f"dist/tokens.dtcg.json is {hashlib.sha256(b'tampered').hexdigest()}", message)
        self.assertIn("drawable-hdpi/putio_icon.png is", message)
        self.assertNotIn("app-icon-nightly-stars.png is", message)
        self.assertEqual([path.read_bytes() for path in paths], [b"previous"] * len(paths))

        archive = self.lock_archive(icon_source=b"tampered art")
        with self.fetch(archive), self.render(self.rendered_icons()):
            with self.assertRaises(SystemExit) as raised:
                design_assets.sync(design_assets.load_lock())

        self.assertIn(
            f"app-icon-nightly-stars.png is {hashlib.sha256(b'tampered art').hexdigest()}",
            str(raised.exception),
        )
        self.assertEqual([path.read_bytes() for path in paths], [b"previous"] * len(paths))

    def lock_archive(self, tokens: bytes | None = None, icon_source: bytes | None = None) -> bytes:
        archive = tarball({
            "dist/tokens.dtcg.json": self.tokens if tokens is None else tokens,
            "system/assets/app-icon-prod-dark.png": self.production_source,
            "system/assets/app-icon-nightly-stars.png": self.icon_source if icon_source is None else icon_source,
            "system/assets/app-icon-dev-blueprint.png": self.dev_source,
            WORDMARK_ASSET: WORDMARK_SVG,
            MONOCHROME_ASSET: MONOCHROME_SVG,
        })
        document = json.loads(json.dumps(self.document))
        document["package"]["integrity"] = "sha512-" + base64.b64encode(
            hashlib.sha512(archive).digest()
        ).decode("ascii")
        self.write_lock(document)
        return archive

    def rendered_icons(self) -> dict[Path, bytes]:
        return {
            res / f"drawable-{density}" / file_name: data
            for res_dirs, art_res_dirs, outputs in (
                (design_assets.PRODUCTION_RES_DIRS, design_assets.PRODUCTION_ART_RES_DIRS, self.production_outputs),
                (design_assets.NIGHTLY_RES_DIRS, design_assets.NIGHTLY_ART_RES_DIRS, self.icon_outputs),
                (design_assets.DEV_RES_DIRS, design_assets.DEV_ART_RES_DIRS, self.dev_outputs),
            )
            for kind, dirs, file_name in (
                ("outputs", res_dirs, design_assets.LAUNCHER_ICON_FILE),
                ("artOutputs", art_res_dirs, design_assets.LAUNCHER_ART_FILE),
            )
            for res in dirs
            for density, data in outputs[kind].items()
        }

    def render(self, rendered: dict[Path, bytes]) -> object:
        """Stands in for sips: each launcher icon gets the bytes of its own output paths."""
        return patch.object(
            design_assets,
            "render_launcher_icon",
            side_effect=lambda _source, outputs: {
                path: rendered[path] for output in outputs for path in output.paths
            },
        )

    def replace_outputs(self, data: bytes) -> list[Path]:
        paths = [
            self.repo_root / design_assets.TOKENS_PATH, self.wordmark_path, self.monochrome_path,
        ] + self.icon_paths()
        for path in paths:
            path.write_bytes(data)
        return paths

    def test_sync_writes_every_locked_output(self) -> None:
        archive = self.lock_archive()
        self.replace_outputs(b"previous")

        with contextlib.redirect_stdout(io.StringIO()), self.fetch(archive), self.render(self.rendered_icons()):
            design_assets.sync(design_assets.load_lock())

        self.check()

    def test_sync_rejects_package_without_locked_integrity(self) -> None:
        self.write_lock()

        with self.fetch(b"other archive"):
            with self.assertRaisesRegex(SystemExit, "does not match locked sha512 SRI"):
                design_assets.sync(design_assets.load_lock())
        self.assertEqual((self.repo_root / design_assets.TOKENS_PATH).read_bytes(), self.tokens)


if __name__ == "__main__":
    unittest.main()

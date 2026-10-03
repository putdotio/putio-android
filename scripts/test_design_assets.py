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


class DesignAssetLockTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.repo_root = Path(self.temporary_directory.name)
        self.lock_path = self.repo_root / "design" / "putio-design.lock.json"
        self.tokens = b'{"tokens": true}\n'
        self.icon_source = b"icon art"
        self.icon_outputs = {
            density: f"{density} icon".encode("ascii")
            for density in design_assets.NIGHTLY_ICON_DENSITIES
        }
        self.wordmark = design_assets.render_wordmark(WORDMARK_SVG, WORDMARK_ASSET, "3.3.0")
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
            "nightlyIcon": {
                "asset": "system/assets/app-icon-nightly-stars.png",
                "sourceSha256": hashlib.sha256(self.icon_source).hexdigest(),
                "outputs": [
                    {"density": density, "size": size, "sha256": hashlib.sha256(self.icon_outputs[density]).hexdigest()}
                    for density, size in design_assets.NIGHTLY_ICON_DENSITIES.items()
                ],
            },
            "wordmark": {
                "asset": WORDMARK_ASSET,
                "sourceSha256": hashlib.sha256(WORDMARK_SVG).hexdigest(),
                "outputSha256": hashlib.sha256(self.wordmark).hexdigest(),
            },
        }
        self.lock_path.parent.mkdir(parents=True)
        (self.repo_root / design_assets.TOKENS_PATH).write_bytes(self.tokens)
        for res in design_assets.NIGHTLY_RES_DIRS:
            for density, data in self.icon_outputs.items():
                path = self.icon_path(density, str(res))
                path.parent.mkdir(parents=True)
                path.write_bytes(data)
        self.wordmark_path.parent.mkdir(parents=True)
        self.wordmark_path.write_bytes(self.wordmark)
        self.repo_patch = patch.multiple(
            design_assets,
            REPO_ROOT=self.repo_root,
            LOCK_PATH=self.lock_path,
        )
        self.repo_patch.start()
        self.addCleanup(self.repo_patch.stop)

    @property
    def wordmark_path(self) -> Path:
        return self.repo_root / design_assets.WORDMARK_PATH

    def icon_path(self, density: str, res: str = "mobile/src/nightly/res") -> Path:
        return self.repo_root / res / f"drawable-{density}" / "putio_icon.png"

    def icon_paths(self) -> list[Path]:
        return [
            self.icon_path(density, str(res))
            for res in design_assets.NIGHTLY_RES_DIRS
            for density in self.icon_outputs
        ]

    def write_lock(self, document: object | None = None) -> None:
        self.lock_path.write_text(
            json.dumps(self.document if document is None else document),
            encoding="utf-8",
        )

    def check(self) -> None:
        with contextlib.redirect_stdout(io.StringIO()):
            design_assets.check_outputs(design_assets.load_lock())

    def test_valid_lock_passes_offline_check(self) -> None:
        self.write_lock()
        self.check()

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

    def test_drifted_tokens_are_rejected(self) -> None:
        self.write_lock()
        (self.repo_root / design_assets.TOKENS_PATH).write_bytes(b"{}\n")
        with self.assertRaisesRegex(SystemExit, "drifted design/tokens.dtcg.json"):
            self.check()

    def test_drifted_nightly_icon_is_rejected(self) -> None:
        self.write_lock()
        self.icon_path("xhdpi").write_bytes(b"edited")
        with self.assertRaisesRegex(SystemExit, "drifted mobile/src/nightly/res/drawable-xhdpi/putio_icon.png"):
            self.check()

    def test_missing_nightly_icon_is_rejected(self) -> None:
        self.write_lock()
        self.icon_path("mdpi").unlink()
        with self.assertRaisesRegex(SystemExit, "missing mobile/src/nightly/res/drawable-mdpi/putio_icon.png"):
            self.check()

    def test_drifted_tv_nightly_icon_is_rejected(self) -> None:
        self.write_lock()
        self.icon_path("xxhdpi", "tv/src/nightly/res").write_bytes(b"edited")
        with self.assertRaisesRegex(SystemExit, "drifted tv/src/nightly/res/drawable-xxhdpi/putio_icon.png"):
            self.check()

    def test_drifted_wordmark_is_rejected(self) -> None:
        self.write_lock()
        self.wordmark_path.write_bytes(b"edited")
        with self.assertRaisesRegex(SystemExit, "drifted mobile/src/main/res/drawable/putio_wordmark.xml"):
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

    def fetch(self, archive: bytes) -> object:
        class Response(io.BytesIO):
            def __enter__(self) -> "Response":
                return self

            def __exit__(self, *_: object) -> None:
                return None

        return patch.object(design_assets.urllib.request, "urlopen", return_value=Response(archive))

    def test_sync_rejects_unlocked_assets_without_writing(self) -> None:
        archive = tarball({
            "dist/tokens.dtcg.json": b"tampered",
            "system/assets/app-icon-nightly-stars.png": self.icon_source,
            WORDMARK_ASSET: WORDMARK_SVG,
        })
        document = json.loads(json.dumps(self.document))
        document["package"]["integrity"] = "sha512-" + base64.b64encode(
            hashlib.sha512(archive).digest()
        ).decode("ascii")
        self.write_lock(document)
        rendered = self.rendered_icons()
        rendered[Path("mobile/src/nightly/res/drawable-hdpi/putio_icon.png")] = b"resampled"

        with self.fetch(archive), patch.object(design_assets, "render_nightly_icon", return_value=rendered):
            with self.assertRaises(SystemExit) as raised:
                design_assets.sync(design_assets.load_lock())

        message = str(raised.exception)
        self.assertIn(f"dist/tokens.dtcg.json is {hashlib.sha256(b'tampered').hexdigest()}", message)
        self.assertIn("drawable-hdpi/putio_icon.png is", message)
        self.assertNotIn("app-icon-nightly-stars.png is", message)
        self.assertEqual((self.repo_root / design_assets.TOKENS_PATH).read_bytes(), self.tokens)

    def lock_archive(self, icon_source: bytes) -> bytes:
        archive = tarball({
            "dist/tokens.dtcg.json": self.tokens,
            "system/assets/app-icon-nightly-stars.png": icon_source,
            WORDMARK_ASSET: WORDMARK_SVG,
        })
        document = json.loads(json.dumps(self.document))
        document["package"]["integrity"] = "sha512-" + base64.b64encode(
            hashlib.sha512(archive).digest()
        ).decode("ascii")
        self.write_lock(document)
        return archive

    def rendered_icons(self) -> dict[Path, bytes]:
        return {
            res / f"drawable-{density}" / "putio_icon.png": data
            for res in design_assets.NIGHTLY_RES_DIRS
            for density, data in self.icon_outputs.items()
        }

    def replace_outputs(self, data: bytes) -> list[Path]:
        paths = [self.repo_root / design_assets.TOKENS_PATH, self.wordmark_path] + self.icon_paths()
        for path in paths:
            path.write_bytes(data)
        return paths

    def test_sync_rejects_unlocked_nightly_source_without_writing(self) -> None:
        archive = self.lock_archive(b"tampered art")
        paths = self.replace_outputs(b"previous")

        with self.fetch(archive), patch.object(
            design_assets, "render_nightly_icon", return_value=self.rendered_icons()
        ):
            with self.assertRaises(SystemExit) as raised:
                design_assets.sync(design_assets.load_lock())

        self.assertIn(
            f"app-icon-nightly-stars.png is {hashlib.sha256(b'tampered art').hexdigest()}",
            str(raised.exception),
        )
        self.assertEqual([path.read_bytes() for path in paths], [b"previous"] * len(paths))

    def test_sync_writes_every_locked_output(self) -> None:
        archive = self.lock_archive(self.icon_source)
        self.replace_outputs(b"previous")

        with contextlib.redirect_stdout(io.StringIO()), self.fetch(archive), patch.object(
            design_assets, "render_nightly_icon", return_value=self.rendered_icons()
        ):
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

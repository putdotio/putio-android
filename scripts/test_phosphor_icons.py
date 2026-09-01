#!/usr/bin/env python3
"""Hermetic tests for the Phosphor icon lock and offline drift check."""

from __future__ import annotations

import base64
import contextlib
import hashlib
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import phosphor_icons


class PhosphorIconLockTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.repo_root = Path(self.temporary_directory.name)
        self.lock_path = self.repo_root / "design" / "phosphor-icons.lock.json"
        self.output_path = (
            self.repo_root
            / "app"
            / "src"
            / "main"
            / "res"
            / "drawable"
            / "ic_ph_folder.xml"
        )
        self.output = b"locked drawable\n"
        self.document: dict[str, object] = {
            "schemaVersion": 1,
            "package": {
                "name": "@phosphor-icons/core",
                "version": "2.1.1",
                "tarball": (
                    "https://registry.npmjs.org/@phosphor-icons/core/-/"
                    "core-2.1.1.tgz"
                ),
                "integrity": "sha512-"
                + base64.b64encode(hashlib.sha512(b"archive").digest()).decode("ascii"),
            },
            "icons": [
                {
                    "sourceSet": "main",
                    "resource": "ic_ph_folder",
                    "asset": "assets/regular/folder.svg",
                    "sourceSha256": hashlib.sha256(b"source").hexdigest(),
                    "outputSha256": hashlib.sha256(self.output).hexdigest(),
                }
            ],
        }
        self.lock_path.parent.mkdir(parents=True)
        self.output_path.parent.mkdir(parents=True)
        self.output_path.write_bytes(self.output)
        self.repo_patch = patch.multiple(
            phosphor_icons,
            REPO_ROOT=self.repo_root,
            LOCK_PATH=self.lock_path,
        )
        self.repo_patch.start()
        self.addCleanup(self.repo_patch.stop)

    def write_lock(self, document: object | None = None) -> None:
        self.lock_path.write_text(
            json.dumps(self.document if document is None else document),
            encoding="utf-8",
        )

    def load(self) -> tuple[phosphor_icons.PackageLock, tuple[phosphor_icons.IconLock, ...]]:
        with contextlib.redirect_stdout(io.StringIO()):
            return phosphor_icons.load_lock()

    def assert_lock_rejected(self, message: str) -> None:
        with self.assertRaisesRegex(SystemExit, message):
            self.load()

    def test_valid_lock_passes_offline_check(self) -> None:
        self.write_lock()
        package, icons = self.load()

        with contextlib.redirect_stdout(io.StringIO()):
            phosphor_icons.check_outputs(package, icons)

    def test_duplicate_key_is_rejected(self) -> None:
        self.write_lock()
        content = self.lock_path.read_text(encoding="utf-8")
        self.lock_path.write_text(
            content.replace('"schemaVersion": 1', '"schemaVersion": 1, "schemaVersion": 1'),
            encoding="utf-8",
        )

        self.assert_lock_rejected("duplicate key 'schemaVersion'")

    def test_non_integer_schema_versions_are_rejected(self) -> None:
        for schema_version in (True, 1.0):
            with self.subTest(schema_version=schema_version):
                document = dict(self.document)
                document["schemaVersion"] = schema_version
                self.write_lock(document)
                self.assert_lock_rejected("lock must use schemaVersion 1")

    def test_invalid_integrity_values_are_rejected(self) -> None:
        for integrity, message in (
            ("sha512-not-base64", "must contain valid base64"),
            ("sha512-YQ==", "must contain a canonical SHA-512 digest"),
        ):
            with self.subTest(integrity=integrity):
                document = json.loads(json.dumps(self.document))
                document["package"]["integrity"] = integrity
                self.write_lock(document)
                self.assert_lock_rejected(message)

    def test_missing_output_is_rejected(self) -> None:
        self.write_lock()
        package, icons = self.load()
        self.output_path.unlink()

        with self.assertRaisesRegex(SystemExit, "missing app/src/main/res/drawable/ic_ph_folder.xml"):
            phosphor_icons.check_outputs(package, icons)

    def test_drifted_output_is_rejected(self) -> None:
        self.write_lock()
        package, icons = self.load()
        self.output_path.write_bytes(b"drifted\n")

        with self.assertRaisesRegex(SystemExit, "drifted app/src/main/res/drawable/ic_ph_folder.xml"):
            phosphor_icons.check_outputs(package, icons)

    def test_stale_generated_output_is_rejected(self) -> None:
        self.write_lock()
        package, icons = self.load()
        stale_path = self.output_path.with_name("ic_ph_stale.xml")
        stale_path.write_text(phosphor_icons.GENERATED_MARKER, encoding="utf-8")

        with self.assertRaisesRegex(SystemExit, "stale app/src/main/res/drawable/ic_ph_stale.xml"):
            phosphor_icons.check_outputs(package, icons)


if __name__ == "__main__":
    unittest.main()

#!/usr/bin/env python3
"""Hermetic tests for the brand font lock and sync; the network is a fake."""

from __future__ import annotations

import contextlib
import hashlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.dont_write_bytecode = True

import brand_fonts

FONT = b"font bytes"


def lock_json(**overrides: object) -> str:
    entry = {"path": "fonts/gt-america/regular.ttf", "sha256": hashlib.sha256(FONT).hexdigest(), "family": "GT America", "weight": 400}
    root = {"baseUrl": "https://static.put.io", "directory": "fonts", "files": {"gt_america_regular.ttf": entry}}
    for key, value in overrides.items():
        if key in root:
            root[key] = value
        else:
            entry[key] = value
    return json.dumps(root)


class LockTest(unittest.TestCase):
    def test_repository_lock_parses(self) -> None:
        lock = brand_fonts.parse_lock(brand_fonts.LOCK_PATH.read_text(encoding="utf-8"))
        self.assertEqual("core/design/brand-fonts", lock.directory)
        self.assertTrue(all(font.url.startswith("https://static.put.io/fonts/") for font in lock.fonts))

    def test_lock_rejects_what_could_escape_its_origin_or_directory(self) -> None:
        for overrides, expected in (
            ({"baseUrl": "https://example.com"}, "fetch from"),
            ({"directory": "../fonts"}, "directory"),
            ({"path": "fonts/../secret.ttf"}, "path"),
            ({"path": "//example.com/fonts/a.ttf"}, "path"),
            ({"sha256": "ABC"}, "sha256"),
        ):
            with self.subTest(overrides=overrides), self.assertRaises(SystemExit) as raised:
                brand_fonts.parse_lock(lock_json(**overrides))
            self.assertIn(expected, str(raised.exception))


class SyncTest(unittest.TestCase):
    def setUp(self) -> None:
        self.root = Path(self.enterContext(tempfile.TemporaryDirectory()))
        self.requests: list[str] = []
        self.enterContext(contextlib.redirect_stdout(io.StringIO()))

    def serve(self, data: bytes):
        def download(url: str) -> bytes:
            self.requests.append(url)
            return data

        return download

    def test_sync_writes_verified_fonts_and_skips_matching_ones(self) -> None:
        lock = brand_fonts.parse_lock(lock_json())
        brand_fonts.sync(lock, self.root, self.serve(FONT))
        self.assertEqual(FONT, (self.root / "fonts/gt_america_regular.ttf").read_bytes())
        self.assertEqual(["https://static.put.io/fonts/gt-america/regular.ttf"], self.requests)
        brand_fonts.sync(lock, self.root, self.serve(FONT))
        self.assertEqual(1, len(self.requests))

    def test_sync_replaces_a_corrupt_font(self) -> None:
        (self.root / "fonts").mkdir()
        (self.root / "fonts/gt_america_regular.ttf").write_bytes(b"corrupt")
        brand_fonts.sync(brand_fonts.parse_lock(lock_json()), self.root, self.serve(FONT))
        self.assertEqual(FONT, (self.root / "fonts/gt_america_regular.ttf").read_bytes())

    def test_sync_rejects_an_unlocked_download_without_writing(self) -> None:
        with self.assertRaises(SystemExit) as raised:
            brand_fonts.sync(brand_fonts.parse_lock(lock_json()), self.root, self.serve(b"other"))
        self.assertIn("not written", str(raised.exception))
        self.assertEqual([], list((self.root / "fonts").iterdir()))


if __name__ == "__main__":
    unittest.main()

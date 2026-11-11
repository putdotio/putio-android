#!/usr/bin/env python3
"""Select the verify lanes a change affects, for CI and scripts/verify-changed.sh.

Reads changed repository paths, one per line, on stdin. A path no rule
recognizes selects every lane. `verify` is the union of the lanes' Gradle
tasks; CI adds each lane's debug assembles.

  ci_lanes.py --matrix [--all]   GitHub Actions matrix JSON for the lanes
  ci_lanes.py --tasks [--all]    Gradle tasks for the lanes, one per line
"""

import argparse
import json
import sys
from dataclasses import dataclass


@dataclass(frozen=True)
class Lane:
    name: str
    tasks: tuple[str, ...]
    ci_tasks: tuple[str, ...] = ()
    ffmpeg: bool = False
    brand_fonts: bool = False
    upload_apks: tuple[str, ...] = ()


LANES = {
    "docs": Lane("Docs", ("verifyDocs",)),
    "scripts": Lane("Scripts", ("verifyScripts",), ffmpeg=True),
    "libraries": Lane("Libraries", ("verifyLibraries",)),
    "mobile": Lane(
        "Mobile",
        ("verifyMobile",),
        (":mobile:assembleProductionDebug",),
        upload_apks=("mobile/build/outputs/apk/production/debug/*.apk",),
    ),
    "tv": Lane(
        "TV",
        ("verifyTv",),
        (":tv:assembleProductionDebug",),
        upload_apks=("tv/build/outputs/apk/production/debug/*.apk",),
    ),
    "release": Lane("Release", ("verifyRelease",), brand_fonts=True),
    "nightly": Lane(
        "Nightly debug",
        (),
        (":mobile:assembleNightlyDebug", ":tv:assembleNightlyDebug"),
        upload_apks=(
            "mobile/build/outputs/apk/nightly/debug/*.apk",
            "tv/build/outputs/apk/nightly/debug/*.apk",
        ),
    ),
}

ALL = frozenset(LANES)
NONE: frozenset[str] = frozenset()
APPS = frozenset({"release", "nightly"})
SHARED = frozenset({"libraries", "mobile", "tv"}) | APPS

UNBUILT_ROOTS = (".agents/", ".claude/", "docs/", "play/", ".github/ISSUE_TEMPLATE/")
UNBUILT_FILES = {"LICENSE", "NOTICE", "skills-lock.json", ".worktreeinclude", "renovate.json"}
OTHER_WORKFLOWS = {
    ".github/workflows/delivery-ref.yml",
    ".github/workflows/emulator-smoke.yml",
    ".github/workflows/play-data-safety.yml",
    ".github/workflows/play-internal.yml",
    ".github/workflows/play-release.yml",
}
NODE_FILES = {"package.json", ".node-version"}
BRAND_FONT_SCRIPTS = {"scripts/sync-brand-fonts.sh", "scripts/brand_fonts.py"}
MODULE_LANES = {
    "mobile": frozenset({"mobile"}) | APPS,
    "tv": frozenset({"tv"}) | APPS,
    "baselineprofile": frozenset({"tv"}) | APPS,
    "core": SHARED,
    "domain": SHARED,
}


def lanes_for(path: str) -> frozenset[str]:
    if path.endswith(".md"):
        return frozenset({"docs"})
    if path.startswith(UNBUILT_ROOTS) or path in UNBUILT_FILES or path in OTHER_WORKFLOWS:
        return NONE
    if path == ".oxfmtrc.json":
        return frozenset({"docs"})
    if path in NODE_FILES:
        return frozenset({"docs", "scripts"})
    if path in BRAND_FONT_SCRIPTS:
        return frozenset({"scripts", "release"})
    if path.startswith("scripts/"):
        return frozenset({"scripts"})
    root = path.split("/", 1)[0]
    if root in MODULE_LANES:
        lanes = MODULE_LANES[root]
        return lanes | {"scripts"} if "/res/" in path else lanes
    return ALL


def select(paths, everything=False) -> list[str]:
    if everything:
        return list(LANES)
    chosen = set()
    for path in paths:
        path = path.strip()
        if path:
            chosen |= lanes_for(path)
    return [key for key in LANES if key in chosen]


def matrix(keys) -> dict:
    include = []
    for key in keys:
        lane = LANES[key]
        include.append({
            "lane": key,
            "name": lane.name,
            "tasks": " ".join(lane.tasks + lane.ci_tasks),
            "ffmpeg": lane.ffmpeg,
            "brand_fonts": lane.brand_fonts,
            "upload_apks": "\n".join(f"putio-android/{glob}" for glob in lane.upload_apks),
        })
    return {"include": include}


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    output = parser.add_mutually_exclusive_group(required=True)
    output.add_argument("--matrix", action="store_true")
    output.add_argument("--tasks", action="store_true")
    parser.add_argument("--all", action="store_true", help="select every lane")
    args = parser.parse_args(argv)
    keys = select([] if args.all else sys.stdin, everything=args.all)
    if args.matrix:
        print(json.dumps(matrix(keys), separators=(",", ":")))
    else:
        for key in keys:
            for task in LANES[key].tasks:
                print(task)
    return 0


if __name__ == "__main__":
    sys.exit(main())

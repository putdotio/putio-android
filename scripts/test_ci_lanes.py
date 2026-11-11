#!/usr/bin/env python3
"""Pin which verify lanes each kind of change runs, and that the lanes cover verify."""

import contextlib
import io
import json
import pathlib
import re
import runpy
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent
lanes = runpy.run_path(str(ROOT / "scripts" / "ci_lanes.py"))
select = lanes["select"]
main = lanes["main"]
EVERY = ["docs", "scripts", "libraries", "mobile", "tv", "release", "nightly"]


def gradle_dependencies(task: str) -> set[str]:
    build = (ROOT / "build.gradle.kts").read_text()
    block = re.search(r'tasks\.register\("%s"\) \{(.*?)\n\}' % task, build, re.S)
    if block is None:
        raise AssertionError(f"build.gradle.kts registers no {task} task")
    return set(re.findall(r"\b(verify[A-Z]\w*)\b", block.group(1)))


class CiLanesTest(unittest.TestCase):
    def test_unbuilt_paths_select_no_lane(self):
        self.assertEqual([], select([
            ".agents/skills/verify-putio-android/scripts/capture.sh",
            ".claude/skills/adaptive",
            "docs/architecture.png",
            "play/data-safety.csv",
            "LICENSE",
            "skills-lock.json",
            ".github/workflows/play-internal.yml",
            ".github/workflows/emulator-smoke.yml",
            ".github/ISSUE_TEMPLATE/bug.yml",
        ]))

    def test_markdown_anywhere_selects_only_docs(self):
        self.assertEqual(["docs"], select([
            "AGENTS.md", "DESIGN.md", "docs/architecture.md",
            ".agents/skills/verify-putio-android/SKILL.md", "mobile/README.md",
        ]))

    def test_harness_scripts_select_only_scripts(self):
        self.assertEqual(["scripts"], select(["scripts/prove.sh", "scripts/test-evidence.sh"]))

    def test_brand_font_scripts_also_rebuild_release(self):
        self.assertEqual(["scripts", "release"], select(["scripts/sync-brand-fonts.sh"]))

    def test_node_pins_select_docs_and_scripts(self):
        self.assertEqual(["docs", "scripts"], select(["package.json"]))

    def test_app_code_selects_its_app_and_the_builds(self):
        self.assertEqual(["mobile", "release", "nightly"],
                         select(["mobile/src/main/kotlin/io/putdotio/android/MainActivity.kt"]))
        self.assertEqual(["tv", "release", "nightly"],
                         select(["tv/src/main/kotlin/io/putdotio/android/tv/TvShell.kt",
                                 "baselineprofile/tv/build.gradle.kts"]))

    def test_resources_also_select_the_asset_checks(self):
        self.assertEqual(["scripts", "mobile", "release", "nightly"],
                         select(["mobile/src/main/res/drawable/ic_ph_play.xml"]))

    def test_shared_modules_select_every_build_lane(self):
        self.assertEqual(["libraries", "mobile", "tv", "release", "nightly"],
                         select(["domain/files/src/main/kotlin/Files.kt"]))

    def test_build_configuration_and_unknown_paths_select_everything(self):
        for path in ("build.gradle.kts", "gradle/libs.versions.toml", "build-logic/build.gradle.kts",
                     ".github/workflows/ci.yml", "design/tokens.dtcg.json", "detekt.yml",
                     "new-module/src/main/kotlin/A.kt"):
            with self.subTest(path=path):
                self.assertEqual(EVERY, select([path]))

    def test_all_flag_and_empty_input(self):
        self.assertEqual(EVERY, select([], everything=True))
        self.assertEqual([], select(["", "\n"]))

    def test_lanes_are_exactly_what_verify_runs(self):
        lane_tasks = {task for key in EVERY for task in lanes["LANES"][key].tasks}
        self.assertEqual(lane_tasks, gradle_dependencies("verify"))
        build = (ROOT / "build.gradle.kts").read_text()
        for task in lane_tasks:
            with self.subTest(task=task):
                self.assertRegex(build, r'verifyLane\(\s*"%s"' % task)

    def test_matrix_carries_lane_tasks_and_ci_assembles(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            main(["--matrix", "--all"])
        include = json.loads(out.getvalue())["include"]
        self.assertEqual(EVERY, [entry["lane"] for entry in include])
        mobile = next(entry for entry in include if entry["lane"] == "mobile")
        self.assertEqual("verifyMobile :mobile:assembleProductionDebug", mobile["tasks"])
        release = next(entry for entry in include if entry["lane"] == "release")
        self.assertTrue(release["brand_fonts"])
        self.assertEqual("", release["upload_apks"])


if __name__ == "__main__":
    unittest.main()

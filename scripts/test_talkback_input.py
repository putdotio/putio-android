#!/usr/bin/env python3
"""Validate the hardware bridge's command arguments and physical coordinates."""

import contextlib
import io
import pathlib
import runpy
import unittest
from unittest.mock import Mock

bridge = runpy.run_path(str(pathlib.Path(__file__).with_name("talkback-input.py")))
gesture_points = bridge["gesture_points"]


class TalkBackInputTest(unittest.TestCase):
    def test_arguments_reject_devices_actions_and_invalid_dimensions(self):
        valid = ["--serial", "emulator-5554", "--action", "swipe-right",
                 "--width", "1080", "--height", "2400", "--rotation", "0"]
        for index, value in ((1, "physical-device"), (3, "shell"), (5, "0"),
                             (7, "999999"), (9, "4")):
            invalid = valid.copy()
            invalid[index] = value
            with self.subTest(value=value), contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit):
                    bridge["parse_arguments"](invalid)
        args = bridge["parse_arguments"](valid)
        self.assertEqual(("emulator-5554", "swipe-right", 1080, 2400, 0),
                         (args.serial, args.action, args.width, args.height, args.rotation))

    def test_rotated_display_points_map_to_natural_touch_coordinates(self):
        transform = bridge["natural_coordinates"]
        self.assertEqual((300, 1300), transform(300, 1300, 1080, 2400, 0))
        self.assertEqual((485, 720), transform(720, 594, 2400, 1080, 1))
        self.assertEqual((779, 1099), transform(300, 1300, 1080, 2400, 2))
        self.assertEqual((594, 1679), transform(720, 594, 2400, 1080, 3))
        for rotation in range(4):
            width, height = (1080, 2400) if rotation % 2 == 0 else (2400, 1080)
            corners = {transform(x, y, width, height, rotation)
                       for x in (0, width - 1) for y in (0, height - 1)}
            self.assertEqual({(0, 0), (1079, 0), (0, 2399), (1079, 2399)}, corners)

    def test_failed_down_still_releases_the_touch(self):
        send = Mock(side_effect=[TimeoutError("Injected but reply lost"), None])
        points = gesture_points("swipe-right", 1080, 2400)
        with self.assertRaises(TimeoutError):
            bridge["emit_gesture"](send, points, pause=lambda _delay: None)
        self.assertEqual(0, send.call_args.args[-1])
        self.assertEqual(2, send.call_count)

    def test_gestures_stay_inside_portrait_and_landscape_display_and_release(self):
        for width, height in ((1080, 2400), (2400, 1080)):
            for action in bridge["ACTIONS"]:
                with self.subTest(action=action, width=width):
                    points = gesture_points(action, width, height)
                    self.assertEqual(0, points[-1][2])
                    for x, y, button, delay in points:
                        self.assertTrue(0 <= x < width and 0 <= y < height)
                        self.assertIn(button, (0, 1))
                        self.assertTrue(0 <= delay <= 0.1)
            self.assertLess(gesture_points("swipe-right", width, height)[0][0],
                            gesture_points("swipe-right", width, height)[-1][0])
            self.assertGreater(gesture_points("swipe-left", width, height)[0][0],
                               gesture_points("swipe-left", width, height)[-1][0])


if __name__ == "__main__":
    unittest.main()

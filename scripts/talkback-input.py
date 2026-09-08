#!/usr/bin/env python3
"""Send one real TalkBack gesture through an emulator's hardware touch input."""

import argparse
import re
import signal
import subprocess
import time

ACTIONS = frozenset(("swipe-right", "swipe-left", "double-tap"))


def parse_arguments(arguments=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--action", required=True, choices=sorted(ACTIONS))
    parser.add_argument("--width", required=True, type=int)
    parser.add_argument("--height", required=True, type=int)
    parser.add_argument("--rotation", required=True, type=int, choices=range(4))
    args = parser.parse_args(arguments)
    if not re.fullmatch(r"emulator-[0-9]+", args.serial):
        parser.error("Only an explicit emulator serial is supported")
    if not 320 <= args.width <= 8192 or not 320 <= args.height <= 8192:
        parser.error("Display dimensions must be 320..8192 physical pixels")
    return args


def natural_coordinates(x, y, width, height, rotation):
    if rotation == 0:
        return x, y
    if rotation == 1:
        return height - 1 - y, x
    if rotation == 2:
        return width - 1 - x, height - 1 - y
    if rotation == 3:
        return y, width - 1 - x
    raise ValueError("Unsupported display rotation")


def gesture_points(action, width, height):
    if action not in ACTIONS:
        raise ValueError("Unsupported gesture")
    if action == "double-tap":
        x, y = width // 2, height // 2
        return [(x, y, 1, 0.04), (x, y, 0, 0.07),
                (x, y, 1, 0.04), (x, y, 0, 0.0)]
    start, end = (0.3, 0.7) if action == "swipe-right" else (0.7, 0.3)
    points = [(int(width * (start + (end - start) * step / 5)), int(height * 0.55), 1, 0.025)
              for step in range(6)]
    points.append((int(width * end), int(height * 0.55), 0, 0.0))
    return points


def emit_gesture(send, points, pause=time.sleep):
    pressed = False
    try:
        for x, y, button, delay in points:
            if button == 1:
                pressed = True
            send(x, y, button)
            pressed = button == 1
            pause(delay)
    finally:
        if pressed:
            x, y, _, _ = points[-1]
            send(x, y, 0)


def interrupted(_signal, _frame):
    raise KeyboardInterrupt


def main():
    args = parse_arguments()
    signal.signal(signal.SIGTERM, interrupted)
    adb = [args.adb, "-s", args.serial]

    def send(x, y, button):
        x, y = natural_coordinates(x, y, args.width, args.height, args.rotation)
        result = subprocess.run(
            adb + ["emu", "event", "mouse", str(x), str(y), "0", str(button)],
            capture_output=True, text=True, timeout=5, check=True,
        )
        if any(line.startswith("KO") for line in result.stdout.splitlines()):
            raise RuntimeError(f"Emulator rejected hardware input: {result.stdout.strip()}")

    emit_gesture(send, gesture_points(args.action, args.width, args.height))
    print(f"{args.action} {args.width}x{args.height} rotation={args.rotation}", flush=True)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        raise SystemExit(130) from None

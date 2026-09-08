#!/usr/bin/env python3
"""Bridge the opt-in TalkBack proof's bounded requests to emulator hardware input."""

import argparse
import re
import signal
import subprocess
import time
import uuid

ACTIONS = frozenset(("swipe-right", "swipe-left", "double-tap"))


def parse_request(text):
    fields = text.split()
    if len(fields) != 5:
        raise ValueError("Expected UUID, gesture, width, height and rotation")
    request_id, action, width_text, height_text, rotation_text = fields
    if str(uuid.UUID(request_id)) != request_id or action not in ACTIONS:
        raise ValueError("Invalid request UUID or gesture")
    width, height = int(width_text), int(height_text)
    if not 320 <= width <= 8192 or not 320 <= height <= 8192:
        raise ValueError("Unsupported display dimensions")
    rotation = int(rotation_text)
    if rotation not in (0, 1, 2, 3):
        raise ValueError("Unsupported display rotation")
    return request_id, action, width, height, rotation


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
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--run-id", required=True, type=uuid.UUID)
    parser.add_argument("--timeout", type=int, default=240)
    args = parser.parse_args()
    signal.signal(signal.SIGTERM, interrupted)
    if not re.fullmatch(r"emulator-[0-9]+", args.serial):
        parser.error("Only an explicit emulator serial is supported")
    if not 1 <= args.timeout <= 600:
        parser.error("Timeout must be 1..600 seconds")
    adb = [args.adb, "-s", args.serial]
    directory = ("/sdcard/Android/data/io.put.putio.mobile.debug/files/"
                 f"accessibility-proof-{args.run_id}")
    request_path = f"{directory}/gesture-request.txt"
    deadline = time.monotonic() + args.timeout
    handled = set()

    def command(*arguments, check=True):
        return subprocess.run(adb + list(arguments), capture_output=True, text=True,
                              timeout=5, check=check)

    while time.monotonic() < deadline:
        result = command("shell", "cat", request_path, check=False)
        if result.returncode:
            if "No such file" not in result.stderr:
                raise RuntimeError(f"Cannot read gesture request: {result.stderr.strip()}")
            time.sleep(0.1)
            continue
        request_id, action, width, height, rotation = parse_request(result.stdout)
        if request_id in handled:
            time.sleep(0.1)
            continue
        acknowledged = f"{directory}/gesture-{request_id}.done"
        if command("shell", "test", "-f", acknowledged, check=False).returncode == 0:
            handled.add(request_id)
            continue
        points = gesture_points(action, width, height)
        def send(x, y, button):
            x, y = natural_coordinates(x, y, width, height, rotation)
            result = command("emu", "event", "mouse", str(x), str(y), "0", str(button))
            if any(line.startswith("KO") for line in result.stdout.splitlines()):
                raise RuntimeError(f"Emulator rejected hardware input: {result.stdout.strip()}")

        emit_gesture(send, points)
        command("shell", "touch", acknowledged)
        handled.add(request_id)
        print(f"{request_id} {action} {width}x{height} rotation={rotation}", flush=True)
    print(f"Bridge timeout reached after {len(handled)} gestures", flush=True)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass

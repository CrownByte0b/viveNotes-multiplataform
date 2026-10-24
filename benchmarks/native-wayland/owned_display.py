#!/usr/bin/env python3
"""Run a command on a new private Xvfb/Weston session, never on the user's desktop."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import time


def run(output, command):
    output = output.resolve()
    output.mkdir(mode=0o700)
    socket_dir = output / "wayland"
    socket_dir.mkdir(mode=0o700)
    env = {key: value for key, value in os.environ.items() if key not in (
        "DISPLAY", "WAYLAND_DISPLAY", "WAYLAND_SOCKET", "XAUTHORITY", "DBUS_SESSION_BUS_ADDRESS",
        "HYPRLAND_INSTANCE_SIGNATURE", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS")}
    env.update(XDG_RUNTIME_DIR=str(socket_dir), WAYLAND_DISPLAY="vive-probe", XDG_SESSION_TYPE="wayland")
    processes = []
    try:
        with (output / "display.txt").open("w") as display, (output / "xvfb.log").open("w") as log:
            process = subprocess.Popen(["Xvfb", "-displayfd", str(display.fileno()), "-screen", "0", "1600x1000x24", "-nolisten", "tcp"],
                                       pass_fds=(display.fileno(),), env=env, stdout=log, stderr=subprocess.STDOUT)
        processes.append(process)
        wait_for(lambda: (output / "display.txt").read_text().strip(), process)
        env["DISPLAY"] = ":" + (output / "display.txt").read_text().strip()
        with (output / "weston.log").open("w") as log:
            process = subprocess.Popen(["weston", "--backend=x11", "--renderer=pixman", "--width=1440", "--height=1000",
                                        "--socket=vive-probe", "--idle-time=0", "--no-config"], env=env, stdout=log, stderr=subprocess.STDOUT)
        processes.append(process)
        wait_for(lambda: (socket_dir / "vive-probe").is_socket(), process)
        (output / "command.json").write_text(json.dumps(command, indent=2) + "\n")
        with (output / "command.log").open("w") as log:
            process = subprocess.Popen(command, env=env, stdout=log, stderr=subprocess.STDOUT)
            processes.append(process)
            result = process.wait(timeout=600)
        if result:
            raise RuntimeError(f"Owned command failed ({result}); see {output / 'command.log'}")
    finally:
        for process in reversed(processes):
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)


def wait_for(ready, process):
    for _ in range(100):
        if ready(): return
        if process.poll() is not None: raise RuntimeError("Owned display exited during startup")
        time.sleep(.1)
    raise TimeoutError("Owned display startup timed out")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    if not command: parser.error("Supply a command after --")
    run(args.output, command)

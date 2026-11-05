#!/usr/bin/env python3
"""Run checks on an owned Xvfb or headless Weston display and clean up its processes."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import time


def ready(predicate, process):
    deadline = time.monotonic() + 15
    while not predicate():
        if process.poll() is not None:
            raise RuntimeError("Private display exited; inspect its log")
        if time.monotonic() >= deadline:
            raise TimeoutError("Private display did not become ready")
        time.sleep(.05)


def run(args, command):
    output = args.output.resolve()
    output.mkdir(mode=0o700)
    sockets = output / "runtime"
    sockets.mkdir(mode=0o700)
    env = {k: v for k, v in os.environ.items() if k not in (
        "DISPLAY", "WAYLAND_DISPLAY", "WAYLAND_SOCKET", "XAUTHORITY", "DBUS_SESSION_BUS_ADDRESS",
        "HYPRLAND_INSTANCE_SIGNATURE", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS")}
    env["XDG_RUNTIME_DIR"] = str(sockets)
    processes = []
    try:
        with (output / "display.log").open("w") as log:
            if args.mode == "x11":
                with (output / "display-number").open("w") as number:
                    server = subprocess.Popen([str(args.xvfb), "-displayfd", str(number.fileno()),
                        "-screen", "0", "1920x1200x24", "-nolisten", "tcp"],
                        env=env, pass_fds=(number.fileno(),), stdout=log, stderr=subprocess.STDOUT)
                processes.append(server)
                ready(lambda: bool((output / "display-number").read_text().strip()), server)
                env.update(DISPLAY=":" + (output / "display-number").read_text().strip(), XDG_SESSION_TYPE="x11")
            else:
                server = subprocess.Popen(["weston", "--backend=headless", "--renderer=gl", "--socket=egl-tests",
                    "--width=1920", "--height=1200", "--scale=" + str(args.scale),
                    "--fake-seat", "--no-config", "--idle-time=0", "--debug"], env=env, stdout=log, stderr=subprocess.STDOUT)
                processes.append(server)
                ready(lambda: (sockets / "egl-tests").is_socket(), server)
                env.update(WAYLAND_DISPLAY="egl-tests", XDG_SESSION_TYPE="wayland",
                    VIVE_JBR_HOME=str(args.jbr.resolve()), VIVE_WAYLAND_SCREENSHOTS=str(output / "screenshots"))
            (output / "command.json").write_text(json.dumps({"command": command, "displayMode": args.mode,
                "scale": args.scale, "display": env.get("DISPLAY"), "waylandDisplay": env.get("WAYLAND_DISPLAY")}, indent=2) + "\n")
            with (output / "checks.log").open("w") as checks:
                child = subprocess.Popen(command, env=env, stdout=checks, stderr=subprocess.STDOUT)
                processes.append(child)
                result = child.wait(timeout=1800)
            (output / "exit-code").write_text(str(result) + "\n")
            if result:
                raise RuntimeError(f"Checks failed ({result}); see {output / 'checks.log'}")
    finally:
        for process in reversed(processes):
            if process.poll() is None:
                process.terminate()
                try: process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--mode", choices=("x11", "wayland"), required=True)
    parser.add_argument("--scale", type=int, choices=(1, 2), default=1)
    parser.add_argument("--xvfb", type=Path, default=Path("Xvfb"))
    parser.add_argument("--jbr", type=Path)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    if not command or args.mode == "wayland" and args.jbr is None:
        parser.error("Supply a command after --, and --jbr for native Wayland checks")
    run(args, command)

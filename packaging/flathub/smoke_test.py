#!/usr/bin/env python3
"""Opens and closes the Flathub build's UI with disposable sandbox storage, as the installed app runs.

Installs build/flathub-dev/net.vivenotes.ViveNotes.flatpak (build_local.py --bundle) into a
throwaway installation and home, then runs FlathubSmoke with the launcher's own JVM options:

    xvfb-run -a python3 -B packaging/flathub/smoke_test.py
    WAYLAND_DISPLAY=<nested compositor> python3 -B packaging/flathub/smoke_test.py --wayland
"""
import argparse
import os
import shlex
import subprocess
import tempfile
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
APP_ID = "net.vivenotes.ViveNotes"


def launcher_options():
    """The JVM options packaging/flathub/vivenotes passes, so the probe runs the app as installed."""
    words = shlex.split((HERE / "vivenotes").read_text().replace("\\\n", " "), comments=True)
    java = words.index("/app/jbr/bin/java")
    return [word for word in words[java + 1:words.index("-cp")]]


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--wayland", action="store_true", help="Use the current Wayland display instead of X11")
    args = parser.parse_args()
    work = ROOT / "build/flathub-dev"
    manifest = yaml.safe_load((HERE / f"{APP_ID}.yml").read_text())
    env = os.environ.copy()
    runtime = subprocess.check_output(["flatpak", "info", "--show-location",
        f"{manifest['runtime']}//{manifest['runtime-version']}"], text=True).strip()
    # Expose the already installed runtime read-only as a system installation to the isolated user.
    env["FLATPAK_SYSTEM_DIR"] = str(Path(runtime).parents[4])
    env["FLATPAK_USER_DIR"] = str(work / "test-installation")
    env["HOME"] = str(work / "test-home")
    Path(env["HOME"]).mkdir(exist_ok=True)
    subprocess.run(["flatpak", "install", "--user", "--noninteractive", "--no-deps", "--reinstall",
                    str(work / f"{APP_ID}.flatpak")], env=env, check=True)
    with tempfile.TemporaryDirectory(prefix="probe-", dir=work) as folder:
        subprocess.run(["javac", "--release", "25", "-cp", str(work / "app/files/lib/*"),
                        "-d", folder, str(HERE / "FlathubSmoke.java")], check=True)
        command = ["flatpak", "run", "--user", "--command=/app/jbr/bin/java", f"--filesystem={folder}:ro"]
        if args.wayland:
            command += ["--env=XDG_SESSION_TYPE=wayland", "--env=WAYLAND_DISPLAY=" + os.environ["WAYLAND_DISPLAY"]]
        else:
            command += ["--nosocket=wayland", "--nosocket=fallback-x11", "--socket=x11",
                        "--unset-env=WAYLAND_DISPLAY", "--env=XDG_SESSION_TYPE=x11"]
        command += [APP_ID, *launcher_options(), "-Dskiko.renderApi=SOFTWARE",
                    "-cp", f"/app/lib/*:{folder}", "FlathubSmoke", "--debug"]
        if not args.wayland:
            command.append("--x11")
        log = work / f"smoke-test-{'wayland' if args.wayland else 'x11'}.log"
        with log.open("w") as stream:
            result = subprocess.run(command, env=env, stdout=stream, stderr=subprocess.STDOUT, timeout=90)
        text = log.read_text()
        print(text)
        for marker in ["FLATHUB_SMOKE_PYTHON", "FLATHUB_SMOKE_INK /app/lib/libink.so", "FLATHUB_SMOKE_WINDOW_OK", "[app] closed"]:
            if marker not in text:
                raise SystemExit(f"Packaged UI smoke test failed: no {marker}")
        if result.returncode or "Exception" in text or "Error:" in text:
            raise SystemExit("Packaged UI reported an error")


if __name__ == "__main__":
    main()

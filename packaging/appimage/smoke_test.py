#!/usr/bin/env python3
"""Launch the built AppImage through its runtime with disposable storage, then close it again."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
MARKER = "APPIMAGE_SMOKE_WINDOW_OK"


def agent(folder):
    subprocess.run(["javac", "--release", "25", "-d", folder, HERE / "AppImageSmoke.java"], check=True)
    jar = folder / "appimage-smoke.jar"
    with zipfile.ZipFile(jar, "w") as output:
        output.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nPremain-Class: AppImageSmoke\n\n")
        for compiled in folder.glob("*.class"):
            output.write(compiled, compiled.name)
    return jar


def environment(home, jar, wayland):
    inherited = {"PATH", "LANG", "LC_ALL", "XDG_RUNTIME_DIR", "DBUS_SESSION_BUS_ADDRESS"}
    inherited |= {"WAYLAND_DISPLAY"} if wayland else {"DISPLAY", "XAUTHORITY"}
    env = {name: value for name, value in os.environ.items() if name in inherited}
    env |= {"HOME": str(home), "XDG_CONFIG_HOME": str(home / ".config"), "XDG_DATA_HOME": str(home / ".local/share"),
            "XDG_CACHE_HOME": str(home / ".cache"), "XDG_STATE_HOME": str(home / ".local/state"),
            "XDG_SESSION_TYPE": "wayland" if wayland else "x11",
            "JAVA_TOOL_OPTIONS": f"-javaagent:{jar} -Dskiko.renderApi=SOFTWARE"}
    if ("WAYLAND_DISPLAY" if wayland else "DISPLAY") not in env:
        raise SystemExit("No display for the selected window system")
    return env


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--wayland", action="store_true", help="Use the current Wayland display instead of X11")
    args = parser.parse_args()
    output = ROOT / "desktopApp/build/appimage"
    images = list(output.glob("ViveNotes-*-x86_64.AppImage"))
    if len(images) != 1:
        raise SystemExit("Expected one built AppImage")
    home = output / "test-home"
    if home.exists():
        shutil.rmtree(home)
    # This test checks the UI and SQLite without triggering a Python or model download.
    (home / ".local/share/vivenotes/math_engine").mkdir(parents=True)
    (home / ".local/share/vivenotes/math_engine/removed").write_text("")
    (home / ".config/vivenotes").mkdir(parents=True)
    (home / ".config/vivenotes/ai.properties").write_text("deletedFormulaModels=UniMerNetSmall\n")
    with tempfile.TemporaryDirectory(prefix="probe-", dir=output) as folder:
        env = environment(home, agent(Path(folder)), args.wayland)
        command = [str(images[0]), "--debug"] + ([] if args.wayland else ["--x11"])
        log = output / "smoke-test.log"
        with log.open("w") as stream:
            result = subprocess.run(command, env=env, cwd=home, stdout=stream, stderr=subprocess.STDOUT, timeout=90)
    text = log.read_text()
    print(text)
    marker = next((line.split() for line in text.splitlines() if line.startswith(MARKER + " ")), None)
    if result.returncode or not marker or "[app] closed" not in text:
        raise SystemExit("AppImage UI smoke test failed")
    if "Exception" in text or "Error:" in text:
        raise SystemExit("AppImage reported an error")
    mounts = Path("/proc/self/mounts").read_text().split()
    if marker[2] in mounts:
        raise SystemExit(f"The AppImage runtime left {marker[2]} mounted")


if __name__ == "__main__":
    main()

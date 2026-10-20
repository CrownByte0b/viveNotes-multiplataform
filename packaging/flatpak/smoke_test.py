#!/usr/bin/env python3
"""Open and close the packaged UI with disposable sandbox storage and no host filesystem access."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--wayland", action="store_true", help="Use the current Wayland display instead of X11")
    args = parser.parse_args()
    output = ROOT / "desktopApp/build/flatpak"
    manifest = json.loads((HERE / "net.vivenotes.ViveNotes.json").read_text())
    env = os.environ.copy()
    runtime = subprocess.check_output(["flatpak", "info", "--show-location",
        manifest["runtime"] + "//" + manifest["runtime-version"]], text=True).strip()
    # Expose the already installed runtime read-only as a system installation to the isolated user.
    env["FLATPAK_SYSTEM_DIR"] = str(Path(runtime).parents[4])
    env["FLATPAK_USER_DIR"] = str(output / "test-installation")
    env["HOME"] = str(output / "test-home")
    Path(env["HOME"]).mkdir(exist_ok=True)
    bundles = list(output.glob("ViveNotes-*-x86_64.flatpak"))
    if len(bundles) != 1:
        raise SystemExit("Expected one built .flatpak bundle")
    subprocess.run(["flatpak", "install", "--user", "--noninteractive", "--no-deps", "--reinstall",
                    str(bundles[0])], env=env, check=True)
    with tempfile.TemporaryDirectory(prefix="probe-", dir=output) as folder:
        subprocess.run(["javac", "--release", "25", "-cp", str(output / "payload/lib/*"),
                        "-d", folder, str(HERE / "FlatpakSmoke.java")], check=True)
        command = ["flatpak", "run", "--user", "--command=/app/jbr/bin/java", f"--filesystem={folder}:ro",
                   "--env=VIVE_UV=/app/bin/uv"]
        if args.wayland:
            command += ["--env=XDG_SESSION_TYPE=wayland", "--env=WAYLAND_DISPLAY=" + os.environ["WAYLAND_DISPLAY"]]
        else:
            command += ["--nosocket=wayland", "--nosocket=fallback-x11", "--socket=x11", "--unset-env=WAYLAND_DISPLAY", "--env=XDG_SESSION_TYPE=x11"]
        command += [manifest["id"] + "//devel", "--enable-native-access=ALL-UNNAMED",
                    "--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED",
                    "--add-exports=java.desktop/sun.awt=ALL-UNNAMED",
                    "--add-opens=java.desktop/sun.awt.wl=ALL-UNNAMED",
                    "-Dvivenotes.profile=production", "-Dskiko.renderApi=SOFTWARE", "-cp", f"/app/lib/*:{folder}", "FlatpakSmoke", "--debug"]
        if not args.wayland:
            command.append("--x11")
        log = output / "smoke-test.log"
        with log.open("w") as stream:
            result = subprocess.run(command, env=env, stdout=stream, stderr=subprocess.STDOUT, timeout=75)
        text = log.read_text()
        print(text)
        if result.returncode or "FLATPAK_SMOKE_WINDOW_OK" not in text or "[app] closed" not in text:
            raise SystemExit("Packaged UI smoke test failed")
        if "Exception" in text or "Error:" in text:
            raise SystemExit("Packaged UI reported an error")


if __name__ == "__main__":
    main()

# Linux Flatpak packaging

The first package targets Linux x86_64 with ID `net.vivenotes.ViveNotes`, Freedesktop
26.08 and bundled JBR 25.0.4.1. `build.py` verifies SHA-256 checksums for JBR and uv;
no host Java or uv installation is required by the installed app. Development bundles
use the `devel` branch. This is a local/CI distribution path, not a Flathub submission.

## Build and test

Install Python 3.12+, the project's build JDK 27, Flatpak, ImageMagick (6 or 7),
`appstreamcli` and `desktop-file-validate`. Gradle must be able to resolve ByteInk
through local Maven or authenticated GitHub Packages, as for normal desktop builds.

```sh
flatpak remote-add --user --if-not-exists flathub https://dl.flathub.org/repo/flathub.flatpakrepo
flatpak install --user flathub org.freedesktop.Platform//26.08
xvfb-run -a ./gradlew lint :shared:jvmTest :desktopApp:test --continue
python3 -B packaging/flatpak/build.py
xvfb-run -a python3 -B packaging/flatpak/smoke_test.py
```

Use `--jbr-archive FILE --uv-archive FILE` for already downloaded copies of the pinned
archives; they are still checksum verified. `--version 0.1.1` overrides the Gradle
version for both jars and metadata. `SOURCE_DATE_EPOCH` overrides the metadata date;
otherwise it uses the source commit's date. Builds use current resolved dependencies;
the jar inventory records their hashes, but this does not guarantee bit-identical builds.

Output is under `desktopApp/build/flatpak/`: the `.flatpak` bundle, local OSTree
`repo`, staged `payload`, build directory `app`, and a builder JSON manifest with
relative local sources. `--stage-only` stops before export. The generated manifest can
also be built with `flatpak-builder` and `org.freedesktop.Sdk//26.08` installed.
Do not run the source manifest in `packaging/flatpak` directly: its payload is generated.

```sh
flatpak install --user desktopApp/build/flatpak/ViveNotes-0.1.0-x86_64.flatpak
flatpak run net.vivenotes.ViveNotes
```

Native Wayland is selected automatically. On Wayland, an explicit X11 fallback needs
`flatpak run --nosocket=fallback-x11 --socket=x11 net.vivenotes.ViveNotes --x11`.
Pass `--debug` for the app's diagnostics. To test Wayland against a disposable nested
compositor, point `WAYLAND_DISPLAY` at that compositor and run
`python3 -B packaging/flatpak/smoke_test.py --wayland`.
The smoke probe is compiled separately and is never included in the package. It installs the
bundle into `test-installation` and uses `test-home` beneath the build output, leaving the
normal user installation and notes alone. The existing runtime is reused read-only.

The GitHub **Linux Flatpak** workflow runs manually and on `v*` tags. It runs JVM tests,
lint and the sandbox smoke test, then uploads a downloadable Actions artifact. It does
not upload a public release, publish an update repository or submit to Flathub.

## Sandbox behavior

Network supports sync, image search and optional model/Python downloads. GPU rendering,
Wayland and fallback X11 are enabled. No home/host filesystem, full D-Bus, raw input,
camera, microphone or host-command permissions are granted. Camera capture currently
requires direct V4L2 access, so it is unavailable in this package; a Camera/PipeWire portal
adapter remains release work.

Picture insertion, notebook import/export and PDF export use the runtime's Zenity
`GtkFileChooserNative` to request files through the desktop portal. The portal owns the
last-used folder and overwrite confirmation. Saves preserve the exact granted filename,
including if a user removes the suggested extension. The file extension is suggested,
not appended after authorization. Other desktop installations retain their AWT dialogs.

Flatpak supplies private XDG directories under `~/.var/app/net.vivenotes.ViveNotes/`;
Vive Notes uses a `vivenotes` subdirectory within data/config/cache. Existing native or
development notes are not migrated automatically. Export/import `.vive` files to transfer.
JBR's license/legal directory, project license, font licenses, uv licenses, AI notice,
and notices embedded in dependency jars are included. Formula models and the Python math
environment retain the app's existing optional download behavior.

## Plan for public release and Flathub

1. Exercise file portals on GNOME and KDE, including import/export, picture insertion,
   PDF export, cancellation, overwrite and restart. Exercise pen/touch and long sessions
   on native Wayland and X11. Add the Camera portal adapter when camera support ships.
2. Finish the third-party redistribution audit, including ByteInk/native libraries,
   uv dependencies, bundled OCR and the downloaded CPython/SymPy/model notices. Embedded
   jar notices plus an inventory are a starting point, not proof that every notice is present.
3. Choose a stable release/version, supply actual hosted screenshots and complete
   store metadata. The current Source First license is described as a custom proprietary
   license in AppStream; no open-source relicensing is implied. Confirm domain control
   for `vivenotes.net` before submission/verification.
4. For Flathub, replace the generated local binary-payload source with a pinned public
   source commit and an offline dependency manifest. ByteInk's authenticated GitHub
   Packages artifacts are not usable by Flathub's public offline builders. Provide
   publicly downloadable pinned dependencies or a reproducible ByteInk source build.
   Follow Flathub's source-build requirements for the app and bundled runtimes/tools.
5. Run current Flathub manifest/AppStream/build lint, resolve findings, submit the reviewed
   manifest and maintain it. For direct distribution, host and sign an OSTree repository
   and publish a `.flatpakref` so users receive updates. A single-file bundle alone does
   not establish an automatic update channel. Add aarch64 only after native dependencies
   and the packaged app have been verified there.

Official references, consulted 2026-10-20:

- [Build a Flatpak](https://docs.flatpak.org/en/latest/first-build.html)
- [Sandbox permissions](https://docs.flatpak.org/en/latest/sandbox-permissions.html)
- [Desktop integration and portals](https://docs.flatpak.org/en/latest/desktop-integration.html)
- [Single-file bundles](https://docs.flatpak.org/en/latest/single-file-bundles.html)
- [Flathub requirements](https://docs.flathub.org/docs/for-app-authors/requirements)
- [Submission](https://docs.flathub.org/docs/for-app-authors/submission)
- [File chooser portal](https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.FileChooser.html)
- [JetBrains Runtime releases](https://github.com/JetBrains/JetBrainsRuntime/releases)
- [uv 0.12.19](https://github.com/astral-sh/uv/releases/tag/0.12.19)

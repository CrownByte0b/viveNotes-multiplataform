# Linux AppImage packaging

A single-file Linux x86_64 package, `ViveNotes-<version>-x86_64.AppImage`, with the same
checksum-verified JBR 25 and uv as the Flatpak (`packaging/flatpak/build.py` pins both). It runs
on the host, not in a sandbox: notes live in the usual XDG directories, file dialogs use the host
GTK, and the window class is the app's own `vivenotes` (the desktop entry's `StartupWMClass`).

## Build and test

Install Python 3.12+, the project's build JDK 27, `squashfs-tools` 4.4+, `appstreamcli`,
`desktop-file-validate`, and FUSE 3 (`fusermount3`) to run the result. ByteInk must resolve through
local Maven or authenticated GitHub Packages, as for normal desktop builds.

```sh
xvfb-run -a ./gradlew lint :shared:jvmTest :desktopApp:test --continue
python3 -B packaging/appimage/build.py
xvfb-run -a python3 -B packaging/appimage/smoke_test.py
```

`build.py` stages `desktopApp/build/appimage/AppDir` (Gradle's `:desktopApp:stageAppImage` jars,
JBR, uv, `AppRun`, desktop entry, icons, MetaInfo and licence notices). It then writes the pinned
[type 2 runtime](https://github.com/AppImage/type2-runtime), with the update information below in
its `.upd_info` section, followed by a root-owned, xattr-free
zstd SquashFS of that directory. This is the image `appimagetool -u` would produce, without running a
downloaded tool. A `.sha256` file is written next to it. `--jbr-archive`, `--uv-archive` and
`--runtime-file` take local copies of the pinned downloads, which are still checksum verified.
`--version` and `SOURCE_DATE_EPOCH` behave as for the Flatpak. `--stage-only` stops after the AppDir.

The smoke test runs the real image through its runtime with a disposable `test-home`, kept offline
by the same seeded math/model markers as the Flatpak smoke test. A Java agent compiled from
`AppImageSmoke.java` and attached through `JAVA_TOOL_OPTIONS` checks the bundled JBR and uv, the
window class and the database, then closes the window. The agent is never packaged. Pass `--wayland`
to use the current `WAYLAND_DISPLAY` (point it at a disposable nested compositor) instead of X11.

The GitHub **Linux AppImage** workflow runs manually and on `v*` tags: JVM tests, lint, build, the
X11 smoke test, then an Actions artifact with the AppImage and its checksum. **Attach release
artifacts** copies both into a manually saved release draft, as it does for the MSI and Flatpak.

## Updates

The image carries the update information
`gh-releases-zsync|CrownByte0b|viveNotes-multiplataform|latest|ViveNotes-*-x86_64.AppImage.zsync`
(`UPDATE_INFORMATION` in `build.py`; print it with `--appimage-updateinfo`, which the smoke test
checks). AppImageUpdate and other tools that follow the AppImage update specification read it,
look in the newest published GitHub release for the asset matching that name, and download only
the blocks that differ from the image they already have.

That asset is `ViveNotes-<version>-x86_64.AppImage.zsync`. **Attach release artifacts** generates
it with `zsyncmake` from the tested image and uploads it beside the image, so a release published
without running that workflow offers these tools nothing to update to. The app itself does not
check for AppImage updates. There is no signature yet.

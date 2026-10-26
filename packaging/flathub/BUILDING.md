# Flathub source build

`net.vivenotes.ViveNotes.yml` builds Vive Notes for Flathub from source, offline, on Freedesktop
26.08. `packaging/flatpak/` remains the local bundle (prebuilt JBR and uv); its desktop entry,
MetaInfo, icon and licence notices are reused here.

| Module | Built from | Notes |
| --- | --- | --- |
| `jbr` | JetBrains Runtime `jbr-release-25.0.4.1b635.75` | Same flags as JetBrains' `mkimages_x64.sh` "jbr" bundle; boot JDK from the openjdk25 extension. Keeps native Wayland. |
| `math-python` | sympy 1.14.0, mpmath 1.3.0, antlr4 4.11.1 wheels from `uv.lock` | A venv of the runtime's Python 3.14. The app runs it through `-Dvivenotes.mathPython`; nothing is downloaded at runtime. |
| `libink` | google/ink at ByteInk's pin, abseil, protobuf, zlib, libtess2 | ByteInk's `native/cmake` build with its `native/patches`; see ByteInk's `memory/cmake-build.md`. |
| `vivenotes` | This repository and ByteInk (composite build) | Gradle 9.8.2 `--offline` on the openjdk extension's JDK 27, every artifact from `maven-sources.json`. |

Before submitting, replace the placeholders in the manifest:

- `VIVENOTES_RELEASE_COMMIT` and `BYTEINK_RELEASE_COMMIT`: pushed commits that contain this
  directory and ByteInk's `native/cmake` and `byteinkBundledPlatforms`.
- `GOOGLE_DESKTOP_CLIENT_ID`: the Desktop OAuth client id (public). Its secret stays on the sync
  server (`GOOGLE_DESKTOP_CLIENT_SECRET`), which exchanges the sign-in code; deploy that first.

## Build and test locally

```sh
python3 -B packaging/flathub/test_flathub.py
python3 -B packaging/flathub/test_generate_maven_sources.py
python3 -B packaging/flathub/build_local.py --bundle
xvfb-run -a python3 -B packaging/flathub/smoke_test.py
WAYLAND_DISPLAY=<nested compositor> python3 -B packaging/flathub/smoke_test.py --wayland
```

`build_local.py` packs both working trees (tracked and new, non-ignored files) in place of the
manifest's git sources, fills the client id from `local.properties`, and builds with
org.flatpak.Builder into `build/flathub-dev/`. JBR takes the longest; finished modules are cached.

## Regenerate `maven-sources.json`

After changing dependencies or the Gradle command, run that command once online with an empty
Gradle home, then list what it downloaded:

```sh
GRADLE_USER_HOME=build/flathub-dev/gradle-home ./gradlew --no-configuration-cache \
  -PbyteinkCompositePath=../byteink -PbyteinkBundledPlatforms=linux-x86_64 \
  -PbyteinkLinuxLibrary=<a built libink.so> :desktopApp:stageFlatpak
python3 -B packaging/flathub/generate_maven_sources.py build/flathub-dev/gradle-home > packaging/flathub/maven-sources.json
```

The empty Gradle home needs a JDK 27 for the daemon (`org.gradle.java.installations.paths` in its
`gradle.properties`).

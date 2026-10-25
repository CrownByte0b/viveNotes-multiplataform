# Windows x64 MSI

Build on Windows 10/11 x64 with PowerShell 7, an Azul Zulu JDK 27, and
[WiX Toolset 3.14.1](https://github.com/wixtoolset/wix3/releases/tag/wix3141rtm)
(`candle.exe` and `light.exe` on PATH). Java is bundled; users do not install it separately.
ByteInk must be available in local Maven or through the authenticated GitHub Packages
repository configured by `settings.gradle.kts`.

```powershell
./gradlew.bat lint :shared:jvmTest :desktopApp:test --continue
./packaging/windows/build.ps1
./packaging/windows/smoke-test.ps1
```

Or set `WIX_PATH` to WiX's bin directory and run `./gradlew.bat :desktopApp:packageReleaseMsi`
directly. The wrapper sets this automatically so Compose uses the installed WiX version.
It copies the
installer to `desktopApp/build/windows/ViveNotes-0.1.0-x64.msi` with a SHA-256 file.
Use `build.ps1 -Version 0.1.1` for another version. MSI accepts only numeric
`MAJOR.MINOR.BUILD`, bounded by `255.255.65535`; prerelease tags are rejected.
Clean older MSI files from that output directory or pass `smoke-test.ps1 -Msi <path>`.

The installer uses the existing app artwork, installs per user, creates Start menu and
desktop shortcuts, and offers a directory chooser. Keep the configured upgrade UUID
constant across releases and increase the numeric version for upgrades. User notes,
settings, downloaded models and Python live in `%APPDATA%/ViveNotes`, separately from
installed files, and survive uninstall. The installer does not register `.vive` files:
open them through File → Import Notebook.

The production profile talks to the production sync endpoint. Desktop Google OAuth still
needs its existing build properties configured to enable Google sign-in. This packaging
does not implement the pending Windows camera/touch adapters.

Gradle downloads checksum-pinned uv 0.12.19 and includes it as an app resource; the app
finds this copy before searching PATH. First run still downloads the pinned CPython/SymPy
environment and UniMERNet Small in the background, unless the user previously removed
them. OCR assets and math sources/lockfiles are bundled in the existing shared jar.
Dependency notices and a jar hash inventory are included under `app/resources/licenses`;
Java's own notices remain in its runtime. These collected notices are not a complete
redistribution audit.

`.github/workflows/windows-msi.yml` builds on `windows-2025` manually, for `v*` tags,
and on pushes changing the Windows packaging or its Gradle configuration.
It pins published ByteInk `0.1.0-alpha.1`, runs the full JVM suite and lint, builds the MSI,
silently installs it into build-directory storage, launches the actual installed executable
with isolated test data, checks its window/SQLite/clean close, then uninstalls it and checks
that notes remain. Only the isolated smoke profile suppresses first-run downloads.
Download the `ViveNotes-Windows-x64` Actions artifact for the MSI, checksum and smoke logs.
For a `v*` tag, a following `release` job (the only one with `contents: write`) checks that
artifact's checksum and publishes the MSI and `.msi.sha256` as that tag's GitHub Release.
MSI generation requires Windows; Linux Compose tasks skip it rather than cross-compile.

## Updates

The installed app checks GitHub's latest release of the public repository a few seconds
after it opens (anonymously, once per launch; drafts and pre-releases are never offered).
When its tag is a newer `MAJOR.MINOR.BUILD`, a dialog offers it with the release notes:
**Install and restart**, **Later** (asks again next launch) or **Skip this version**
(remembered in `%APPDATA%/ViveNotes/updates.properties` until a newer release appears).
Installing downloads `ViveNotes-<version>-x64.msi` to `%LOCALAPPDATA%/ViveNotes/Cache/updates`,
keeps it only if its size and SHA-256 match the release's `.msi.sha256`, saves and closes
the notes, then a small PowerShell script waits for both launcher processes to exit, runs
`msiexec /i … /passive` with `INSTALLDIR` set to the current install folder and reopens the
app. Its output and the verbose MSI log stay in that folder as `install-update.log` and
`install-msi.log`. Only the installed launcher (`jpackage.app-path`) with the production
profile updates itself; Gradle runs and other platforms never check.

To test an update end to end without publishing, serve a release object shaped like GitHub's
API from this computer and point the installed app at it with the `VIVENOTES_UPDATE_FEED`
environment variable (HTTPS anywhere, plain HTTP only to `localhost`/`127.x`/`[::1]`).

The MSI and launcher are currently unsigned, so the update is verified only against the
checksum published beside it over HTTPS. Authenticode signing and a real two-version
Windows upgrade check remain release work.

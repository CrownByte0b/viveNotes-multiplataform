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

Or run `./gradlew.bat :desktopApp:packageReleaseMsi` directly. The wrapper copies the
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
It does not publish a GitHub Release. MSI generation requires Windows; Linux Compose tasks
skip it rather than cross-compile.

The MSI and launcher are currently unsigned. Authenticode signing and a real Windows
upgrade/manual UI check remain release work; there is no automatic update service.

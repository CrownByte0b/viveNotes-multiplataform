[CmdletBinding()]
param([string]$Msi)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (-not $IsWindows) { throw 'Run the installed MSI smoke test on Windows using PowerShell 7.' }
$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$output = [IO.Path]::GetFullPath("$root/desktopApp/build/windows")
if (-not $Msi) {
    $installers = @(Get-ChildItem "$output/*.msi")
    if ($installers.Count -ne 1) { throw 'Pass -Msi when there is not exactly one installer.' }
    $Msi = $installers[0].FullName
}
$Msi = (Resolve-Path $Msi).Path
$smoke = Join-Path $output 'smoke'
New-Item -ItemType Directory -Force $smoke | Out-Null
# A distinct install location and profile keep this check away from real notes.
$install = Join-Path $smoke 'installed app'
$profile = "$smoke/profile"
$saved = @{}
foreach ($key in @('APPDATA', 'LOCALAPPDATA', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'VIVE_UV')) {
    $saved[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
}
$app = $null
$windowProcess = $null
$ownedProcesses = [Collections.Generic.HashSet[int]]::new()
$installed = $false
try {
    $result = Start-Process msiexec.exe -ArgumentList @('/i', "`"$Msi`"", '/qn', '/norestart',
        "INSTALLDIR=`"$install`"", '/L*v', "`"$smoke/install.log`"") -PassThru -Wait
    if ($result.ExitCode -notin @(0, 3010)) { throw "MSI installation failed ($($result.ExitCode))." }
    $installed = $true
    $exe = Join-Path $install 'Vive Notes.exe'
    if (-not (Test-Path $exe)) { throw "Installed launcher missing: $exe" }
    $uv = "$install/app/resources/bin/uv.exe"
    $uvVersion = & $uv --version
    if ($LASTEXITCODE -ne 0 -or $uvVersion -notmatch '^uv 0\.12\.19\b') { throw 'Bundled uv does not run.' }
    $env:APPDATA = "$profile/Roaming"
    $env:LOCALAPPDATA = "$profile/Local"
    $env:JAVA_TOOL_OPTIONS = '-Dskiko.renderApi=SOFTWARE'
    $env:_JAVA_OPTIONS = $null
    $env:JDK_JAVA_OPTIONS = $null
    $env:VIVE_UV = $null
    $notes = "$env:APPDATA/ViveNotes"
    New-Item -ItemType Directory -Force "$notes/math_engine" | Out-Null
    New-Item -ItemType File -Force "$notes/math_engine/removed" | Out-Null
    'deletedFormulaModels=UniMerNetSmall' | Set-Content "$notes/ai.properties" -Encoding ascii
    # Run the installed launcher, including its generated classpath, options and resource path.
    $app = Start-Process $exe -ArgumentList '--debug' -WorkingDirectory $smoke -PassThru `
        -RedirectStandardOutput "$smoke/app.log" -RedirectStandardError "$smoke/app-error.log"
    [void]$ownedProcesses.Add($app.Id)
    $deadline = (Get-Date).AddSeconds(90)
    do {
        Start-Sleep -Milliseconds 250
        # Current jpackage launchers fork another copy of the EXE to host the JVM.
        # Follow this launch's descendants rather than inspecting the waiting launcher stub.
        $candidates = @(Get-CimInstance Win32_Process -Filter "Name='Vive Notes.exe'")
        do {
            $added = $false
            foreach ($candidate in $candidates) {
                if ($candidate.ExecutablePath -eq $exe -and $ownedProcesses.Contains([int]$candidate.ParentProcessId)) {
                    if ($ownedProcesses.Add([int]$candidate.ProcessId)) { $added = $true }
                }
            }
        } while ($added)
        foreach ($processId in $ownedProcesses) {
            $candidate = Get-Process -Id $processId -ErrorAction SilentlyContinue
            if ($null -ne $candidate -and $candidate.MainWindowHandle -ne 0 -and $candidate.MainWindowTitle -eq 'Vive Notes') {
                $windowProcess = $candidate
                # Retain a handle before the child exits so .NET can read its exit status.
                $null = $windowProcess.Handle
                break
            }
        }
    } until ($null -ne $windowProcess -or (Get-Date) -gt $deadline)
    if ($null -eq $windowProcess) {
        throw 'Installed app did not show the Vive Notes window.'
    }
    Start-Sleep -Seconds 5
    if (-not (Test-Path "$notes/notes.db")) { throw 'Installed app did not create its production SQLite database.' }
    if (-not $windowProcess.CloseMainWindow()) { throw 'Cannot close the installed app window.' }
    if (-not $windowProcess.WaitForExit(30000) -or -not $app.WaitForExit(30000)) { throw 'Installed app did not close cleanly.' }
    if ($windowProcess.ExitCode -ne 0 -or $app.ExitCode -ne 0) {
        throw "Installed app failed (JVM=$($windowProcess.ExitCode), launcher=$($app.ExitCode))."
    }
    $log = (Get-Content "$smoke/app.log", "$smoke/app-error.log" -Raw) -join "`n"
    if ($log -match 'Exception in thread|UnsatisfiedLinkError|NoClassDefFoundError|VerifyError') {
        throw "Installed app reported an error. See $smoke/app-error.log."
    }
    'WINDOWS_MSI_SMOKE_OK' | Set-Content "$smoke/result.log"
} finally {
    foreach ($processId in $ownedProcesses) {
        $remaining = Get-Process -Id $processId -ErrorAction SilentlyContinue
        if ($null -ne $remaining -and -not $remaining.HasExited) { Stop-Process -Id $processId -Force }
    }
    foreach ($key in $saved.Keys) { [Environment]::SetEnvironmentVariable($key, $saved[$key], 'Process') }
    if ($installed) {
        $result = Start-Process msiexec.exe -ArgumentList @('/x', "`"$Msi`"", '/qn', '/norestart',
            '/L*v', "`"$smoke/uninstall.log`"") -PassThru -Wait
        if ($result.ExitCode -notin @(0, 3010)) { throw "MSI uninstall failed ($($result.ExitCode))." }
        if (Test-Path "$install/Vive Notes.exe") { throw 'MSI uninstall left the application installed.' }
        if (-not (Test-Path "$profile/Roaming/ViveNotes/notes.db")) { throw 'Uninstall removed user notes.' }
    }
}

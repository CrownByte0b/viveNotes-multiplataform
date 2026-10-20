[CmdletBinding()]
param([string]$Version)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (-not $IsWindows) { throw 'Build the MSI on Windows x64 using PowerShell 7.' }
if ([System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture -ne 'X64') {
    throw 'The Windows package currently supports x64 only.'
}
$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
if (-not $Version) {
    $Version = (Select-String -Path "$root/gradle.properties" -Pattern '^version=(.+)$').Matches.Groups[1].Value
}
if ($Version -notmatch '^(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,4})$') {
    throw 'MSI versions must be MAJOR.MINOR.BUILD (numeric, without a prerelease suffix).'
}
$parts = $Version.Split('.')
if ([int]$parts[0] -gt 255 -or [int]$parts[1] -gt 255 -or [int]$parts[2] -gt 65535) {
    throw 'MSI version limits are 255.255.65535.'
}
if ($env:WIX) { $env:PATH = "$env:WIX\bin;$env:PATH" }
foreach ($tool in @('candle.exe', 'light.exe')) {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
        throw "Missing $tool. Install WiX Toolset 3.14.1 and add its bin directory to PATH."
    }
}
$env:WIX_PATH = Split-Path (Get-Command candle.exe).Source -Parent
Push-Location $root
try {
    & ./gradlew.bat :desktopApp:packageReleaseMsi "-Pversion=$Version" --console=plain
    if ($LASTEXITCODE -ne 0) { throw "MSI build failed ($LASTEXITCODE)." }
    $source = Join-Path $root "desktopApp/build/compose/binaries/main-release/msi/Vive Notes-$Version.msi"
    if (-not (Test-Path $source)) { throw "MSI output is missing: $source" }
    $output = New-Item -ItemType Directory -Force "$root/desktopApp/build/windows"
    $msi = Join-Path $output.FullName "ViveNotes-$Version-x64.msi"
    Copy-Item $source $msi -Force
    $hash = (Get-FileHash $msi -Algorithm SHA256).Hash.ToLowerInvariant()
    "$hash  $([IO.Path]::GetFileName($msi))" | Set-Content "$msi.sha256" -Encoding ascii
    Write-Host "Built $msi"
} finally {
    Pop-Location
}

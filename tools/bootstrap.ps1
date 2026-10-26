param(
    [string]$ToolsRoot = (Join-Path $PSScriptRoot '../.tools'),
    [switch]$AcceptSdkLicenses
)

# Portable Windows x64 toolchain. Only this PowerShell process is configured.
$ErrorActionPreference = 'Stop'
$ToolsRoot = [IO.Path]::GetFullPath($ToolsRoot)
$projectRoot = Split-Path $PSScriptRoot -Parent
New-Item -ItemType Directory -Force $ToolsRoot | Out-Null

function Get-VerifiedArchive([string]$Url, [string]$Path, [string]$Hash, [string]$Algorithm = 'SHA256') {
    if (Test-Path -LiteralPath $Path) {
        if ((Get-FileHash -LiteralPath $Path -Algorithm $Algorithm).Hash -eq $Hash) { return }
    }
    & curl.exe --fail --location --retry 3 --output $Path $Url
    if ($LASTEXITCODE -ne 0) { throw "Download failed: $Url" }
    if ((Get-FileHash -LiteralPath $Path -Algorithm $Algorithm).Hash -ne $Hash) {
        throw "Checksum mismatch: $Path"
    }
}

# SHA256 from official GitHub asset digests and Gradle distribution checksum;
# Google repository2-1.xml publishes SHA1 for this command-line tools release.
$jdk = Join-Path $ToolsRoot 'jdk-21.0.12.1+1'
$gradle = Join-Path $ToolsRoot 'gradle-8.13'
$sdk = Join-Path $ToolsRoot 'android-sdk'
if (-not (Test-Path "$jdk/bin/java.exe")) {
    Get-VerifiedArchive 'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_x64_windows_hotspot_21.0.12.1_1.zip' "$ToolsRoot/jdk21.zip" 'f9d6e191ab098c0d416e7d588a24420a8621cd2f4720dab2459b8b7b2d2d8b4e'
    Expand-Archive -LiteralPath "$ToolsRoot/jdk21.zip" -DestinationPath $ToolsRoot -Force
}
if (-not (Test-Path "$gradle/bin/gradle.bat")) {
    Get-VerifiedArchive 'https://services.gradle.org/distributions/gradle-8.13-bin.zip' "$ToolsRoot/gradle.zip" '20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78'
    Expand-Archive -LiteralPath "$ToolsRoot/gradle.zip" -DestinationPath $ToolsRoot -Force
}
if (-not (Test-Path "$sdk/cmdline-tools/17.0/bin/sdkmanager.bat")) {
    Get-VerifiedArchive 'https://dl.google.com/android/repository/commandlinetools-win-12700392_latest.zip' "$ToolsRoot/cmdline.zip" '4a0902fb473afb7ce90a0ff6181d997138a15d9c' 'SHA1'
    New-Item -ItemType Directory -Force "$sdk/cmdline-tools" | Out-Null
    Expand-Archive -LiteralPath "$ToolsRoot/cmdline.zip" -DestinationPath "$ToolsRoot/cmdline-extracted" -Force
    $commandSource = [IO.Path]::GetFullPath("$ToolsRoot/cmdline-extracted/cmdline-tools")
    $commandDestination = [IO.Path]::GetFullPath("$sdk/cmdline-tools/17.0")
    $allowedPrefix = $ToolsRoot.TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar
    if (-not $commandSource.StartsWith($allowedPrefix, [StringComparison]::OrdinalIgnoreCase) -or
        -not $commandDestination.StartsWith($allowedPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'SDK move escaped the verified tool directory'
    }
    Move-Item -LiteralPath $commandSource -Destination $commandDestination
}

$env:JAVA_HOME = $jdk
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
$env:GRADLE_USER_HOME = Join-Path $ToolsRoot 'gradle-home'
$env:Path = "$jdk/bin;$gradle/bin;$sdk/platform-tools;$env:Path"
$sdkmanager = "$sdk/cmdline-tools/17.0/bin/sdkmanager.bat"
if ($AcceptSdkLicenses) {
    (1..100 | ForEach-Object { 'y' }) | & $sdkmanager "--sdk_root=$sdk" --licenses > "$ToolsRoot/sdk-licenses.log"
    if ($LASTEXITCODE -ne 0) { throw 'SDK license acceptance failed' }
}
& $sdkmanager "--sdk_root=$sdk" 'platform-tools' 'platforms;android-35' 'platforms;android-36' 'build-tools;35.0.0'
if ($LASTEXITCODE -ne 0) { throw 'SDK installation failed. Run with -AcceptSdkLicenses if needed.' }

New-Item -ItemType Directory -Force "$projectRoot/app/libs" | Out-Null
Get-VerifiedArchive 'https://github.com/heiher/hev-socks5-tunnel/releases/download/2.18.0/hev-socks5-tunnel.aar' "$projectRoot/app/libs/hev-socks5-tunnel-2.18.0.aar" '15ec8ed121663b562c99caa5bb602d1009f24e5b09e733438b81988f12feaaab'

& "$jdk/bin/java.exe" -version
if ($LASTEXITCODE -ne 0) { throw 'Java verification failed' }
& "$gradle/bin/gradle.bat" --version
if ($LASTEXITCODE -ne 0) { throw 'Gradle verification failed' }
& $sdkmanager "--sdk_root=$sdk" --list_installed
if ($LASTEXITCODE -ne 0) { throw 'SDK verification failed' }
Write-Host "Portable toolchain ready. JAVA_HOME=$jdk ANDROID_HOME=$sdk"
Write-Host 'Dot-source this script to retain environment variables in the calling shell.'

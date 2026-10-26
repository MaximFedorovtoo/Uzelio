param(
    [Parameter(Position=0, ValueFromRemainingArguments=$true)]
    [string[]]$GradleTasks = @(':app:assembleDebug', ':app:assembleDebugAndroidTest', ':app:lintDebug')
)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
$buildRoot = 'C:\Users\Public\uzel-build-workspace'
$tempRoot = 'C:\Users\Public\uzel-build-temp'

# Windows tool argument files and Unix-domain socket names use ASCII paths.
# The junction preserves the existing sources and downloaded caches in place.
if (Test-Path -LiteralPath $buildRoot) {
    $junction = Get-Item -LiteralPath $buildRoot
    if ($junction.LinkType -ne 'Junction' -or [IO.Path]::GetFullPath([string]$junction.Target) -ne $projectRoot) {
        throw "Build alias already belongs to another directory: $buildRoot"
    }
} else {
    New-Item -ItemType Junction -Path $buildRoot -Target $projectRoot | Out-Null
}
New-Item -ItemType Directory -Force $tempRoot | Out-Null
$toolRoot = Join-Path $buildRoot '.tools'
if (-not (Test-Path "$toolRoot/jdk-21.0.12.1+1/bin/java.exe") -or
    -not (Test-Path "$toolRoot/gradle-8.13/bin/gradle.bat") -or
    -not (Test-Path "$toolRoot/android-sdk/platforms/android-35/android.jar") -or
    -not (Test-Path "$toolRoot/android-sdk/platforms/android-36/android.jar") -or
    -not (Test-Path "$projectRoot/app/libs/hev-socks5-tunnel-2.18.0.aar")) {
    & "$PSScriptRoot/bootstrap.ps1" -ToolsRoot $toolRoot -AcceptSdkLicenses
}
& "$PSScriptRoot/bootstrap-traversal.ps1" -ToolsRoot $toolRoot
$env:JAVA_HOME = "$toolRoot\jdk-21.0.12.1+1"
$env:ANDROID_HOME = "$toolRoot\android-sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:GRADLE_USER_HOME = "$toolRoot\gradle-home"
$env:JAVA_TOOL_OPTIONS = "$env:JAVA_TOOL_OPTIONS -Djava.io.tmpdir=$tempRoot -Djdk.net.unixdomain.tmpdir=$tempRoot".Trim()
$env:Path = "$env:JAVA_HOME\bin;$toolRoot\gradle-8.13\bin;$env:ANDROID_HOME\platform-tools;$env:Path"
Push-Location $buildRoot
try {
    & "$toolRoot/gradle-8.13/bin/gradle.bat" --no-daemon --console=plain @GradleTasks
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed (exit $LASTEXITCODE)" }
} finally {
    Pop-Location
}

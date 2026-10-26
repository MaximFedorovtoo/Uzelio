param([ValidateSet('app-image','exe','msi')][string]$Type='exe',[string]$Version='0.8.0',[string]$WixDirectory)
$ErrorActionPreference='Stop'
if ($env:OS -ne 'Windows_NT') { throw 'Windows packaging must run locally on Windows.' }
$root=[IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
$rootItem=Get-Item -LiteralPath $root
if ($rootItem.LinkType -eq 'Junction') { $root=[IO.Path]::GetFullPath([string]$rootItem.Target) }
$alias='C:\Users\Public\uzel-build-workspace'
if (Test-Path $alias) {
    $item=Get-Item -LiteralPath $alias
    if ($item.LinkType -ne 'Junction' -or [IO.Path]::GetFullPath([string]$item.Target) -ne $root) { throw 'Build alias belongs to another workspace.' }
} else { New-Item -ItemType Junction -Path $alias -Target $root | Out-Null }
$jdk=Join-Path $alias '.tools/jdk-21.0.12.1+1'
if (-not (Test-Path "$jdk/bin/jpackage.exe")) { throw 'Run tools/bootstrap.ps1 to prepare the local JDK.' }
if ($Type -ne 'app-image') {
    if (-not $WixDirectory) { $WixDirectory=Join-Path $alias '.tools/wix-3.14.1' }
    if (-not (Test-Path "$WixDirectory/candle.exe") -or -not (Test-Path "$WixDirectory/light.exe")) { throw 'WiX 3.14.1 portable tools required. See desktop/PACKAGING.md.' }
    $toolHashes=@{ 'candle.exe'='A464676E2A2191D58E6CA643E89505BFB9628D3E721873A14994D3F4667987E5'; 'light.exe'='8EE3D7FA5592511152A4D1FC605780EAE1B27AC7B41D75D518C958515DE39529' }
    foreach ($tool in $toolHashes.Keys) { if ((Get-FileHash (Join-Path $WixDirectory $tool) -Algorithm SHA256).Hash -ne $toolHashes[$tool]) { throw "Unexpected WiX executable: $tool. Use the documented official 3.14.1 binaries." } }
    $env:PATH="$WixDirectory;$env:PATH"
}
$env:JAVA_HOME=$jdk
$env:GRADLE_USER_HOME=Join-Path $alias '.tools/gradle-home'
$tempRoot='C:\Users\Public\uzel-build-temp'
New-Item -ItemType Directory -Force $tempRoot | Out-Null
$env:JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$tempRoot -Djdk.net.unixdomain.tmpdir=$tempRoot"
Push-Location $alias
try {
    & '.tools/gradle-8.13/bin/gradle.bat' --no-daemon --console=plain :desktop:test :desktop:packageInput
    if ($LASTEXITCODE) { throw 'Desktop checks/build failed.' }
    $dest=Join-Path $alias "dist/desktop/$Type-$Version"
    New-Item -ItemType Directory -Force $dest | Out-Null
    $arguments=@('--type',$Type,'--name','Uzelio','--app-version',$Version,'--vendor','Uzelio',
        '--description','Uzelio distributed device directory','--input',"$alias/desktop/build/package-input",'--main-jar','desktop.jar',
        '--main-class','org.uzel.desktop.DesktopMain','--dest',$dest,'--icon',"$alias/desktop/build/uzelio.ico",'--add-modules','java.desktop,java.naming,java.sql,jdk.crypto.ec',
        '--java-options','-Dfile.encoding=UTF-8')
    if ($Type -ne 'app-image') { $arguments+=@('--win-per-user-install','--win-shortcut','--win-menu','--win-menu-group','Uzelio','--win-dir-chooser','--win-upgrade-uuid','73e27e14-437c-46d3-b24c-477f68f4e912') }
    & "$jdk/bin/jpackage.exe" @arguments
    if ($LASTEXITCODE) { throw 'jpackage failed.' }
    if ($Type -eq 'app-image') {
        $runtimeRelease=Join-Path $dest 'Uzelio/runtime/release'
        if (-not (Test-Path -LiteralPath $runtimeRelease) -or
            -not (Select-String -LiteralPath $runtimeRelease -Pattern '^JAVA_VERSION="21\.0\.12\.1"$' -Quiet)) {
            throw 'Packaged runtime is not the required Java 21.0.12.1.'
        }
    }
    Write-Output "Package output: $dest"
} finally { Pop-Location }

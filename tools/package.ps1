param([string]$Version='0.8.0')
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
$outputFolder = Join-Path $projectRoot 'dist'
New-Item -ItemType Directory -Force -Path $outputFolder | Out-Null
$apk = Join-Path $outputFolder 'Uzelio.apk'
$archive = Join-Path $outputFolder "Uzelio-$Version-source.zip"
# ABI splits: the phone artifact carries only arm64-v8a native libraries.
$splitApk = Join-Path $projectRoot 'app/build/outputs/apk/debug/app-arm64-v8a-debug.apk'
$universalApk = Join-Path $projectRoot 'app/build/outputs/apk/debug/app-debug.apk'
Copy-Item -LiteralPath $(if (Test-Path $splitApk) { $splitApk } else { $universalApk }) -Destination $apk
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$stream = [IO.File]::Open($archive,[IO.FileMode]::Create)
$zip = [IO.Compression.ZipArchive]::new($stream,[IO.Compression.ZipArchiveMode]::Create)
try {
    # Explicit source roots only; downloaded SDK/JDK/AARs and local credentials are excluded.
    $sources = @('README.md','THIRD_PARTY_NOTICES.txt','.gitignore','.gitattributes','build.gradle','settings.gradle','gradle.properties','docs/brandbook.html')
    foreach($sourceRoot in @('core/src','app/src','desktop/src','routing-probe-vpn/src','routing-probe-direct/src','docs/i18n','docs/licenses','docs/designs','tools')) {
        Get-ChildItem -LiteralPath (Join-Path $projectRoot $sourceRoot) -Recurse -File | ForEach-Object {
            $sources += $_.FullName.Substring($projectRoot.Length+1)
        }
    }
    $sources += @('app/build.gradle','core/build.gradle','desktop/build.gradle','routing-probe-vpn/build.gradle','routing-probe-direct/build.gradle')
    foreach($source in $sources) {
        $fullPath = Join-Path $projectRoot $source
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,$fullPath,$source.Replace('\','/')) | Out-Null
    }
} finally { $zip.Dispose(); $stream.Dispose() }
$hashes = @($apk,$archive) | ForEach-Object {
    '{0}  {1}' -f (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant(),[IO.Path]::GetFileName($_)
}
[IO.File]::WriteAllLines((Join-Path $outputFolder "Uzelio-$Version-SHA256SUMS.txt"),$hashes,[Text.UTF8Encoding]::new($false))
$hashes


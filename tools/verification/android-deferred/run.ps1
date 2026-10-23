param(
    [Parameter(Mandatory=$true)][string]$ToolRoot
)
$ErrorActionPreference='Stop'
$repository=(Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$tools=(Resolve-Path -LiteralPath $ToolRoot).Path
$jdk=Get-ChildItem -LiteralPath $tools -Directory -Filter 'jdk-17*' | Select-Object -First 1
if(!$jdk){throw 'JDK 17 must already be installed in ToolRoot'}
$junit=Get-ChildItem -LiteralPath (Join-Path $tools 'gradle-home/caches/modules-2/files-2.1/junit/junit/4.13.2') -Recurse -Filter '*.jar' | Select-Object -First 1
$hamcrest=Get-ChildItem -LiteralPath (Join-Path $tools 'gradle-home/caches/modules-2/files-2.1/org.hamcrest/hamcrest-core/1.3') -Recurse -Filter '*.jar' | Select-Object -First 1
if(!$junit -or !$hamcrest){throw 'Cached JUnit 4.13.2 and Hamcrest 1.3 are required; this harness never downloads dependencies'}
$output=Join-Path $repository '.tools/android-deferred-regression/classes'
New-Item -ItemType Directory -Force -Path $output | Out-Null
$sources=@(Get-ChildItem (Join-Path $repository 'core/src/main/java') -Recurse -Filter '*.java' | ForEach-Object FullName)
$sources+=@(Get-ChildItem (Join-Path $PSScriptRoot 'src') -Recurse -Filter '*.java' | ForEach-Object FullName)
$sources+=@('core/src/test/java/org/uzel/core/FederationTestIdentities.java','app/src/main/java/org/uzel/app/AutoDiscoveryManager.java','app/src/main/java/org/uzel/app/PairingManager.java' | ForEach-Object {Join-Path $repository $_})
$classPath="$($junit.FullName);$($hamcrest.FullName)"
& (Join-Path $jdk.FullName 'bin/javac.exe') -encoding UTF-8 -cp $classPath -d $output @sources
if($LASTEXITCODE -ne 0){throw 'Regression compilation failed'}
& (Join-Path $jdk.FullName 'bin/java.exe') -cp "$output;$classPath" org.junit.runner.JUnitCore org.uzel.app.DeferredSignalRegressionTest
if($LASTEXITCODE -ne 0){throw 'Deferred signaling regression failed'}

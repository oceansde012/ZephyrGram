param(
    [Parameter(Mandatory=$true)][string]$JsonJar,
    [Parameter(Mandatory=$true)][string]$BuildDirectory
)
$ErrorActionPreference = 'Stop'
$sourceRoot = Split-Path $PSScriptRoot -Parent
$jarPath = (Resolve-Path -LiteralPath $JsonJar).Path
New-Item -ItemType Directory -Force -Path $BuildDirectory | Out-Null
$buildPath = (Resolve-Path -LiteralPath $BuildDirectory).Path
$testSources = Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'memory_bridge_test') -Recurse -Filter *.java | Select-Object -ExpandProperty FullName
$transport = Join-Path $sourceRoot 'TMessagesProj/src/main/java/org/telegram/messenger/zephyr/ZephyrMemoryClient.java'
& javac -encoding UTF-8 -cp $jarPath -d $buildPath $transport $testSources
if ($LASTEXITCODE -ne 0) { throw 'Memory bridge JVM compilation failed' }
& java -cp "$buildPath$([IO.Path]::PathSeparator)$jarPath" org.telegram.messenger.zephyr.MemoryBridgeTest
if ($LASTEXITCODE -ne 0) { throw 'Memory bridge JVM tests failed' }
& java -cp "$buildPath$([IO.Path]::PathSeparator)$jarPath" org.telegram.messenger.zephyr.MemoryStyleBridgeTest
if ($LASTEXITCODE -ne 0) { throw 'Memory style JVM tests failed' }

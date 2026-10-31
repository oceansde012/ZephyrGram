param(
    [string]$Task = ':TMessagesProj_App:assembleAfatDebug',
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$wrapper = Join-Path $projectRoot 'gradle/wrapper/gradle-wrapper.jar'
if (!(Test-Path -LiteralPath $wrapper)) { throw 'Gradle wrapper JAR is missing.' }
$java = 'java.exe'
if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
    $java = Join-Path $env:JAVA_HOME 'bin/java.exe'
}
$gradleArgs = @('-cp', $wrapper, 'org.gradle.wrapper.GradleWrapperMain',
    '--no-daemon', '-Dorg.gradle.jvmargs=-Xmx4g', $Task)
if ($Offline) { $gradleArgs += '--offline' }
Push-Location $projectRoot
try {
    & $java @gradleArgs
    $result = $LASTEXITCODE
} finally {
    Pop-Location
}
exit $result

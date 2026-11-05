$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$classes = Join-Path $projectRoot ('.gradle/zephyr-tests/crypto/' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fffffff'))
[IO.Directory]::CreateDirectory($classes) | Out-Null
$javac = 'javac.exe'
$java = 'java.exe'
if ($env:JAVA_HOME) {
    $javac = Join-Path $env:JAVA_HOME 'bin/javac.exe'
    $java = Join-Path $env:JAVA_HOME 'bin/java.exe'
}
$verifier = Join-Path $projectRoot 'TMessagesProj/src/main/java/org/telegram/messenger/zephyr/plugins/Ed25519Verify.java'
$test = Join-Path $PSScriptRoot 'tests/Ed25519RegressionTest.java'
& $javac -d $classes $verifier $test
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $java -cp $classes Ed25519RegressionTest
exit $LASTEXITCODE

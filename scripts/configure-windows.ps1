param(
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$SdkPath,
    [switch]$CheckOnly
)
$ErrorActionPreference = 'Stop'
$localFile = Join-Path $ProjectRoot 'local.properties'

function Read-LocalValue([string]$Name) {
    if (!(Test-Path -LiteralPath $localFile)) { return '' }
    $value = ''
    foreach ($line in [IO.File]::ReadAllLines($localFile)) {
        if ($line -match ('^\s*' + [regex]::Escape($Name) + '\s*=(.*)$')) {
            $value = $Matches[1].Trim()
        }
    }
    return $value
}

function Test-Sdk([string]$Path) {
    if (!$Path) { return $false }
    foreach ($part in @('platforms/android-36/android.jar', 'build-tools/36.0.0/aapt2.exe',
        'ndk/27.2.12479018/source.properties', 'cmake/3.22.1/bin/cmake.exe')) {
        if (!(Test-Path -LiteralPath (Join-Path $Path $part))) { return $false }
    }
    return $true
}

function Find-Sdk {
    $candidates = @($SdkPath, (Read-LocalValue 'sdk.dir'), $env:ANDROID_HOME,
        $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA 'Android/Sdk'))
    $packages = Join-Path $env:LOCALAPPDATA 'Packages'
    if (Test-Path -LiteralPath $packages) {
        foreach ($package in Get-ChildItem -LiteralPath $packages -Directory -Filter 'Claude*') {
            $candidates += Join-Path $package.FullName 'LocalCache/Local/Android/Sdk'
        }
    }
    foreach ($candidate in $candidates) {
        if (Test-Sdk $candidate) { return (Resolve-Path -LiteralPath $candidate).Path }
    }
    return ''
}

function Test-ApiId([string]$Value) {
    $number = 0
    return ($Value -match '^[0-9]+$' -and [int]::TryParse($Value, [ref]$number) -and $number -gt 0)
}

function Save-LocalConfiguration([string]$ApiId, [string]$ApiHash, [string]$Sdk) {
    $values = [ordered]@{ 'sdk.dir' = $Sdk.Replace('\', '/'); 'TG_API_ID' = ([int]$ApiId).ToString(); 'TG_API_HASH' = $ApiHash }
    $lines = @()
    if (Test-Path -LiteralPath $localFile) {
        $backupDir = Join-Path $ProjectRoot '.gradle/zephyr-setup-backups'
        [IO.Directory]::CreateDirectory($backupDir) | Out-Null
        Copy-Item -LiteralPath $localFile -Destination (Join-Path $backupDir ('local.properties.' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fffffff')))
        $lines = @([IO.File]::ReadAllLines($localFile))
    }
    $output = New-Object 'System.Collections.Generic.List[string]'
    foreach ($line in $lines) {
        $replace = $false
        foreach ($key in $values.Keys) {
            if ($line -match ('^\s*' + [regex]::Escape($key) + '\s*=')) { $replace = $true; break }
        }
        if (!$replace) { $output.Add($line) }
    }
    foreach ($key in $values.Keys) { $output.Add($key + '=' + $values[$key]) }
    [IO.File]::WriteAllLines($localFile, $output, (New-Object Text.UTF8Encoding($false)))
}

$resolvedSdk = Find-Sdk
$apiId = Read-LocalValue 'TG_API_ID'
$apiHash = Read-LocalValue 'TG_API_HASH'
if (!$apiId) { $apiId = [Environment]::GetEnvironmentVariable('TG_API_ID') }
if (!$apiHash) { $apiHash = [Environment]::GetEnvironmentVariable('TG_API_HASH') }
if ($CheckOnly) {
    Write-Output ('Android SDK complete: ' + [bool]$resolvedSdk)
    Write-Output ('TG_API_ID valid: ' + (Test-ApiId $apiId))
    Write-Output ('TG_API_HASH valid: ' + [bool]($apiHash -match '^[0-9a-fA-F]{32}$'))
    exit 0
}

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[Windows.Forms.Application]::EnableVisualStyles()
$form = New-Object Windows.Forms.Form
$form.Text = 'ZephyrGram - local build setup'
$form.ClientSize = New-Object Drawing.Size(570, 295)
$form.StartPosition = 'CenterScreen'
$form.FormBorderStyle = 'FixedDialog'
$form.MaximizeBox = $false
$form.TopMost = $true
$info = New-Object Windows.Forms.Label
$info.Text = "Enter your own values from my.telegram.org > API development tools.`nSaved only in local.properties (excluded from Git). No upload."
$info.SetBounds(16, 15, 535, 45)
$form.Controls.Add($info)
function Add-Field([string]$Label, [int]$Y, [string]$Text, [bool]$Secret) {
    $caption = New-Object Windows.Forms.Label
    $caption.Text = $Label
    $caption.SetBounds(16, $Y, 115, 24)
    $form.Controls.Add($caption)
    $box = New-Object Windows.Forms.TextBox
    $box.SetBounds(135, $Y, 415, 24)
    $box.Text = $Text
    $box.UseSystemPasswordChar = $Secret
    $form.Controls.Add($box)
    return $box
}
$idBox = Add-Field 'Telegram API ID' 75 $apiId $false
$hashBox = Add-Field 'Telegram API Hash' 112 $apiHash $true
$sdkBox = Add-Field 'Android SDK' 149 $resolvedSdk $false
$status = New-Object Windows.Forms.Label
$status.Text = 'The ID is a positive number; the Hash is 32 hexadecimal characters.'
$status.SetBounds(16, 192, 535, 35)
$form.Controls.Add($status)
$save = New-Object Windows.Forms.Button
$save.Text = 'Save locally'
$save.SetBounds(320, 242, 110, 32)
$form.Controls.Add($save)
$cancel = New-Object Windows.Forms.Button
$cancel.Text = 'Cancel'
$cancel.SetBounds(440, 242, 110, 32)
$cancel.DialogResult = [Windows.Forms.DialogResult]::Cancel
$form.Controls.Add($cancel)
$form.CancelButton = $cancel
$form.AcceptButton = $save
$save.Add_Click({
    $id = $idBox.Text.Trim()
    $hash = $hashBox.Text.Trim()
    $sdk = $sdkBox.Text.Trim()
    if (!(Test-ApiId $id)) { $status.Text = 'API ID must be a positive number up to 2147483647.'; return }
    if ($hash -notmatch '^[0-9a-fA-F]{32}$') { $status.Text = 'API Hash must contain exactly 32 hexadecimal characters.'; return }
    if (!(Test-Sdk $sdk)) { $status.Text = 'SDK is missing a required platform, build-tools, NDK or CMake package.'; return }
    try {
        Save-LocalConfiguration $id $hash $sdk
        [Windows.Forms.MessageBox]::Show('Settings saved locally. You can return to Codex.', 'ZephyrGram') | Out-Null
        $form.DialogResult = [Windows.Forms.DialogResult]::OK
        $form.Close()
    } catch {
        $status.Text = 'Could not save local.properties. Check folder write access.'
    }
})
[void]$form.ShowDialog()
$hashBox.Clear()
$apiHash = $null
$form.Dispose()

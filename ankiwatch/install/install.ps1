# Installs AnkiWatch on your phone and Galaxy Watch from a Windows PC.
#
# Put this script next to ankiwatch-phone.apk and ankiwatch-watch.apk (the files from the
# CI download), then in PowerShell:
#
#   .\install.ps1 -Watch 192.168.1.23:41235            # watch only (phone APK opened on the phone)
#   .\install.ps1 -Watch 192.168.1.23:41235 -Phone     # watch + phone over USB debugging
#
# -Watch is the "IP address & Port" shown under Developer options > Wireless debugging on
# the watch. Pair once first:  adb pair <ip>:<pairing port> <pairing code>
#
# If adb isn't on your PATH, pass its location: -Adb C:\platform-tools\adb.exe
param(
    [Parameter(Mandatory = $true)][string]$Watch,
    [switch]$Phone,
    [string]$Adb = "adb"
)

$ErrorActionPreference = "Stop"
$Package = "com.ankiwatch.cloze"
$Here = Split-Path -Parent $MyInvocation.MyCommand.Path

function Install-Apk([string]$Serial, [string]$Apk) {
    Write-Host "Installing $(Split-Path -Leaf $Apk) on $Serial ..."
    $out = & $Adb -s $Serial install -r $Apk 2>&1 | Out-String
    if ($out -match "INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match") {
        # A build signed with a different key: remove the old one first.
        Write-Host "  Signing key changed - reinstalling (review history lives in AnkiDroid, nothing is lost)."
        & $Adb -s $Serial uninstall $Package | Out-Null
        $out = & $Adb -s $Serial install $Apk 2>&1 | Out-String
    }
    if ($out -notmatch "Success") { throw "Install failed on ${Serial}:`n$out" }
    Write-Host "  OK"
}

& $Adb version | Out-Null

if ($Phone) {
    $devices = & $Adb devices | Select-String "`tdevice$" | ForEach-Object { ($_ -split "`t")[0] }
    $phoneSerial = $devices | Where-Object { $_ -notmatch ":" } | Select-Object -First 1
    if (-not $phoneSerial) { throw "No phone found over USB. Enable USB debugging on the phone and accept the prompt." }
    Install-Apk $phoneSerial (Join-Path $Here "ankiwatch-phone.apk")
    # Same permission the app asks for on first launch.
    & $Adb -s $phoneSerial shell pm grant $Package com.ichi2.anki.permission.READ_WRITE_DATABASE 2>$null
}

& $Adb connect $Watch | Write-Host
Install-Apk $Watch (Join-Path $Here "ankiwatch-watch.apk")

Write-Host ""
Write-Host "Done. Open AnkiWatch on the phone once (it asks for AnkiDroid access), then on the watch."

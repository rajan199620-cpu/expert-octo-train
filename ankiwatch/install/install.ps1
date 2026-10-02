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
# If adb isn't on your PATH, pass its location: -Adb .\adb.exe
param(
    [Parameter(Mandatory = $true)][string]$Watch,
    [switch]$Phone,
    [string]$Adb = "adb"
)

# Not "Stop": Windows PowerShell 5.1 turns anything adb prints on stderr into a terminating
# error under "Stop", which would abort before the signature-mismatch retry below.
$ErrorActionPreference = "Continue"
$Package = "com.ankiwatch.cloze"
$Here = Split-Path -Parent $MyInvocation.MyCommand.Path

if (-not (Get-Command $Adb -ErrorAction SilentlyContinue)) {
    Write-Error "adb not found. Install Android SDK Platform-Tools and pass -Adb <path to adb.exe>."
    exit 1
}

function Invoke-Adb([string[]]$Arguments) {
    # Merge stderr into the text we inspect; never let it raise.
    $output = & $Adb @Arguments 2>&1 | ForEach-Object { "$_" }
    return ($output -join "`n")
}

function Install-Apk([string]$Serial, [string]$Apk) {
    if (-not (Test-Path $Apk)) {
        Write-Error "Missing $Apk - run this script from the unzipped download."
        exit 1
    }
    Write-Host "Installing $(Split-Path -Leaf $Apk) on $Serial ..."
    $out = Invoke-Adb @("-s", $Serial, "install", "-r", $Apk)
    if ($out -match "INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match") {
        # A build signed with a different key: remove the old one first.
        Write-Host "  Signing key changed - reinstalling (review history lives in AnkiDroid, nothing is lost)."
        Invoke-Adb @("-s", $Serial, "uninstall", $Package) | Out-Null
        $out = Invoke-Adb @("-s", $Serial, "install", $Apk)
    }
    if ($out -notmatch "Success") {
        Write-Error "Install failed on ${Serial}:`n$out"
        exit 1
    }
    Write-Host "  OK"
}

if ($Phone) {
    $lines = (Invoke-Adb @("devices")) -split "`n"
    $phoneSerial = $lines |
        Where-Object { $_ -match "^(\S+)\s+device$" -and $Matches[1] -notmatch ":" } |
        ForEach-Object { ($_ -split "\s+")[0] } |
        Select-Object -First 1
    if (-not $phoneSerial) {
        Write-Error "No phone found over USB. Enable USB debugging on the phone and accept the prompt."
        exit 1
    }
    Install-Apk $phoneSerial (Join-Path $Here "ankiwatch-phone.apk")
    # Same permission the app asks for on first launch.
    Invoke-Adb @("-s", $phoneSerial, "shell", "pm", "grant", $Package, "com.ichi2.anki.permission.READ_WRITE_DATABASE") | Out-Null
}

Write-Host (Invoke-Adb @("connect", $Watch))
Install-Apk $Watch (Join-Path $Here "ankiwatch-watch.apk")

Write-Host ""
Write-Host "Done. Open AnkiWatch on the phone once (it asks for AnkiDroid access), then on the watch."

# Installs AnkiWatch on your phone and Galaxy Watch from a Windows PC.
#
# Copy everything from the CI download (this script and both APKs) into your platform-tools
# folder, next to adb.exe, then in PowerShell, in that folder:
#
#   powershell -ExecutionPolicy Bypass -File .\install.ps1 -Watch 192.168.1.23:41235
#   powershell -ExecutionPolicy Bypass -File .\install.ps1 -Watch 192.168.1.23:41235 -Phone
#
# ("-ExecutionPolicy Bypass" lets Windows run this downloaded script once, without changing
# any setting.) -Watch is the "IP address & Port" shown under Developer options > Wireless
# debugging on the watch. Pair once first:  .\adb.exe pair <ip>:<pairing port> <code>
# -Phone also installs the phone app on a phone connected by USB with USB debugging on.
# adb is found next to this script or in the current folder; otherwise pass -Adb <path>.
# -Yes skips the question asked before removing a watch app signed with another key.
param(
    [Parameter(Mandatory = $true)][string]$Watch,
    [switch]$Phone,
    [switch]$Yes,
    [string]$Adb = "adb"
)

# Not "Stop": Windows PowerShell 5.1 turns anything adb prints on stderr into a terminating
# error under "Stop", which would abort before the retries below.
$ErrorActionPreference = "Continue"
$Package = "com.ankiwatch.cloze"
$Here = Split-Path -Parent $MyInvocation.MyCommand.Path

function Fail([string]$Message) {
    # Plain red text: Write-Error would bury the advice under PowerShell's code-line noise.
    Write-Host $Message -ForegroundColor Red
    exit 1
}

if ($Adb -eq "adb" -and -not (Get-Command adb -ErrorAction SilentlyContinue)) {
    foreach ($candidate in @((Join-Path $Here "adb.exe"), (Join-Path (Get-Location) "adb.exe"))) {
        if (Test-Path $candidate) { $Adb = $candidate; break }
    }
}
if (-not (Get-Command $Adb -ErrorAction SilentlyContinue)) {
    Fail "adb not found. Put this script in the platform-tools folder (next to adb.exe), or pass -Adb <path to adb.exe>."
}

function Invoke-Adb([string[]]$Arguments) {
    # Merge stderr into the text we inspect; never let it raise.
    $output = & $Adb @Arguments 2>&1 | ForEach-Object { "$_" }
    return ($output -join "`n")
}

# "true" on a Wear OS watch, "false" on a phone; anything else if the device can't say.
function Get-WatchFeature([string]$Serial) {
    $out = Invoke-Adb @("-s", $Serial, "shell", "pm", "has-feature", "android.hardware.type.watch")
    $lines = @($out -split "`r?`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    if ($lines.Count -eq 0) { return "" }
    return $lines[-1]
}

# --no-streaming copies the APK over first and then installs it on the device itself, so a
# Wi-Fi hiccup shows up as a failed copy (retried below) rather than a reasonless failure.
function Install-Once([string]$Serial, [string]$Apk) {
    $out = Invoke-Adb @("-s", $Serial, "install", "-r", "--no-streaming", $Apk)
    if ($out -match "INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match") {
        # A build signed with a different key: the old one has to go first.
        Confirm-Removal $Serial
        Invoke-Adb @("-s", $Serial, "uninstall", $Package) | Out-Null
        $out = Invoke-Adb @("-s", $Serial, "install", "--no-streaming", $Apk)
    }
    return $out
}

# Removing the watch app also deletes its offline downloads and any grades that haven't
# reached the phone yet, so ask first. On a phone nothing is lost: reviews live in AnkiDroid.
function Confirm-Removal([string]$Serial) {
    if ($Yes -or (Get-WatchFeature $Serial) -ne "true") {
        Write-Host "  Signing key changed - removing the old app first (your reviews live in AnkiDroid)."
        return
    }
    Write-Host "  This download is signed differently, so the old watch app has to be removed first." -ForegroundColor Yellow
    Write-Host "  That also deletes the watch's offline downloads and any grades still waiting for your phone." -ForegroundColor Yellow
    Write-Host '  On the watch, AnkiWatch > Offline review must not say "grades waiting for your phone".' -ForegroundColor Yellow
    $answer = Read-Host "  Remove it and install this one? Type y and press Enter"
    # "$answer": Read-Host gives $null when input has ended, and $null -notmatch is not true.
    if ("$answer" -notmatch '^\s*[yY]') {
        Fail "Stopped. Nothing on the watch was changed. Once its grades are in AnkiDroid, run this again."
    }
}

function Install-Apk([string]$Serial, [string]$Apk, [switch]$OverWifi) {
    if (-not (Test-Path $Apk)) {
        Fail "Missing $Apk - copy all files from the download next to this script."
    }
    $mb = [math]::Round((Get-Item $Apk).Length / 1MB, 1)
    Write-Host "Installing $(Split-Path -Leaf $Apk) ($mb MB) on $Serial ..."
    $out = Install-Once $Serial $Apk
    if ($out -notmatch "Success" -and $out -notmatch "Failure \[|INSTALL_") {
        # No answer from the device's installer: the transfer was cut off.
        Write-Host "  The transfer was cut off; reconnecting and trying once more ..."
        if ($OverWifi) { Invoke-Adb @("connect", $Serial) | Out-Null }
        $out = Install-Once $Serial $Apk
    }
    if ($out -match "INSTALL_FAILED_MISSING_SHARED_LIBRARY") {
        Fail "$Serial is not a Wear OS watch, so it refuses the watch app."
    }
    if ($out -notmatch "Success") {
        $hint = ""
        if ($OverWifi -and $out -notmatch "Failure \[|INSTALL_") {
            $hint = "`nThe Wi-Fi link to the watch dropped during the copy. Put the watch on its charger and keep " +
                "its screen on, turn the watch's Bluetooth off until the install is done (Wear OS may switch " +
                "Wi-Fi off while Bluetooth is connected), check the port under Wireless debugging, and run " +
                "this again. Turn Bluetooth back on afterwards."
        }
        Fail "Install failed on ${Serial}:`n$out$hint"
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
        Fail "No phone found over USB. Enable USB debugging on the phone and accept the prompt."
    }
    if ((Get-WatchFeature $phoneSerial) -eq "true") {
        Fail "$phoneSerial (USB) is a watch, not a phone. Connect the phone with USB debugging."
    }
    Install-Apk $phoneSerial (Join-Path $Here "ankiwatch-phone.apk")
    # Same permission the app asks for on first launch.
    Invoke-Adb @("-s", $phoneSerial, "shell", "pm", "grant", $Package, "com.ichi2.anki.permission.READ_WRITE_DATABASE") | Out-Null
}

Write-Host (Invoke-Adb @("connect", $Watch))
# Both apps share one package name, so the watch app on a phone would replace the phone app.
if ((Get-WatchFeature $Watch) -eq "false") {
    Fail ("$Watch is not a watch (it looks like a phone). Use the address shown on the WATCH " +
        "under Settings > Developer options > Wireless debugging > IP address & Port.")
}
Install-Apk $Watch (Join-Path $Here "ankiwatch-watch.apk") -OverWifi

Write-Host ""
Write-Host "Done. Open AnkiWatch Phone on the phone once (it asks for AnkiDroid access), then AnkiWatch on the watch."

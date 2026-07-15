<#
    pull.ps1 — Pull AVATAR session logs off the Galaxy Watch over Wi-Fi (ADB).

    The watch has no data USB port, so it uses ADB over Wi-Fi ("Debug over Wi-Fi").
    This script connects to the watch, pulls every avatar_*.jsonl session file into
    ./avatar_logs, and remembers the watch IP so next time you can just run `.\pull.ps1`.

    ONE-TIME SETUP ON THE WATCH:
      Settings > About watch > Software  ->  tap "Software version" 7x to unlock Developer options
      Settings > Developer options       ->  turn ON "ADB debugging"
      Settings > Developer options       ->  turn ON "Debug over Wi-Fi"
                                             it shows an IP like  192.168.1.42:5555
      (watch + PC must be on the SAME Wi-Fi network)

    USAGE:
      .\pull.ps1 -Ip 192.168.1.42            # first run: pass the IP the watch shows
      .\pull.ps1                             # later runs: reuses the saved IP
      .\pull.ps1 -Ip 192.168.1.42 -Port 5555 -Out .\avatar_logs
#>
param(
    [string]$Ip,
    [int]$Port = 5555,
    [string]$Out = (Join-Path $PSScriptRoot "avatar_logs")
)

$ErrorActionPreference = "Stop"

$Package    = "com.example.galaxywatch5"
$WatchDir   = "/sdcard/Android/data/$Package/files/AVATAR/"
$ConfigFile = Join-Path $PSScriptRoot ".watch"

# --- locate adb ---------------------------------------------------------------
$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) {
    $candidate = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
    if (Test-Path $candidate) { $adb = $candidate }
}
if (-not $adb) {
    Write-Host "adb not found. Install Android platform-tools or add adb to PATH." -ForegroundColor Red
    Write-Host "  Typical location: $env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
    exit 1
}

# --- resolve watch IP (arg > saved config) ------------------------------------
if (-not $Ip -and (Test-Path $ConfigFile)) {
    $Ip = (Get-Content $ConfigFile -Raw).Trim()
    Write-Host "Using saved watch IP: $Ip" -ForegroundColor DarkGray
}
if (-not $Ip) {
    Write-Host "No watch IP. Pass it once with -Ip <the IP the watch shows under Debug over Wi-Fi>." -ForegroundColor Red
    Write-Host "  Example: .\pull.ps1 -Ip 192.168.1.42"
    exit 1
}
$Target = "${Ip}:${Port}"

# --- connect ------------------------------------------------------------------
Write-Host "Connecting to $Target ..." -ForegroundColor Cyan
$connect = & $adb connect $Target
Write-Host "  $connect"
if ($connect -match "unable to connect|failed|cannot connect") {
    Write-Host "Could not reach the watch. Check that:" -ForegroundColor Red
    Write-Host "  - 'Debug over Wi-Fi' is ON and shows this IP:port"
    Write-Host "  - watch and PC are on the same Wi-Fi"
    Write-Host "  - you accept the 'Allow debugging?' prompt on the watch"
    exit 1
}

# First connection triggers an RSA 'Allow debugging?' prompt on the watch face.
Write-Host "Waiting for device to authorize (accept the prompt on the watch if it appears)..." -ForegroundColor Cyan
& $adb -s $Target wait-for-device

# --- pull ---------------------------------------------------------------------
New-Item -ItemType Directory -Force -Path $Out | Out-Null
Write-Host "Pulling $WatchDir  ->  $Out" -ForegroundColor Cyan
& $adb -s $Target pull -a $WatchDir $Out

# --- report -------------------------------------------------------------------
$pulled = Get-ChildItem -Path $Out -Recurse -Filter "avatar_*.jsonl" -ErrorAction SilentlyContinue
if ($pulled) {
    Write-Host ""
    Write-Host "Done. $($pulled.Count) session file(s) in $Out :" -ForegroundColor Green
    $pulled | Sort-Object LastWriteTime -Descending |
        Select-Object -First 10 Name, @{n="KB";e={[math]::Round($_.Length/1KB,1)}}, LastWriteTime |
        Format-Table -AutoSize
} else {
    Write-Host "Connected and pulled, but found no avatar_*.jsonl files yet." -ForegroundColor Yellow
    Write-Host "Record a session on the watch first, then re-run."
}

# --- remember the IP for next time --------------------------------------------
Set-Content -Path $ConfigFile -Value $Ip -Encoding utf8

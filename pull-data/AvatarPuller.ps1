<#
    AvatarPuller.ps1 - tiny window to pull AVATAR logs off the watch over Wi-Fi.
    Launched by "Pull Data.bat" (double-click). No terminal needed.

    Fields:
      1. Watch IP    - the IP the watch shows under Developer options > Debug over Wi-Fi
      2. Port        - usually 5555
      3. Destination - local folder the .jsonl files are pulled into

    Under the hood it runs: adb connect <ip:port>  then  adb pull <watch dir> <destination>
#>

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

$Package    = "com.example.galaxywatch5"
$WatchDir   = "/sdcard/Android/data/$Package/files/AVATAR/"
$ConfigFile = Join-Path $PSScriptRoot ".watch"
$DefaultOut = Join-Path $PSScriptRoot "avatar_logs"

# --- locate adb ---------------------------------------------------------------
function Get-Adb {
    $cmd = (Get-Command adb -ErrorAction SilentlyContinue).Source
    if ($cmd) { return $cmd }
    $c = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
    if (Test-Path $c) { return $c }
    return $null
}

# --- saved IP -----------------------------------------------------------------
$savedIp = ""
if (Test-Path $ConfigFile) { $savedIp = (Get-Content $ConfigFile -Raw).Trim() }

# --- build the window ---------------------------------------------------------
$form = New-Object System.Windows.Forms.Form
$form.Text            = "AVATAR - Pull Watch Data"
$form.Size            = New-Object System.Drawing.Size(560, 470)
$form.StartPosition   = "CenterScreen"
$form.FormBorderStyle = "FixedSingle"
$form.MaximizeBox     = $false

function New-Label($text, $x, $y) {
    $l = New-Object System.Windows.Forms.Label
    $l.Text = $text; $l.Location = New-Object System.Drawing.Point($x, $y)
    $l.AutoSize = $true
    $form.Controls.Add($l); return $l
}
function New-Box($x, $y, $w, $value) {
    $t = New-Object System.Windows.Forms.TextBox
    $t.Location = New-Object System.Drawing.Point($x, $y)
    $t.Size = New-Object System.Drawing.Size($w, 24)
    $t.Text = $value
    $form.Controls.Add($t); return $t
}

New-Label "1.  Watch IP address"  20 20  | Out-Null
$ipBox = New-Box 20 42 300 $savedIp

New-Label "2.  Port"              340 20 | Out-Null
$portBox = New-Box 340 42 180 "5555"

New-Label "3.  Destination folder" 20 82 | Out-Null
$outBox = New-Box 20 104 400 $DefaultOut

$browse = New-Object System.Windows.Forms.Button
$browse.Text = "Browse..."
$browse.Location = New-Object System.Drawing.Point(430, 103)
$browse.Size = New-Object System.Drawing.Size(90, 26)
$browse.Add_Click({
    $dlg = New-Object System.Windows.Forms.FolderBrowserDialog
    if (Test-Path $outBox.Text) { $dlg.SelectedPath = $outBox.Text }
    if ($dlg.ShowDialog() -eq "OK") { $outBox.Text = $dlg.SelectedPath }
})
$form.Controls.Add($browse)

$pull = New-Object System.Windows.Forms.Button
$pull.Text = "Pull Data"
$pull.Location = New-Object System.Drawing.Point(20, 148)
$pull.Size = New-Object System.Drawing.Size(500, 40)
$pull.Font = New-Object System.Drawing.Font("Segoe UI", 11, [System.Drawing.FontStyle]::Bold)
$form.Controls.Add($pull)

$status = New-Label "Fill in the watch IP, then click Pull Data." 20 196
$status.AutoSize = $false
$status.Size = New-Object System.Drawing.Size(500, 20)

$log = New-Object System.Windows.Forms.TextBox
$log.Location   = New-Object System.Drawing.Point(20, 222)
$log.Size       = New-Object System.Drawing.Size(500, 195)
$log.Multiline  = $true
$log.ReadOnly   = $true
$log.ScrollBars = "Vertical"
$log.BackColor  = [System.Drawing.Color]::FromArgb(30, 30, 30)
$log.ForeColor  = [System.Drawing.Color]::FromArgb(220, 220, 220)
$log.Font       = New-Object System.Drawing.Font("Consolas", 9)
$form.Controls.Add($log)

function Write-Log($msg) {
    $log.AppendText($msg + "`r`n")
    [System.Windows.Forms.Application]::DoEvents()
}

# --- pull action --------------------------------------------------------------
$pull.Add_Click({
    $log.Clear()
    $ip   = $ipBox.Text.Trim()
    $port = $portBox.Text.Trim()
    $out  = $outBox.Text.Trim()

    if (-not $ip)   { $status.Text = "Please enter the watch IP.";      return }
    if (-not $port) { $port = "5555" }
    if (-not $out)  { $out  = $DefaultOut }
    $target = "${ip}:${port}"

    $adb = Get-Adb
    if (-not $adb) {
        $status.Text = "adb not found."
        Write-Log "adb (Android platform-tools) not found on this PC."
        Write-Log "Expected: $env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
        return
    }

    $pull.Enabled = $false
    $status.Text = "Connecting to $target ..."
    Write-Log "Connecting to $target ..."

    try {
        $c = & $adb connect $target 2>&1 | Out-String
        Write-Log $c.Trim()
        if ($c -match "unable to connect|failed|cannot connect") {
            $status.Text = "Could not reach the watch."
            Write-Log "Check: Debug over Wi-Fi is ON, same Wi-Fi network, and accept the prompt on the watch."
            return
        }

        Write-Log "Waiting for the watch to authorize (accept the prompt on the watch if it appears)..."
        & $adb -s $target wait-for-device

        New-Item -ItemType Directory -Force -Path $out | Out-Null
        $status.Text = "Pulling files ..."
        Write-Log "Pulling $WatchDir"
        Write-Log "     -> $out"
        $p = & $adb -s $target pull -a $WatchDir $out 2>&1 | Out-String
        Write-Log $p.Trim()

        $files = Get-ChildItem -Path $out -Recurse -Filter "avatar_*.jsonl" -ErrorAction SilentlyContinue
        if ($files) {
            $status.Text = "Done - $($files.Count) session file(s) in destination."
            Write-Log ""
            Write-Log "Done. $($files.Count) file(s) pulled."
        } else {
            $status.Text = "Connected, but no session files found yet."
            Write-Log "No avatar_*.jsonl found. Record a session on the watch, then pull again."
        }
        Set-Content -Path $ConfigFile -Value $ip -Encoding utf8   # remember IP
    }
    catch {
        $status.Text = "Error - see log."
        Write-Log ("Error: " + $_.Exception.Message)
    }
    finally {
        $pull.Enabled = $true
    }
})

[void]$form.ShowDialog()

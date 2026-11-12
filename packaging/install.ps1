# Installs YTM Starcast from this folder into your Python and starts it at logon (Windows, for people who use Python).
# Most people want the Windows download instead (install-exe.ps1 inside it): it needs no Python.
#
#   .\packaging\install.ps1                         # Windows volume + media controls
#   .\packaging\install.ps1 -Audio voicemeeter      # also use Voicemeeter (EQ, meter) -- optional extra
#   .\packaging\install.ps1 -Lan                    # also listen on your home network (adds an access token)
#   .\packaging\install.ps1 -KeepYtmBeacon          # leave the separate YTM Beacon app installed
#   .\packaging\install.ps1 -Uninstall              # remove YTM Starcast from your Python (your settings are kept)
#
# No admin rights needed. First run installs the Python dependencies for you. Your settings live in %APPDATA%\ytm-starcast.
# YTM Starcast used to be called Pocketfader: an installed Pocketfader is stopped and replaced (its settings carry over).
# If the separate YTM Beacon app is installed, its settings are copied to YTM Starcast and it is removed with its own
# uninstaller; YTM Starcast shows your Discord status itself now.

param(
    [ValidateSet("windows", "voicemeeter")][string]$Audio = "windows",
    [switch]$Lan,
    [switch]$KeepYtmBeacon,
    [switch]$Uninstall
)

$TaskName = "YTM Starcast"
$LegacyTask = "Pocketfader"
$RunKey = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run"
$RunValue = "YTM Starcast"        # written by the desktop window's "Start when I log in"
$LegacyRunValue = "Pocketfader"
$Root = Split-Path -Parent $PSScriptRoot

function Remove-Task($name) {
    if (Get-ScheduledTask -TaskName $name -ErrorAction SilentlyContinue) {
        Stop-ScheduledTask -TaskName $name -ErrorAction SilentlyContinue
        Unregister-ScheduledTask -TaskName $name -Confirm:$false -ErrorAction SilentlyContinue
        return $true
    }
    return $false
}

function Get-TaskArguments($name) {
    $t = Get-ScheduledTask -TaskName $name -ErrorAction SilentlyContinue
    if ($t -and $t.Actions -and $t.Actions.Count -gt 0) { return [string]$t.Actions[0].Arguments }
    return ""
}

# A Python package of that name, if this Python has one: uninstall it.
function Remove-Package($python, $name) {
    & $python -m pip show --quiet $name 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) { & $python -m pip uninstall --yes --quiet $name 2>$null | Out-Null }
}

$py = (Get-Command pythonw.exe -ErrorAction SilentlyContinue).Source
$python = (Get-Command python.exe -ErrorAction SilentlyContinue).Source

if ($Uninstall) {
    foreach ($t in @($TaskName, $LegacyTask)) { [void](Remove-Task $t) }
    foreach ($v in @($RunValue, $LegacyRunValue)) { Remove-ItemProperty -Path $RunKey -Name $v -ErrorAction SilentlyContinue }
    Get-Process -Name "ytm-starcast", "ytm-starcast-gui", "pocketfader", "pocketfader-gui" -ErrorAction SilentlyContinue |
        Stop-Process -Force -ErrorAction SilentlyContinue
    if ($python) {
        Remove-Package $python "ytm-starcast"
        Remove-Package $python "pocketfader"
        Write-Host "YTM Starcast is removed from this Python and no longer starts at logon."
    } else {
        Write-Host "YTM Starcast no longer starts at logon. (Python wasn't found, so its package was left as it is.)"
    }
    Write-Host "Your settings are kept in $(Join-Path $env:APPDATA 'ytm-starcast') (delete that folder for a clean slate)."
    return
}

if (-not $py -or -not $python) { throw "Python 3.9 or newer wasn't found. Install it from python.org (tick 'Add to PATH'), then run this again." }

# Installing again, or over Pocketfader: keep the options it was started with, unless new ones are given here.
$oldArgs = Get-TaskArguments $TaskName
if (-not $oldArgs) { $oldArgs = Get-TaskArguments $LegacyTask }
if (-not $PSBoundParameters.ContainsKey("Lan") -and $oldArgs -match '(^|\s)--lan(\s|$)') { $Lan = $true }
if (-not $PSBoundParameters.ContainsKey("Audio") -and $oldArgs -match '--audio\s+voicemeeter') { $Audio = "voicemeeter" }

# YTM Starcast's old name: stop it and take it out of start-at-logon before installing the new one.
$hadOld = Remove-Task $LegacyTask
if (Get-ItemProperty -Path $RunKey -Name $LegacyRunValue -ErrorAction SilentlyContinue) {
    Remove-ItemProperty -Path $RunKey -Name $LegacyRunValue -ErrorAction SilentlyContinue
    $hadOld = $true
}
$procs = Get-Process -Name "pocketfader", "pocketfader-gui" -ErrorAction SilentlyContinue
if ($procs) { $procs | Stop-Process -Force -ErrorAction SilentlyContinue; $hadOld = $true; Start-Sleep -Seconds 1 }
if ($hadOld) { Write-Host "Replaced Pocketfader (YTM Starcast's old name). Your settings and paired phones carry over." }

Write-Host "Installing YTM Starcast and what it needs..."
Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
& $python -m pip install --quiet -e "$Root[windows]"
if ($LASTEXITCODE -ne 0) { throw "pip couldn't install YTM Starcast (see the messages above)." }
# the old name's package, if this Python still has it: it would only confuse `python -m pocketfader`
Remove-Package $python "pocketfader"

# The separate YTM Beacon app: YTM Starcast shows your Discord status itself now.
$beacon = Join-Path $env:LOCALAPPDATA "ytm-beacon\ytm-beacon.exe"
if ((Test-Path $beacon) -and $KeepYtmBeacon) {
    Write-Host "The YTM Beacon app stays installed (-KeepYtmBeacon). YTM Starcast's Discord status waits while it runs."
} elseif (Test-Path $beacon) {
    Write-Host "Replacing the YTM Beacon app: YTM Starcast shows your music as your Discord status itself now."
    # Its settings first (words never to show, private mode ...). YTM Starcast copies them by itself only the first
    # time it starts, so anything changed in YTM Beacon since then would otherwise be lost.
    & $python -c "import sys; from starcast.config import take_over_beacon_main; sys.exit(take_over_beacon_main())"
    if ($LASTEXITCODE -ne 0) {
        # carrying on with other settings could show songs it was told to keep private
        Write-Host "So the YTM Beacon app stays installed for now. Fix or remove that settings file, then run this again."
        $beacon = $null
    }
}
if ($beacon -and (Test-Path $beacon) -and -not $KeepYtmBeacon) {
    Write-Host "Running YTM Beacon's own uninstaller (it leaves its own settings file in place)."
    $empty = [System.IO.Path]::GetTempFileName()   # stdin from an empty file, so it never waits for a key press
    try {
        $p = Start-Process -FilePath $beacon -ArgumentList "uninstall" -NoNewWindow -Wait -PassThru `
            -RedirectStandardInput $empty -ErrorAction Stop
        if ($p.ExitCode -ne 0) { Write-Host "YTM Beacon's uninstaller ended with code $($p.ExitCode)." }
    } catch {
        Write-Host "YTM Beacon's uninstaller couldn't run: $($_.Exception.Message)"
    } finally {
        Remove-Item $empty -Force -ErrorAction SilentlyContinue
    }
    for ($i = 0; $i -lt 15 -and (Test-Path $beacon); $i++) { Start-Sleep -Seconds 1 }
    if (Test-Path $beacon) {
        Stop-Process -Name "ytm-beacon-bg", "ytm-beacon" -Force -ErrorAction SilentlyContinue
        Remove-ItemProperty -Path $RunKey -Name "YTMBeacon" -ErrorAction SilentlyContinue
        Write-Host "YTM Beacon is stopped and no longer starts at login; its files are still in $(Split-Path $beacon)."
    }
    Write-Host "If your browser has the ""YTM Beacon"" extension, remove it on the browser's extensions page."
}

$argsList = "-m starcast --audio $Audio"
if ($Lan) { $argsList += " --lan" }

# Delay 30s so Tailscale (and Voicemeeter, if used) are up first. The server retries either way.
# Python's path may have a space in it (C:\Program Files\...), so the task's command is quoted.
$action  = New-ScheduledTaskAction -Execute "`"$py`"" -Argument $argsList -WorkingDirectory $Root
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
$trigger.Delay = "PT30S"

# Interactive session only: audio APIs (and Voicemeeter's) can't reach the engine from session 0.
$principal = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -LogonType Interactive -RunLevel Limited
$settings = New-ScheduledTaskSettingsSet `
    -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -StartWhenAvailable -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) `
    -ExecutionTimeLimit (New-TimeSpan -Seconds 0)

Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger `
    -Principal $principal -Settings $settings -Force | Out-Null

# start it now too, so nothing waits for the next logon (Pocketfader or YTM Beacon may just have been stopped)
Start-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
Write-Host "YTM Starcast starts now, and 30 seconds after each time you log in (scheduled task '$TaskName')."
Write-Host "To see the address to open on your phone, run:  python -m starcast --gui"

# One time, by hand: browsers only take an extension like this from their extensions page.
# YTM Starcast puts a copy of it in this folder when it starts (and keeps it up to date after updates).
$extDir = Join-Path $env:LOCALAPPDATA "ytm-starcast\extension"
Write-Host ""
Write-Host "One more step: add the YTM Starcast extension to your browser (one time)"
Write-Host "  1. Open the browser's extensions page: chrome://extensions, edge://extensions or brave://extensions"
Write-Host "  2. Turn on 'Developer mode'."
Write-Host "  3. Click 'Load unpacked' and choose this folder:"
Write-Host "       $extDir"
Write-Host "  4. Open music.youtube.com in that browser (or refresh its tab)."
Write-Host "The same steps, with buttons that open the page and the folder: Settings > Browser extension in the app"
Write-Host "(http://127.0.0.1:8777/#extension)."

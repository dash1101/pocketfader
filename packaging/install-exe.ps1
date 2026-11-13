# Installs the Windows download of YTM Starcast (no Python needed) and starts it at logon.
#
#   .\install-exe.ps1                    # Windows volume + media controls
#   .\install-exe.ps1 -Lan               # also listen on your home network (adds an access token)
#   .\install-exe.ps1 -Port 8790         # another port than 8777
#   .\install-exe.ps1 -Audio voicemeeter # optional Voicemeeter extras (EQ, meter)
#   .\install-exe.ps1 -NoDesktopApp      # leave out the desktop app (the window in the Start menu)
#   .\install-exe.ps1 -KeepYtmBeacon     # leave the separate YTM Beacon app installed
#   .\install-exe.ps1 -Uninstall         # remove YTM Starcast (your settings are kept)
#
# The program is copied to %LOCALAPPDATA%\Programs\YTM Starcast, with a "YTM Starcast" entry in the Start menu.
# No admin rights needed. Your settings live in %APPDATA%\ytm-starcast and are never removed by this script.
# Installing again (an update) keeps the options given last time: -Lan, -Port, -Audio and -NoDesktopApp. To change one,
# give it again: -Lan:$false turns the home network off again, -NoDesktopApp:$false brings the desktop app back.
# YTM Starcast runs without a console window; its messages go to server.log in your settings folder.
#
# YTM Starcast used to be called Pocketfader: an installed Pocketfader is stopped and replaced (its settings carry over).
# YTM Starcast also shows your music as your Discord status, which the separate YTM Beacon app used to do. If that app is
# installed, its settings are copied to YTM Starcast and it is removed with its own uninstaller.

param(
    [ValidateSet("windows", "voicemeeter")][string]$Audio = "windows",
    [switch]$Lan,
    [ValidateRange(1, 65535)][int]$Port = 8777,
    [switch]$NoDesktopApp,
    [switch]$KeepYtmBeacon,
    [switch]$Uninstall
)

$DefaultPort = 8777

$TaskName = "YTM Starcast"
$LegacyTask = "Pocketfader"
$RunKey = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run"
$RunValue = "YTM Starcast"        # written by the desktop window's "Start when I log in"
$LegacyRunValue = "Pocketfader"
$Target = Join-Path $env:LOCALAPPDATA "Programs\YTM Starcast"
$LegacyDir = Join-Path $env:LOCALAPPDATA "Programs\Pocketfader"
$StartMenu = Join-Path $env:APPDATA "Microsoft\Windows\Start Menu\Programs"
$NoAppMark = Join-Path $Target "no-desktop-app"   # this folder was installed with -NoDesktopApp

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

function Test-SameFolder($a, $b) {
    if (-not (Test-Path $a) -or -not (Test-Path $b)) { return $false }
    return (Resolve-Path $a).Path.TrimEnd('\') -eq (Resolve-Path $b).Path.TrimEnd('\')
}

function Get-SettingsFolder {
    $new = Join-Path $env:APPDATA "ytm-starcast"
    $old = Join-Path $env:APPDATA "pocketfader"
    if ((Test-Path $old) -and -not (Test-Path $new)) { return $old }
    return $new
}

function Retire-Pocketfader([switch]$Quiet) {
    # YTM Starcast's old name: its task, its start-at-login entry, its running programs, its shortcut and its folder
    $found = Remove-Task $LegacyTask
    if (Get-ItemProperty -Path $RunKey -Name $LegacyRunValue -ErrorAction SilentlyContinue) {
        Remove-ItemProperty -Path $RunKey -Name $LegacyRunValue -ErrorAction SilentlyContinue
        $found = $true
    }
    $procs = Get-Process -Name "pocketfader", "pocketfader-gui" -ErrorAction SilentlyContinue
    if ($procs) { $procs | Stop-Process -Force -ErrorAction SilentlyContinue; $found = $true; Start-Sleep -Seconds 1 }
    $lnk = Join-Path $StartMenu "Pocketfader.lnk"
    if (Test-Path $lnk) { Remove-Item $lnk -Force -ErrorAction SilentlyContinue; $found = $true }
    if ((Test-Path $LegacyDir) -and -not (Test-SameFolder $LegacyDir $PSScriptRoot)) {
        Remove-Item $LegacyDir -Recurse -Force -ErrorAction SilentlyContinue
        $found = $true
    }
    if ($found -and -not $Quiet) { Write-Host "Replaced Pocketfader (YTM Starcast's old name). Your settings and paired phones carry over." }
}

function Retire-YtmBeacon($starcast) {
    $dir = Join-Path $env:LOCALAPPDATA "ytm-beacon"
    $exe = Join-Path $dir "ytm-beacon.exe"
    if (-not (Test-Path $exe)) { return }
    if ($KeepYtmBeacon) {
        Write-Host "The YTM Beacon app stays installed (-KeepYtmBeacon). YTM Starcast's Discord status waits while it runs."
        return
    }
    Write-Host "Replacing the YTM Beacon app: YTM Starcast shows your music as your Discord status itself now."
    # Its settings first (words never to show, private mode ...). YTM Starcast copies them by itself only the first
    # time it starts, so anything changed in YTM Beacon since then would otherwise be lost.
    $copied = $false
    try {
        # Start-Process -Wait: the program has no console window, so PowerShell wouldn't wait for it otherwise
        $p = Start-Process -FilePath $starcast -ArgumentList "--take-over-ytm-beacon" -NoNewWindow -Wait -PassThru `
            -ErrorAction Stop
        $copied = ($p.ExitCode -eq 0)
    } catch {
        Write-Host "YTM Beacon's settings couldn't be copied to YTM Starcast: $($_.Exception.Message)"
    }
    if (-not $copied) {
        # carrying on with other settings could show songs it was told to keep private
        Write-Host "So the YTM Beacon app stays installed for now. Fix or remove that settings file, then run this again."
        return
    }
    Write-Host "Running YTM Beacon's own uninstaller (it leaves its own settings file in place)."
    # stdin from an empty file, so it never waits for a key press
    $empty = [System.IO.Path]::GetTempFileName()
    try {
        $p = Start-Process -FilePath $exe -ArgumentList "uninstall" -NoNewWindow -Wait -PassThru `
            -RedirectStandardInput $empty -ErrorAction Stop
        if ($p.ExitCode -ne 0) { Write-Host "YTM Beacon's uninstaller ended with code $($p.ExitCode)." }
    } catch {
        Write-Host "YTM Beacon's uninstaller couldn't run: $($_.Exception.Message)"
    } finally {
        Remove-Item $empty -Force -ErrorAction SilentlyContinue
    }
    # it removes its program a moment after it exits
    for ($i = 0; $i -lt 15 -and (Test-Path $exe); $i++) { Start-Sleep -Seconds 1 }
    if (Test-Path $exe) {
        # it didn't finish: at least stop it and take it out of start-at-login
        Stop-Process -Name "ytm-beacon-bg", "ytm-beacon" -Force -ErrorAction SilentlyContinue
        Remove-ItemProperty -Path $RunKey -Name "YTMBeacon" -ErrorAction SilentlyContinue
        Write-Host "YTM Beacon is stopped and no longer starts at login; its files are still in $dir."
    }
    Write-Host "If your browser has the ""YTM Beacon"" extension, remove it on the browser's extensions page;"
    Write-Host "YTM Starcast's extension does its job now."
}

if ($Uninstall) {
    foreach ($t in @($TaskName, $LegacyTask)) { [void](Remove-Task $t) }
    foreach ($v in @($RunValue, $LegacyRunValue)) { Remove-ItemProperty -Path $RunKey -Name $v -ErrorAction SilentlyContinue }
    Get-Process -Name "ytm-starcast", "ytm-starcast-gui" -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
    Retire-Pocketfader -Quiet
    Start-Sleep -Seconds 1
    Remove-Item (Join-Path $StartMenu "YTM Starcast.lnk") -Force -ErrorAction SilentlyContinue
    if (Test-Path $Target) { Remove-Item $Target -Recurse -Force -ErrorAction SilentlyContinue }
    if (Test-Path $Target) { Write-Host "YTM Starcast no longer starts at logon, but some of its files couldn't be removed: delete ""$Target"" yourself." }
    else { Write-Host "YTM Starcast is removed." }
    Write-Host "Your settings are kept in $(Get-SettingsFolder) (delete that folder for a clean slate)."
    # the extension's folder (the browser loads it from there)
    $extData = Join-Path $env:LOCALAPPDATA "ytm-starcast"
    if (Test-Path (Join-Path $extData "extension")) { Remove-Item (Join-Path $extData "extension") -Recurse -Force -ErrorAction SilentlyContinue }
    Write-Host "Remove the YTM Starcast extension from your browser too: open its extensions page and click Remove."
    return
}

$source = Join-Path $PSScriptRoot "ytm-starcast.exe"
if (-not (Test-Path $source)) { throw "ytm-starcast.exe wasn't found next to this script." }

# Installing again, or over Pocketfader: keep the options it was started with, unless new ones are given here.
$oldArgs = Get-TaskArguments $TaskName
if (-not $oldArgs) { $oldArgs = Get-TaskArguments $LegacyTask }
if (-not $PSBoundParameters.ContainsKey("Lan") -and $oldArgs -match '(^|\s)--lan(\s|$)') { $Lan = $true }
if (-not $PSBoundParameters.ContainsKey("Audio") -and $oldArgs -match '--audio\s+voicemeeter') { $Audio = "voicemeeter" }
if (-not $PSBoundParameters.ContainsKey("Port") -and $oldArgs -match '--port\s+(\d{1,5})(\s|$)') {
    $old = [int]$Matches[1]
    if ($old -ge 1 -and $old -le 65535) { $Port = $old }
}
if (-not $PSBoundParameters.ContainsKey("NoDesktopApp") -and (Test-Path $NoAppMark)) { $NoDesktopApp = [switch]$true }

Retire-Pocketfader

# Copy the program to its own folder (unless this script already runs from there).
New-Item -ItemType Directory -Force -Path $Target | Out-Null
if (-not (Test-SameFolder $PSScriptRoot $Target)) {
    Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    Get-Process -Name "ytm-starcast", "ytm-starcast-gui" -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
    Start-Sleep -Milliseconds 500
    $files = @("ytm-starcast.exe", "install-exe.ps1", "README.md", "LICENSE")
    if (-not $NoDesktopApp) { $files += "ytm-starcast-gui.exe" }
    foreach ($f in $files) {
        $from = Join-Path $PSScriptRoot $f
        if (Test-Path $from) { Copy-Item $from -Destination $Target -Force }
    }
    Write-Host "Installed YTM Starcast to $Target."
}
$exe = Join-Path $Target "ytm-starcast.exe"

# The desktop app: a window with the addresses for your phone, the switches, start/stop and the log.
$gui = Join-Path $Target "ytm-starcast-gui.exe"
$shortcut = Join-Path $StartMenu "YTM Starcast.lnk"
if ($NoDesktopApp) {
    Get-Process -Name "ytm-starcast-gui" -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
    Remove-Item $shortcut -Force -ErrorAction SilentlyContinue
    if (Test-Path $gui) { Start-Sleep -Milliseconds 300; Remove-Item $gui -Force -ErrorAction SilentlyContinue }
    Set-Content -Path $NoAppMark -Value "Installed with -NoDesktopApp: updates leave the desktop app out too." -Encoding ASCII
    Write-Host "The desktop app is left out (-NoDesktopApp)."
} else {
    Remove-Item $NoAppMark -Force -ErrorAction SilentlyContinue
    if (Test-Path $gui) {
        $shell = New-Object -ComObject WScript.Shell
        $lnk = $shell.CreateShortcut($shortcut)
        $lnk.TargetPath = $gui
        $lnk.WorkingDirectory = $Target
        $lnk.Description = "YouTube Music companion"
        $lnk.Save()
    }
}

Retire-YtmBeacon $exe

$argsList = "--audio $Audio"
if ($Lan) { $argsList += " --lan" }
if ($Port -ne $DefaultPort) { $argsList += " --port $Port" }

# the program's folder name has a space in it, so the task's command is quoted
$action  = New-ScheduledTaskAction -Execute "`"$exe`"" -Argument $argsList -WorkingDirectory $Target
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
$trigger.Delay = "PT30S"   # give Tailscale time to come up; the server retries either way
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
if ($NoDesktopApp) {
    Write-Host "Its settings, and the address to open on your phone: http://127.0.0.1:$Port/ in a browser on this computer."
} else {
    Write-Host "To see the address to open on your phone, open YTM Starcast from the Start menu."
}
Write-Host "Its messages go to $(Join-Path (Get-SettingsFolder) 'server.log')."

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
Write-Host "(http://127.0.0.1:$Port/#extension)."

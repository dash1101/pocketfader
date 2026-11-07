# Registers Pocketfader to start at logon (Windows hosts).
#
#   .\packaging\install.ps1                         # Windows volume + media controls
#   .\packaging\install.ps1 -Audio voicemeeter      # also use Voicemeeter (EQ, meter) -- optional extra
#   .\packaging\install.ps1 -Lan                    # also listen on your home network (adds an access token)
#   .\packaging\install.ps1 -Uninstall
#
# No admin rights needed. First run installs the Python dependencies for you.

param(
    [ValidateSet("windows", "voicemeeter")][string]$Audio = "windows",
    [switch]$Lan,
    [switch]$Uninstall
)

$TaskName = "Pocketfader"
$Root = Split-Path -Parent $PSScriptRoot

if ($Uninstall) {
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue
    Write-Host "Removed scheduled task '$TaskName'."
    return
}

$py = (Get-Command pythonw.exe -ErrorAction SilentlyContinue).Source
if (-not $py) { throw "Python 3.9+ not found. Install it from python.org (tick 'Add to PATH'), then re-run." }

Write-Host "Installing dependencies..."
& (Get-Command python.exe).Source -m pip install --quiet -e "$Root[windows]"

$argsList = "-m pocketfader --audio $Audio"
if ($Lan) { $argsList += " --lan" }

# Delay 30s so Tailscale (and Voicemeeter, if used) are up first. The server retries either way.
$action  = New-ScheduledTaskAction -Execute $py -Argument $argsList -WorkingDirectory $Root
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

Write-Host "Registered '$TaskName' (starts 30s after logon)."
Write-Host "Start it now with:  Start-ScheduledTask -TaskName $TaskName"
Write-Host "Or run in a window to see the address:  python -m pocketfader --audio $Audio"

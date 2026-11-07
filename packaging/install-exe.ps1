# Starts the standalone Pocketfader.exe at logon (for the Windows download; no Python needed).
#
#   .\install-exe.ps1                    # Windows volume + media controls
#   .\install-exe.ps1 -Lan               # also listen on your home network (adds an access token)
#   .\install-exe.ps1 -Audio voicemeeter # optional Voicemeeter extras (EQ, meter)
#   .\install-exe.ps1 -Uninstall
#
# Keep pocketfader.exe where it is after installing; the task points at this folder.

param(
    [ValidateSet("windows", "voicemeeter")][string]$Audio = "windows",
    [switch]$Lan,
    [switch]$Uninstall
)

$TaskName = "Pocketfader"

if ($Uninstall) {
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue
    Write-Host "Removed scheduled task '$TaskName'."
    return
}

$exe = Join-Path $PSScriptRoot "pocketfader.exe"
if (-not (Test-Path $exe)) { throw "pocketfader.exe not found next to this script." }

$argsList = "--audio $Audio"
if ($Lan) { $argsList += " --lan" }

$action  = New-ScheduledTaskAction -Execute $exe -Argument $argsList -WorkingDirectory $PSScriptRoot
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
$trigger.Delay = "PT30S"   # give Tailscale time to come up; the server retries either way
$principal = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -LogonType Interactive -RunLevel Limited
$settings = New-ScheduledTaskSettingsSet `
    -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -StartWhenAvailable -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) `
    -ExecutionTimeLimit (New-TimeSpan -Seconds 0)

Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger `
    -Principal $principal -Settings $settings -Force | Out-Null

Write-Host "Registered '$TaskName' (starts 30s after logon)."
Write-Host "Start it now with:  Start-ScheduledTask -TaskName $TaskName"
Write-Host "To see the address to open on your phone, run .\pocketfader.exe in a terminal."

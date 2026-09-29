<#
.SYNOPSIS
    Schedules the local CI/CD pipeline (pipeline.ps1) to check main every few minutes, as the current user.

.DESCRIPTION
    Registers the Windows scheduled task "AccountUpgrade-CD". It runs as you, only while you are logged on (the same
    session as Rancher Desktop, so it can use its Docker engine), needs no stored password or admin rights, never
    overlaps itself, and runs without a console window.

    The pipeline script is copied to %LOCALAPPDATA%\account-upgrade\pipeline\bin, so the task does not depend on this
    clone. Run the installer again after changing pipeline.ps1.

        powershell -NoProfile -ExecutionPolicy Bypass -File deploy\install-pipeline.ps1                  # install / update
        powershell -NoProfile -ExecutionPolicy Bypass -File deploy\install-pipeline.ps1 -IntervalMinutes 5
        powershell -NoProfile -ExecutionPolicy Bypass -File deploy\install-pipeline.ps1 -Uninstall
#>
[CmdletBinding()]
param(
    [ValidateRange(1, 1440)]
    [int]$IntervalMinutes = 2,

    [string]$TaskName = 'AccountUpgrade-CD',

    [switch]$Uninstall
)

$ErrorActionPreference = 'Stop'

if ($Uninstall) {
    if (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue) {
        Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
        "Removed scheduled task '$TaskName'. Pipeline state and logs are kept in $env:LOCALAPPDATA\account-upgrade."
    }
    else { "Scheduled task '$TaskName' is not installed." }
    return
}

$binDir = Join-Path $env:LOCALAPPDATA 'account-upgrade\pipeline\bin'
New-Item -ItemType Directory -Force -Path $binDir | Out-Null
$script = Join-Path $binDir 'pipeline.ps1'
Copy-Item -Path (Join-Path $PSScriptRoot 'pipeline.ps1') -Destination $script -Force

$user = "$env:USERDOMAIN\$env:USERNAME"
# conhost --headless: no console window flashing up on every run.
$action = New-ScheduledTaskAction -Execute 'conhost.exe' -Argument (
    "--headless powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File `"$script`"")
# Repeats indefinitely from now on; runs only while the user is logged on.
$trigger = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) -RepetitionInterval (New-TimeSpan -Minutes $IntervalMinutes)
$principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited
$settings = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Hours 1) `
    -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries

Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Principal $principal -Settings $settings `
    -Description 'Account Upgrade local CI/CD: deploys new commits on main to Rancher Desktop (deploy\pipeline.ps1).' `
    -Force | Out-Null

"Installed scheduled task '$TaskName' for ${user}: checks main every $IntervalMinutes minute(s)."
"Status:  powershell -NoProfile -ExecutionPolicy Bypass -File `"$script`" -Status"
"Logs:    $env:LOCALAPPDATA\account-upgrade\pipeline\logs"

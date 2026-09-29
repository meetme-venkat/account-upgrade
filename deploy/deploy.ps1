<#
.SYNOPSIS
    Deploys one release of the Account Upgrade platform to the local Docker engine (Rancher Desktop, dockerd/moby).

.DESCRIPTION
    1. Pulls the backend and frontend images tagged with the release (commit SHA).
    2. Rolls the compose stack forward and waits for every health check (docker compose up --wait).
    3. Smoke tests the running release: a real request through the API, Kafka, PostgreSQL and the email outbox, and
       the UI plus its /api proxy.
    4. If any step fails, rolls back to the images that were running before, and exits non-zero.

    The "cd" workflow runs it on the self-hosted runner. It can also be run by hand, for example to roll back:
        powershell -NoProfile -ExecutionPolicy Bypass -File deploy\deploy.ps1 -Tag <commit sha>
    Locally built images, without a registry:
        docker build -t local/account-upgrade-backend:dev account-upgrade-backend
        docker build -t local/account-upgrade-frontend:dev account-upgrade-frontend
        powershell -NoProfile -ExecutionPolicy Bypass -File deploy\deploy.ps1 -Tag dev -Registry local -SkipPull

    Written for Windows PowerShell 5.1, the version every Windows machine has.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9_][A-Za-z0-9._-]{0,127}$')]
    [string]$Tag,

    [string]$Registry = 'ghcr.io/meetme-venkat',

    # Fixed, so every deployment updates the same stack whatever folder the runner checked out into.
    [string]$ProjectName = 'account-upgrade',

    [switch]$SkipPull,

    [int]$WaitTimeoutSec = 300,

    [int]$SmokeTimeoutSec = 90,

    # Older release images kept locally for fast rollback; the rest are removed.
    [int]$KeepImages = 5
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$composeFile = Join-Path (Split-Path -Parent $PSScriptRoot) 'docker-compose.yml'
$compose = @('compose', '-p', $ProjectName, '-f', $composeFile)
$backendImage = "$Registry/account-upgrade-backend:$Tag"
$frontendImage = "$Registry/account-upgrade-frontend:$Tag"
# 127.0.0.1, not localhost: on Windows localhost resolves to ::1 first, which WSL's relay can hold without forwarding.
$apiUrl = 'http://127.0.0.1:8080'
$uiUrl = 'http://127.0.0.1:4200'

function Write-Step([string]$Message) {
    Write-Host "==> $Message" -ForegroundColor Cyan
}

# Native commands never throw in PowerShell 5.1, and with 'Stop' their stderr (docker's progress output) would.
# So: run with 'Continue' and fail on the exit code instead.
function Invoke-Docker {
    $ErrorActionPreference = 'Continue'
    & docker @args | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "docker $($args -join ' ') failed with exit code $LASTEXITCODE" }
}

# Stdout of a docker command, or $null if it fails.
function Get-DockerOutput {
    $ErrorActionPreference = 'Continue'
    $out = & docker @args 2>$null
    if ($LASTEXITCODE -ne 0) { return $null }
    return $out
}

function Get-RunningImage([string]$Service) {
    $id = Get-DockerOutput @compose ps -q $Service | Select-Object -First 1
    if (-not $id) { return $null }
    return Get-DockerOutput inspect --format '{{.Config.Image}}' $id
}

# Another compose project (for example a stack started by hand from a clone) holding the ports would make the
# rollout fail halfway; stop before touching anything instead.
function Assert-PortsFree {
    foreach ($port in 8080, 4200) {
        # No quoted template (PowerShell 5.1 mangles embedded quotes in native arguments): match the label list.
        $owners = @(Get-DockerOutput ps --filter "publish=$port" --format '{{.Names}}|{{.Labels}}') |
            Where-Object { $_ -and $_ -notmatch "(^|[|,])com\.docker\.compose\.project=$([regex]::Escape($ProjectName))(,|$)" } |
            ForEach-Object { $_.Split('|')[0] }
        if ($owners) {
            throw "Port $port is used by container(s) outside project '$ProjectName': $($owners -join ', '). " +
                  "Stop them first, e.g. 'docker compose -p <project> down'."
        }
    }
}

function Start-Release([string]$Backend, [string]$Frontend) {
    $env:BACKEND_IMAGE = $Backend
    $env:FRONTEND_IMAGE = $Frontend
    Invoke-Docker @compose up -d --no-build --remove-orphans --wait --wait-timeout $WaitTimeoutSec
}

# Invoke-RestMethod in 5.1 returns a JSON array as one object; unroll it. PowerShell unwraps a one-item array on
# return, so callers wrap the result in @() again.
function Get-Json([string]$Url) {
    return @(Invoke-RestMethod -Uri $Url -TimeoutSec 10 | ForEach-Object { $_ })
}

function Test-Release {
    $health = Invoke-RestMethod -Uri "$apiUrl/actuator/health/readiness" -TimeoutSec 10
    if ($health.status -ne 'UP') { throw "Backend readiness is '$($health.status)'" }

    $ui = Invoke-WebRequest -Uri "$uiUrl/healthz" -UseBasicParsing -TimeoutSec 10
    if ($ui.StatusCode -ne 200) { throw "Frontend /healthz returned $($ui.StatusCode)" }

    # Eligible request: must come back ELIGIBLE with its email delivered, i.e. the whole event pipeline works.
    $userId = 'deploy-smoke-' + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $body = @{ userId = $userId; userName = 'Deploy Smoke'; age = 20; balance = 50 } | ConvertTo-Json
    Invoke-RestMethod -Method Post -Uri "$apiUrl/api/realtime-upgrade" -ContentType 'application/json' `
        -Body $body -TimeoutSec 15 | Out-Null

    $deadline = (Get-Date).AddSeconds($SmokeTimeoutSec)
    while ($true) {
        $rows = @(Get-Json "$apiUrl/api/processed-upgrades?userId=$userId")
        if ($rows.Count -gt 0 -and $rows[0].status -eq 'ELIGIBLE' -and $rows[0].notificationSent) { break }
        if ((Get-Date) -gt $deadline) {
            throw "Smoke request $userId was not processed and notified within $SmokeTimeoutSec s (last: $($rows | ConvertTo-Json -Compress))"
        }
        Start-Sleep -Seconds 1
    }

    # The UI's reverse proxy reaches the new backend.
    $viaUi = @(Get-Json "$uiUrl/api/processed-upgrades?userId=$userId")
    if ($viaUi.Count -ne 1) { throw "Frontend /api proxy did not return the smoke request" }
    Write-Host "Smoke test passed ($userId)"
}

function Remove-OldImages([string[]]$Keep) {
    foreach ($repo in "$Registry/account-upgrade-backend", "$Registry/account-upgrade-frontend") {
        # Newest first.
        @(Get-DockerOutput images $repo --format '{{.Repository}}:{{.Tag}}') |
            Where-Object { $_ -and $_ -notmatch ':<none>$' -and $Keep -notcontains $_ } |
            Select-Object -Skip $KeepImages |
            ForEach-Object { Get-DockerOutput rmi $_ | Out-Null }
    }
}

function Write-Record([string]$Result, [string]$Detail) {
    $line = '{0:u}  {1,-11} {2}  {3}' -f (Get-Date).ToUniversalTime(), $Result, $Tag, $Detail
    $logDir = Join-Path $env:LOCALAPPDATA 'account-upgrade'
    New-Item -ItemType Directory -Force -Path $logDir | Out-Null
    Add-Content -Path (Join-Path $logDir 'deployments.log') -Value $line -Encoding utf8
    if ($env:GITHUB_STEP_SUMMARY) {
        Add-Content -Path $env:GITHUB_STEP_SUMMARY -Encoding utf8 -Value @(
            "### Deployment: $Result", '',
            "| | |", "|---|---|",
            "| Release | ``$Tag`` |",
            "| Backend | ``$backendImage`` |",
            "| Frontend | ``$frontendImage`` |",
            "| Detail | $Detail |")
    }
}

Write-Step "Deploying release $Tag to project '$ProjectName'"
Invoke-Docker version --format 'Docker engine {{.Server.Version}}'

$previousBackend = Get-RunningImage 'account-upgrade-backend'
$previousFrontend = Get-RunningImage 'account-upgrade-frontend'
if ($previousBackend) { Write-Host "Currently running: $previousBackend, $previousFrontend" }
else { Write-Host 'No release running yet (first deployment)' }

Assert-PortsFree

if (-not $SkipPull) {
    Write-Step 'Pulling images'
    Invoke-Docker pull $backendImage
    Invoke-Docker pull $frontendImage
}

try {
    Write-Step 'Rolling out and waiting for health checks'
    Start-Release $backendImage $frontendImage
    Write-Step 'Smoke testing'
    Test-Release
}
catch {
    $failure = $_.Exception.Message
    Write-Host "::error::Release $Tag failed: $failure"
    Write-Step 'Recent logs'
    $ErrorActionPreference = 'Continue'
    & docker @compose logs --tail 100 account-upgrade-backend account-upgrade-frontend | Out-Host
    $ErrorActionPreference = 'Stop'

    $canRollBack = $previousBackend -and $previousFrontend -and
        ($previousBackend -ne $backendImage -or $previousFrontend -ne $frontendImage)
    if (-not $canRollBack) {
        Write-Record 'FAILED' "$failure (nothing to roll back to)"
        exit 1
    }
    Write-Step "Rolling back to $previousBackend, $previousFrontend"
    try {
        Start-Release $previousBackend $previousFrontend
        Test-Release
        Write-Record 'ROLLED BACK' "$failure. Restored $previousBackend"
    }
    catch {
        Write-Host "::error::Rollback failed too: $($_.Exception.Message)"
        Write-Record 'FAILED' "$failure. Rollback failed: $($_.Exception.Message)"
    }
    exit 1
}

Remove-OldImages -Keep @($backendImage, $frontendImage, $previousBackend, $previousFrontend)
Write-Record 'DEPLOYED' "UI $uiUrl, API $apiUrl"
Write-Step "Release $Tag is live: UI $uiUrl, API $apiUrl"

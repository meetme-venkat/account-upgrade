<#
.SYNOPSIS
    Deploys one release of the Account Upgrade platform to Kubernetes (Rancher Desktop's k3s by default).

.DESCRIPTION
    1. Renders the manifests in deploy/k8s/base with the release's images (<registry>/<service>:<tag>) and creates the
       secret account-upgrade-secrets on the first deployment (random PostgreSQL password and JWT signing key).
    2. Rolls the release out one component at a time, waiting for each:
         PostgreSQL and Kafka ready -> schema Job (Liquibase) succeeded -> backend rolled out -> frontend rolled out.
       A failed schema Job fails the release before the backend is touched. Backend and frontend run 2 replicas
       each and are replaced one pod at a time (a new pod must be ready first), so a deployment has no downtime.
    3. Smoke tests the running release: the API refuses a request without a token; logging in works; a real request
       goes through the API, Kafka, PostgreSQL and the email outbox; the UI and its /api proxy work with the token.
    4. If any step fails, rolls back to the images that were running before, and exits non-zero.

    pipeline.ps1 runs it. It can also be run by hand, for example to roll back:
        powershell -NoProfile -ExecutionPolicy Bypass -File deploy\deploy.ps1 -Tag <commit sha>
    Locally built images, without a registry (Rancher Desktop's Kubernetes sees the images of its Docker engine):
        docker build -t local/account-update-db-schema:dev account-update-db-schema
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

    # Named explicitly on every kubectl call, so a deployment never lands in whatever cluster is current.
    [string]$Context = $(if ($env:ACCOUNT_UPGRADE_KUBE_CONTEXT) { $env:ACCOUNT_UPGRADE_KUBE_CONTEXT } else { 'rancher-desktop' }),

    # Must match the namespace in deploy/k8s/base/kustomization.yaml.
    [string]$Namespace = 'account-upgrade',

    # Don't pre-pull the images with docker (the cluster pulls what it doesn't have).
    [switch]$SkipPull,

    [int]$WaitTimeoutSec = 300,

    [int]$SmokeTimeoutSec = 90,

    # Older release images kept locally for fast rollback; the rest are removed.
    [int]$KeepImages = 5,

    # Credentials the smoke test logs in with (the API needs an access token).
    [string]$AdminUsername = $(if ($env:UPGRADE_SECURITY_ADMIN_USERNAME) { $env:UPGRADE_SECURITY_ADMIN_USERNAME } else { 'admin' }),

    [string]$AdminPassword = $(if ($env:UPGRADE_SECURITY_ADMIN_PASSWORD) { $env:UPGRADE_SECURITY_ADMIN_PASSWORD } else { 'admin' })
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$manifests = Join-Path $PSScriptRoot 'k8s'
$overlay = Join-Path $manifests 'release'
$kubectl = @('--context', $Context, '--namespace', $Namespace)
$services = 'account-update-db-schema', 'account-upgrade-backend', 'account-upgrade-frontend'
$schemaImage = "$Registry/account-update-db-schema:$Tag"
$backendImage = "$Registry/account-upgrade-backend:$Tag"
$frontendImage = "$Registry/account-upgrade-frontend:$Tag"
# 127.0.0.1, not localhost: on Windows localhost resolves to ::1 first, which WSL's relay can hold without forwarding.
$apiUrl = 'http://127.0.0.1:8080'
$uiUrl = 'http://127.0.0.1:4200'

function Write-Step([string]$Message) {
    Write-Host "==> $Message" -ForegroundColor Cyan
}

# Native commands never throw in PowerShell 5.1, and with 'Stop' their stderr (progress output) would.
# So: run with 'Continue' and fail on the exit code instead.
function Invoke-Kubectl {
    $ErrorActionPreference = 'Continue'
    & kubectl @kubectl @args 2>&1 | ForEach-Object { "$_" } | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "kubectl $($args -join ' ') failed with exit code $LASTEXITCODE" }
}

# Stdout of a kubectl command, or $null if it fails.
function Get-KubectlOutput {
    $ErrorActionPreference = 'Continue'
    $out = & kubectl @kubectl @args 2>$null
    if ($LASTEXITCODE -ne 0) { return $null }
    return $out
}

function Test-Docker {
    $ErrorActionPreference = 'Continue'
    & docker version --format '{{.Server.Version}}' 2>$null | Out-Null
    return $LASTEXITCODE -eq 0
}

function Invoke-Docker {
    $ErrorActionPreference = 'Continue'
    & docker @args | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "docker $($args -join ' ') failed with exit code $LASTEXITCODE" }
}

function Get-DockerOutput {
    $ErrorActionPreference = 'Continue'
    $out = & docker @args 2>$null
    if ($LASTEXITCODE -ne 0) { return $null }
    return $out
}

# The image a workload currently runs, or $null (not deployed yet).
function Get-RunningImage([string]$Kind, [string]$Name) {
    $image = Get-KubectlOutput get $Kind $Name -o 'jsonpath={.spec.template.spec.containers[0].image}'
    if ($image) { return "$image".Trim() }
    return $null
}

# The docker compose stack this platform used to run as (or one started by hand from a clone) publishes the same
# ports; the Kubernetes services could not bind them. Stop before touching anything instead.
function Assert-NoComposeStack {
    if (-not (Test-Docker)) { return }
    foreach ($port in 8080, 4200) {
        # No quoted template (PowerShell 5.1 mangles embedded quotes in native arguments): match the label list.
        $owners = @(Get-DockerOutput ps --filter "publish=$port" --filter 'label=com.docker.compose.project' --format '{{.Names}}|{{.Labels}}') |
            Where-Object { $_ } |
            ForEach-Object {
                $project = if ($_ -match '(^|[|,])com\.docker\.compose\.project=([^,]+)') { $Matches[2] } else { '?' }
                "$($_.Split('|')[0]) (compose project '$project')"
            }
        if ($owners) {
            throw "Port $port is published by docker compose: $($owners -join ', '). " +
                  "Stop that stack first, e.g. 'docker compose -p <project> down' (its data volume is kept)."
        }
    }
}

# Created once with random values, then kept: PostgreSQL only reads its password when it initialises the volume,
# and a new JWT key would sign everyone out.
function Initialize-Secrets {
    if (Get-KubectlOutput get secret account-upgrade-secrets -o name) { return }
    Write-Step 'Creating secret account-upgrade-secrets (random PostgreSQL password and JWT signing key)'
    $bytes = New-Object byte[] 48
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $rng.GetBytes($bytes)
    $jwt = [Convert]::ToBase64String($bytes)
    $rng.GetBytes($bytes)
    # Hex: safe in a JDBC connection and in psql without quoting.
    $postgres = -join ($bytes[0..23] | ForEach-Object { $_.ToString('x2') })
    $rng.Dispose()
    Invoke-Kubectl create secret generic account-upgrade-secrets `
        --from-literal=postgres-password=$postgres --from-literal=jwt-secret=$jwt
    Invoke-Kubectl label secret account-upgrade-secrets app.kubernetes.io/part-of=account-upgrade `
        app.kubernetes.io/component=platform
}

# A kustomize overlay of deploy/k8s/base that pins the release's images (the base only has placeholders).
function Write-Overlay([string]$Schema, [string]$Backend, [string]$Frontend) {
    New-Item -ItemType Directory -Force -Path $overlay | Out-Null
    $images = @{ 'account-update-db-schema' = $Schema; 'account-upgrade-backend' = $Backend; 'account-upgrade-frontend' = $Frontend }
    $lines = @('# Generated by deploy/deploy.ps1 for one release. Not committed.',
        'apiVersion: kustomize.config.k8s.io/v1beta1', 'kind: Kustomization', 'resources:', '  - ../base', 'images:')
    foreach ($name in $services) {
        $image = $images[$name]
        $split = $image.LastIndexOf(':')
        $lines += "  - name: $name", "    newName: $($image.Substring(0, $split))", "    newTag: '$($image.Substring($split + 1))'"
    }
    # No BOM: kustomize reads it as part of the first key.
    [System.IO.File]::WriteAllLines((Join-Path $overlay 'kustomization.yaml'), [string[]]$lines)
}

function Invoke-ApplyComponent([string]$Component) {
    Invoke-Kubectl apply -k $overlay --selector "app.kubernetes.io/component=$Component"
}

# Succeeded, failed, or still running past the deadline (the Job's own backoff retries count as running).
function Wait-SchemaJob {
    $deadline = (Get-Date).AddSeconds($WaitTimeoutSec)
    while ($true) {
        # No quoted filter in the template: PowerShell 5.1 mangles embedded quotes in native arguments.
        $conditions = "$(Get-KubectlOutput get job account-update-db-schema -o 'jsonpath={range .status.conditions[*]}{.type}={.status};{end}')"
        if ($conditions -match '(^|;)Complete=True;') { return }
        if ($conditions -match '(^|;)Failed=True;') { throw 'The schema job failed (see its logs below)' }
        if ((Get-Date) -gt $deadline) { throw "The schema job did not complete within $WaitTimeoutSec s" }
        Start-Sleep -Seconds 2
    }
}

function Start-Release([string]$Schema, [string]$Backend, [string]$Frontend) {
    Write-Overlay $Schema $Backend $Frontend
    Invoke-ApplyComponent 'platform'
    Initialize-Secrets
    foreach ($component in 'postgres', 'kafka') { Invoke-ApplyComponent $component }
    Invoke-Kubectl rollout status statefulset/postgres --timeout "${WaitTimeoutSec}s"
    Invoke-Kubectl rollout status statefulset/kafka --timeout "${WaitTimeoutSec}s"

    Write-Host "Schema job: $Schema"
    Invoke-Kubectl delete job account-update-db-schema --ignore-not-found --wait=true
    Invoke-ApplyComponent 'db-schema'
    Wait-SchemaJob

    Write-Host "Backend: $Backend"
    Invoke-ApplyComponent 'backend'
    Invoke-Kubectl rollout status deployment/account-upgrade-backend --timeout "${WaitTimeoutSec}s"

    Write-Host "Frontend: $Frontend"
    Invoke-ApplyComponent 'frontend'
    Invoke-Kubectl rollout status deployment/account-upgrade-frontend --timeout "${WaitTimeoutSec}s"
}

# Invoke-RestMethod in 5.1 returns a JSON array as one object; unroll it. PowerShell unwraps a one-item array on
# return, so callers wrap the result in @() again.
function Get-Json([string]$Url, [hashtable]$Headers = @{}) {
    return @(Invoke-RestMethod -Uri $Url -Headers $Headers -TimeoutSec 10 | ForEach-Object { $_ })
}

# Status code of a request that is expected to fail (Invoke-WebRequest throws on 4xx in PowerShell 5.1).
function Get-StatusCode([string]$Url) {
    try { return [int](Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 10).StatusCode }
    catch [System.Net.WebException] {
        if ($_.Exception.Response) { return [int]$_.Exception.Response.StatusCode }
        throw
    }
}

function Test-Release {
    # Rancher Desktop publishes a LoadBalancer port on the host a few seconds after the Service is created; until
    # then connections are refused or closed.
    $deadline = (Get-Date).AddSeconds(60)
    while ($true) {
        try {
            $health = Invoke-RestMethod -Uri "$apiUrl/actuator/health/readiness" -TimeoutSec 10
            $ui = Invoke-WebRequest -Uri "$uiUrl/healthz" -UseBasicParsing -TimeoutSec 10
            break
        }
        catch [System.Net.WebException] {
            if ((Get-Date) -gt $deadline) { throw "Not reachable on the host: $($_.Exception.Message)" }
        }
        Start-Sleep -Seconds 2
    }
    if ($health.status -ne 'UP') { throw "Backend readiness is '$($health.status)'" }
    if ($ui.StatusCode -ne 200) { throw "Frontend /healthz returned $($ui.StatusCode)" }

    # The API is protected: no token, no data. Then log in as the UI does.
    $anonymous = Get-StatusCode "$apiUrl/api/processed-upgrades"
    if ($anonymous -ne 401) { throw "The API answered $anonymous to a request without an access token (expected 401)" }
    $credentials = @{ username = $AdminUsername; password = $AdminPassword } | ConvertTo-Json
    $login = Invoke-RestMethod -Method Post -Uri "$apiUrl/api/auth/login" -ContentType 'application/json' `
        -Body $credentials -TimeoutSec 15
    $auth = @{ Authorization = "Bearer $($login.accessToken)" }

    # Eligible request: must come back ELIGIBLE with its email delivered, i.e. the whole event pipeline works.
    $userId = 'deploy-smoke-' + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $body = @{ userId = $userId; userName = 'Deploy Smoke'; age = 20; balance = 50 } | ConvertTo-Json
    Invoke-RestMethod -Method Post -Uri "$apiUrl/api/realtime-upgrade" -ContentType 'application/json' `
        -Headers $auth -Body $body -TimeoutSec 15 | Out-Null

    $deadline = (Get-Date).AddSeconds($SmokeTimeoutSec)
    while ($true) {
        $rows = @(Get-Json "$apiUrl/api/processed-upgrades?userId=$userId" $auth)
        if ($rows.Count -gt 0 -and $rows[0].status -eq 'ELIGIBLE' -and $rows[0].notificationSent) { break }
        if ((Get-Date) -gt $deadline) {
            throw "Smoke request $userId was not processed and notified within $SmokeTimeoutSec s (last: $($rows | ConvertTo-Json -Compress))"
        }
        Start-Sleep -Seconds 1
    }

    # The UI's reverse proxy reaches the new backend, passing the token on.
    $viaUi = @(Get-Json "$uiUrl/api/processed-upgrades?userId=$userId" $auth)
    if ($viaUi.Count -ne 1) { throw "Frontend /api proxy did not return the smoke request" }
    Write-Host "Smoke test passed ($userId)"
}

function Write-RecentLogs {
    $ErrorActionPreference = 'Continue'
    & kubectl @kubectl get pods -o wide 2>&1 | Out-Host
    foreach ($component in 'db-schema', 'backend', 'frontend') {
        & kubectl @kubectl logs --selector "app.kubernetes.io/component=$component" --all-containers --prefix `
            --tail 100 --max-log-requests 10 2>&1 | Out-Host
    }
    $ErrorActionPreference = 'Stop'
}

# Only with the Docker engine (Rancher Desktop's moby), whose images the cluster runs.
function Remove-OldImages([string[]]$Keep) {
    if (-not (Test-Docker)) { return }
    foreach ($repo in $services | ForEach-Object { "$Registry/$_" }) {
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
            "| Database schema | ``$schemaImage`` |",
            "| Backend | ``$backendImage`` |",
            "| Frontend | ``$frontendImage`` |",
            "| Detail | $Detail |")
    }
}

Write-Step "Deploying release $Tag to namespace '$Namespace' (context '$Context')"
$ErrorActionPreference = 'Continue'
$server = & kubectl --context $Context version -o json 2>$null | Out-String
$ErrorActionPreference = 'Stop'
if ($LASTEXITCODE -ne 0) {
    throw "Kubernetes context '$Context' is not reachable. Enable Kubernetes in Rancher Desktop (Preferences -> Kubernetes), or pass -Context."
}
Write-Host "Kubernetes $(($server | ConvertFrom-Json).serverVersion.gitVersion)"

# Schema changes only go forward: rolling back re-runs the previous schema image, which finds nothing to do.
$previousSchema = Get-RunningImage 'job' 'account-update-db-schema'
if (-not $previousSchema) { $previousSchema = $schemaImage }
$previousBackend = Get-RunningImage 'deployment' 'account-upgrade-backend'
$previousFrontend = Get-RunningImage 'deployment' 'account-upgrade-frontend'
if ($previousBackend) { Write-Host "Currently running: $previousBackend, $previousFrontend" }
else { Write-Host 'No release running yet (first deployment)' }

Assert-NoComposeStack

if (-not $SkipPull -and (Test-Docker)) {
    Write-Step 'Pulling images'
    Invoke-Docker pull $schemaImage
    Invoke-Docker pull $backendImage
    Invoke-Docker pull $frontendImage
}

try {
    Write-Step 'Rolling out, in order, and waiting for each component'
    Start-Release $schemaImage $backendImage $frontendImage
    Write-Step 'Smoke testing'
    Test-Release
}
catch {
    $failure = $_.Exception.Message
    Write-Host "::error::Release $Tag failed: $failure"
    Write-Step 'Pods and recent logs'
    Write-RecentLogs

    $canRollBack = $previousBackend -and $previousFrontend -and
        ($previousBackend -ne $backendImage -or $previousFrontend -ne $frontendImage)
    if (-not $canRollBack) {
        Write-Record 'FAILED' "$failure (nothing to roll back to)"
        exit 1
    }
    Write-Step "Rolling back to $previousBackend, $previousFrontend"
    try {
        Start-Release $previousSchema $previousBackend $previousFrontend
        Test-Release
        Write-Record 'ROLLED BACK' "$failure. Restored $previousBackend"
    }
    catch {
        Write-Host "::error::Rollback failed too: $($_.Exception.Message)"
        Write-Record 'FAILED' "$failure. Rollback failed: $($_.Exception.Message)"
    }
    exit 1
}

Remove-OldImages -Keep @($schemaImage, $backendImage, $frontendImage, $previousSchema, $previousBackend, $previousFrontend)
Write-Record 'DEPLOYED' "UI $uiUrl, API $apiUrl"
Write-Step "Release $Tag is live: UI $uiUrl, API $apiUrl"

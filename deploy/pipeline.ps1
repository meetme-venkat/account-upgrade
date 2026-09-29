<#
.SYNOPSIS
    Local CI/CD pipeline: tests, builds, scans and deploys the latest commit of main to Rancher Desktop.

.DESCRIPTION
    Runs entirely on this machine. GitHub is only where the code comes from: no runner, no registry.

      git fetch ─► new commit on main? ─► clean checkout ─► backend tests (mvnw verify)
        ─► build images (the frontend's tests and budgeted build run inside its image build)
        ─► Trivy scan (no fixable CRITICAL vulnerabilities)
        ─► deploy.ps1 (compose up --wait, smoke test, rollback to the previous release on failure)

    Each call handles at most one commit and returns. install-pipeline.ps1 schedules it every few minutes, so a push
    to main goes live within minutes. A commit that fails is not retried until main moves on (or -Force).

    By hand:
        powershell -NoProfile -ExecutionPolicy Bypass -File deploy\pipeline.ps1                # deploy main if new
        powershell -NoProfile -ExecutionPolicy Bypass -File deploy\pipeline.ps1 -Commit <sha>  # redeploy / roll back
        powershell -NoProfile -ExecutionPolicy Bypass -File deploy\pipeline.ps1 -Status        # what is deployed

    Needs: git, a JDK 17+ on PATH (backend tests), and Rancher Desktop running with the dockerd (moby) engine.
    Written for Windows PowerShell 5.1.
#>
[CmdletBinding()]
param(
    [string]$RepoUrl = 'https://github.com/meetme-venkat/account-upgrade.git',

    [string]$Branch = 'main',

    # Deploy this commit instead of the head of the branch (rollback or redeploy). Implies -Force.
    [string]$Commit,

    # Run even if the commit is already deployed or already failed.
    [switch]$Force,

    [switch]$SkipTests,

    [switch]$SkipScan,

    [switch]$Status,

    [string]$StateDir = (Join-Path $env:LOCALAPPDATA 'account-upgrade\pipeline'),

    [int]$KeepLogs = 50
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoDir = Join-Path $StateDir 'repo'
$logDir = Join-Path $StateDir 'logs'
$stateFile = Join-Path $StateDir 'state.json'
$historyFile = Join-Path (Split-Path -Parent $StateDir) 'deployments.log'   # shared with deploy.ps1
$services = 'account-upgrade-backend', 'account-upgrade-frontend'
$trivyImage = 'aquasec/trivy:0.74.0'
New-Item -ItemType Directory -Force -Path $StateDir, $logDir | Out-Null

function Write-Stage([string]$Name) {
    Write-Host ''
    Write-Host ("==> [{0:HH:mm:ss}] {1}" -f (Get-Date), $Name) -ForegroundColor Cyan
}

# Runs a native command, streaming its output (stderr included) to the host and the transcript.
# Native commands never throw in PowerShell 5.1, so fail on the exit code.
function Invoke-Native([string]$Exe, [string[]]$Arguments, [string]$WorkingDirectory = $repoDir) {
    $ErrorActionPreference = 'Continue'
    Push-Location $WorkingDirectory
    try {
        & $Exe @Arguments 2>&1 | ForEach-Object { "$_" } | Out-Host
        if ($LASTEXITCODE -ne 0) { throw "$(Split-Path -Leaf $Exe) $($Arguments -join ' ') failed with exit code $LASTEXITCODE" }
    }
    finally { Pop-Location }
}

function Get-NativeOutput([string]$Exe, [string[]]$Arguments, [string]$WorkingDirectory = $repoDir) {
    $ErrorActionPreference = 'Continue'
    Push-Location $WorkingDirectory
    try {
        $out = & $Exe @Arguments 2>$null
        if ($LASTEXITCODE -ne 0) { throw "$(Split-Path -Leaf $Exe) $($Arguments -join ' ') failed with exit code $LASTEXITCODE" }
        return $out
    }
    finally { Pop-Location }
}

function Get-State {
    if (Test-Path $stateFile) { return Get-Content $stateFile -Raw | ConvertFrom-Json }
    return $null
}

function Save-State([string]$Sha, [string]$Result, [string]$Log) {
    $previous = Get-State
    $deployed = if ($Result -eq 'DEPLOYED') { $Sha } elseif ($previous) { $previous.deployedCommit } else { $null }
    [pscustomobject]@{
        deployedCommit = $deployed
        lastCommit     = $Sha
        lastResult     = $Result
        lastRunAt      = (Get-Date).ToUniversalTime().ToString('o')
        lastLog        = $Log
    } | ConvertTo-Json | Set-Content -Path $stateFile -Encoding utf8
}

# Polling problems (network, Docker not started) go here instead of a run log, which only exists for real runs.
function Write-PollLog([string]$Message) {
    $pollLog = Join-Path $StateDir 'poll.log'
    Add-Content -Path $pollLog -Encoding utf8 -Value ('{0:u}  {1}' -f (Get-Date).ToUniversalTime(), $Message)
    if ((Get-Item $pollLog).Length -gt 1MB) { Get-Content $pollLog -Tail 500 | Set-Content $pollLog -Encoding utf8 }
}

if ($Status) {
    $state = Get-State
    if ($state) { $state | Format-List } else { 'No pipeline run yet.' }
    if (Test-Path $historyFile) { 'Recent deployments:'; Get-Content $historyFile -Tail 10 }
    return
}

# One run at a time: the lock is held until this process exits.
try { $lock = [System.IO.File]::Open((Join-Path $StateDir 'pipeline.lock'), 'OpenOrCreate', 'ReadWrite', 'None') }
catch { Write-Host 'Another pipeline run is in progress.'; return }

try {
    # Nothing can be built or deployed without the engine; try again next time rather than fail the commit.
    $null = Get-NativeOutput docker @('version', '--format', '{{.Server.Version}}') $StateDir

    if (-not (Test-Path (Join-Path $repoDir '.git'))) {
        Invoke-Native git @('clone', '--quiet', '--no-checkout', $RepoUrl, $repoDir) $StateDir
    }
    Invoke-Native git @('fetch', '--quiet', '--prune', 'origin', "+refs/heads/${Branch}:refs/remotes/origin/$Branch")

    $target = if ($Commit) { Get-NativeOutput git @('rev-parse', '--verify', "$Commit^{commit}") }
              else { Get-NativeOutput git @('rev-parse', "origin/$Branch") }
}
catch {
    Write-PollLog "Skipped: $($_.Exception.Message)"
    Write-Host "Skipped: $($_.Exception.Message)"
    return
}

$state = Get-State
if ($state -and -not $Force -and -not $Commit) {
    if ($state.deployedCommit -eq $target) {
        Write-Host "Up to date: $target is deployed."
        return
    }
    if ($state.lastCommit -eq $target) {
        Write-Host "Not retrying $target, its last run ended with '$($state.lastResult)' (log: $($state.lastLog)). Push a fix, or use -Force."
        return
    }
}

$short = $target.Substring(0, 8)
$logFile = Join-Path $logDir ('{0:yyyyMMdd-HHmmss}-{1}.log' -f (Get-Date), $short)
Start-Transcript -Path $logFile | Out-Null
$timer = [System.Diagnostics.Stopwatch]::StartNew()
$stage = 'checkout'
$result = 'DEPLOYED'
try {
    Write-Stage "Pipeline for $target"
    Invoke-Native git @('checkout', '--quiet', '--force', '--detach', $target)
    Invoke-Native git @('clean', '-ffdxq')
    Get-NativeOutput git @('log', '-1', '--format=%h %an, %ad: %s', '--date=iso') | Out-Host

    $deployScript = Join-Path $repoDir 'deploy\deploy.ps1'
    if (-not (Test-Path $deployScript)) { throw "Commit $short predates the pipeline (no deploy\deploy.ps1) and cannot be deployed by it" }

    if ($SkipTests) { Write-Stage 'Backend tests SKIPPED (-SkipTests)' }
    else {
        $stage = 'test'
        Write-Stage 'Backend tests, coverage gate and enforcer rules (mvnw verify)'
        $backendDir = Join-Path $repoDir 'account-upgrade-backend'
        Invoke-Native (Join-Path $backendDir 'mvnw.cmd') @('-B', '-ntp', 'verify') $backendDir
    }

    $stage = 'build'
    foreach ($service in $services) {
        Write-Stage "Building local/${service}:$short"
        Invoke-Native docker @('build', '--progress=plain',
            '--label', "org.opencontainers.image.revision=$target",
            '--label', "org.opencontainers.image.source=$RepoUrl",
            '-t', "local/${service}:$target", $service)
    }

    if ($SkipScan) { Write-Stage 'Vulnerability scan SKIPPED (-SkipScan)' }
    else {
        $stage = 'scan'
        foreach ($service in $services) {
            Write-Stage "Scanning local/${service}:$short (fail on fixable CRITICAL)"
            Invoke-Native docker @('run', '--rm',
                '-v', '//var/run/docker.sock:/var/run/docker.sock',
                '-v', 'account-upgrade-trivy-cache:/root/.cache/',
                $trivyImage, 'image', '--scanners', 'vuln', '--severity', 'CRITICAL', '--ignore-unfixed',
                '--exit-code', '1', '--no-progress', "local/${service}:$target")
        }
    }

    # The commit's own deploy script and compose file, so the stack definition always matches its images.
    $stage = 'deploy'
    Write-Stage 'Deploying'
    Invoke-Native powershell @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $deployScript,
        '-Tag', $target, '-Registry', 'local', '-SkipPull')
}
catch {
    $result = "FAILED ($stage)"
    Write-Host "Pipeline failed at stage '$stage': $($_.Exception.Message)" -ForegroundColor Red
    # deploy.ps1 records its own outcome (including rollbacks); record failures before it.
    if ($stage -ne 'deploy') {
        Add-Content -Path $historyFile -Encoding utf8 -Value (
            '{0:u}  {1,-11} {2}  {3}' -f (Get-Date).ToUniversalTime(), 'FAILED', $target, "pipeline stage '$stage', log $logFile")
    }
}
finally {
    Write-Stage ("Result: {0} for {1} in {2:mm\:ss}" -f $result, $short, $timer.Elapsed)
    Save-State $target $result $logFile
    Stop-Transcript | Out-Null
    Get-ChildItem $logDir -Filter *.log | Sort-Object LastWriteTime -Descending | Select-Object -Skip $KeepLogs |
        Remove-Item -Force
    $lock.Dispose()
}

if ($result -ne 'DEPLOYED') { exit 1 }

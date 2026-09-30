<#
.SYNOPSIS
    Validates the running Account Upgrade stack end to end: infrastructure, authentication, business rules,
    notifications and guardrails. Prints PASS/FAIL per check and exits 1 if any check fails.

.DESCRIPTION
    Read-only on the infrastructure; on the API it logs in and submits a handful of uniquely named test requests
    (user IDs start with "validate-<timestamp>-"), which stay in the database like any other request.

      Infrastructure  (Kubernetes) workloads fully ready, schema job succeeded, schema changelog applied, Kafka
                      topics replicated, dead-letter queue empty, consumer lag 0
      Authentication  no token 401, wrong password 401, admin login returns a token, token 200, tampered token 401
      Ingestion       batch and real-time requests accepted
      Validation      missing userId, invalid email, empty batch, malformed JSON, unknown field: 400 each
      Processing      every request processed and notified; age 18/23 and $30 eligible, age 17/24 and $29.99 not;
                      all failure reasons recorded; an Idempotency-Key sent 3 times is stored once; filters work
      Notifications   one email per user, a parent email only for eligible users with a parent, decline reasons
      Guardrails      UI security headers, rate limiting before authentication

    Runs through the UI's reverse proxy (as a browser does) by default. Written for Windows PowerShell 5.1.
        powershell -NoProfile -ExecutionPolicy Bypass -File deploy\validate.ps1
        powershell -NoProfile -ExecutionPolicy Bypass -File deploy\validate.ps1 -SkipInfrastructure -SkipRateLimit
#>
[CmdletBinding()]
param(
    # Where requests go: the UI (its nginx proxies /api) or the backend directly (http://127.0.0.1:8080).
    [string]$BaseUrl = 'http://127.0.0.1:4200',

    # The backend itself, for the rate-limit check (the UI's nginx has its own, different limit).
    [string]$ApiUrl = 'http://127.0.0.1:8080',

    [string]$AdminUsername = $(if ($env:UPGRADE_SECURITY_ADMIN_USERNAME) { $env:UPGRADE_SECURITY_ADMIN_USERNAME } else { 'admin' }),

    [string]$AdminPassword = $(if ($env:UPGRADE_SECURITY_ADMIN_PASSWORD) { $env:UPGRADE_SECURITY_ADMIN_PASSWORD } else { 'admin' }),

    [string]$Context = $(if ($env:ACCOUNT_UPGRADE_KUBE_CONTEXT) { $env:ACCOUNT_UPGRADE_KUBE_CONTEXT } else { 'rancher-desktop' }),

    [string]$Namespace = 'account-upgrade',

    [string]$Topic = 'upgrade-requests',

    [int]$ProcessingTimeoutSec = 60,

    # Skip the Kubernetes checks, e.g. when validating a stack that runs elsewhere.
    [switch]$SkipInfrastructure,

    # Skip the burst of requests that trips the rate limiter (it briefly throttles this machine's IP).
    [switch]$SkipRateLimit
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
# 127.0.0.1 rather than localhost: on Windows localhost resolves to ::1 first, which WSL's relay can hold.
$script:passed = 0
$script:failed = 0
$prefix = 'validate-' + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()

function Write-Section([string]$Name) { Write-Host ''; Write-Host "== $Name" -ForegroundColor Cyan }

function Test-Check([string]$Name, $Actual, $Expected) {
    if ("$Actual" -eq "$Expected") {
        $script:passed++
        Write-Host ('  PASS  {0,-62} {1}' -f $Name, $Actual) -ForegroundColor Green
    }
    else {
        $script:failed++
        Write-Host ('  FAIL  {0,-62} got {1}, expected {2}' -f $Name, $Actual, $Expected) -ForegroundColor Red
    }
}

# One HTTP call; never throws on 4xx/5xx (Invoke-WebRequest does in PowerShell 5.1). Returns status, headers, JSON.
function Invoke-Api([string]$Method, [string]$Path, $Body = $null, [hashtable]$Headers = @{}, [string]$Url = $BaseUrl) {
    $request = [System.Net.HttpWebRequest]::Create("$Url$Path")
    $request.Method = $Method
    $request.Timeout = 15000
    foreach ($key in $Headers.Keys) { $request.Headers[$key] = $Headers[$key] }
    if ($null -ne $Body) {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($(if ($Body -is [string]) { $Body } else { ConvertTo-Json $Body -Depth 5 -Compress }))
        $request.ContentType = 'application/json'
        $stream = $request.GetRequestStream(); $stream.Write($bytes, 0, $bytes.Length); $stream.Close()
    }
    try { $response = $request.GetResponse() }
    catch [System.Net.WebException] {
        if (-not $_.Exception.Response) { throw }
        $response = $_.Exception.Response
    }
    try {
        $reader = New-Object System.IO.StreamReader($response.GetResponseStream())
        $text = $reader.ReadToEnd()
        $json = $null
        if ($text -and $response.ContentType -match 'json') { $json = ConvertFrom-Json $text }
        return [pscustomobject]@{ Status = [int]$response.StatusCode; Headers = $response.Headers; Json = $json }
    }
    finally { $response.Close() }
}

# ConvertFrom-Json in 5.1 returns a JSON array as one object; unroll it into a real array.
function ConvertTo-Array($Value) { return @($Value | ForEach-Object { $_ }) }

function Get-KubectlOutput {
    $ErrorActionPreference = 'Continue'
    $out = & kubectl --context $Context --namespace $Namespace @args 2>$null
    if ($LASTEXITCODE -ne 0) { return $null }
    return $out
}

# "ready/desired" replicas of a workload, or 'missing'.
function Get-Readiness([string]$Kind, [string]$Name) {
    $out = Get-KubectlOutput get $Kind $Name -o 'jsonpath={.status.readyReplicas}/{.spec.replicas}'
    if (-not $out) { return 'missing' }
    $parts = "$out".Split('/')
    return "$(if ($parts[0]) { $parts[0] } else { 0 })/$($parts[1])"
}

Write-Host "Validating $BaseUrl (test users: $prefix-*)"

if (-not $SkipInfrastructure) {
    Write-Section 'Infrastructure'
    Test-Check 'postgres: ready/desired pods' (Get-Readiness statefulset postgres) '1/1'
    Test-Check 'kafka brokers: ready/desired pods' (Get-Readiness statefulset kafka) '3/3'
    Test-Check 'account-upgrade-backend: ready/desired pods' (Get-Readiness deployment account-upgrade-backend) '2/2'
    Test-Check 'account-upgrade-frontend: ready/desired pods' (Get-Readiness deployment account-upgrade-frontend) '2/2'
    $schema = Get-KubectlOutput get job account-update-db-schema -o 'jsonpath={.status.succeeded}'
    Test-Check 'schema job succeeded' $(if ($null -eq $schema) { 'missing' } elseif ("$schema" -eq '1') { 'yes' } else { 'no' }) 'yes'

    $postgres = @('exec', 'postgres-0', '--', 'psql', '-U', 'postgres', '-d', 'account_upgrade', '-tAc')
    $unapplied = Get-KubectlOutput @postgres "select count(*) from databasechangelog where exectype not in ('EXECUTED', 'MARK_RAN')"
    $applied = Get-KubectlOutput @postgres 'select count(*) from databasechangelog'
    Test-Check 'schema changelog applied (changesets recorded)' $(if ([int]"$applied" -gt 0 -and [int]"$unapplied" -eq 0) { 'yes' } else { "applied=$applied, other=$unapplied" }) 'yes'
    $tables = Get-KubectlOutput @postgres "select count(*) from pg_tables where schemaname = 'public' and tablename in ('processed_upgrades', 'notification_outbox')"
    Test-Check 'application tables exist' "$tables".Trim() 2

    $kafka = @('exec', 'kafka-0', '--')
    $topics = @(Get-KubectlOutput @kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe)
    foreach ($name in $Topic, "$Topic-dlq") {
        $line = $topics | Where-Object { $_ -match "^Topic: $([regex]::Escape($name))\s" } | Select-Object -First 1
        $replication = if ($line -and $line -match 'ReplicationFactor: (\d+)') { $Matches[1] } else { 'missing' }
        Test-Check "topic $name replication factor" $replication 3
    }
    $offsets = @(Get-KubectlOutput @kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 --topic "$Topic-dlq")
    $deadLetters = ($offsets | Where-Object { $_ } | ForEach-Object { [long]($_.Split(':')[2]) } | Measure-Object -Sum).Sum
    Test-Check 'dead-letter queue is empty' $deadLetters 0
    $groups = @(Get-KubectlOutput @kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group upgrade-eligibility)
    $lag = ($groups | Where-Object { $_ -match "^\S+\s+$([regex]::Escape($Topic))\s" } |
        ForEach-Object { $col = ($_ -split '\s+')[5]; if ($col -match '^\d+$') { [long]$col } else { 0 } } | Measure-Object -Sum).Sum
    Test-Check 'consumer lag on the upgrade-requests topic' $lag 0
}

Write-Section 'Authentication'
Test-Check 'API without a token' (Invoke-Api GET '/api/processed-upgrades').Status 401
Test-Check 'login with a wrong password' (Invoke-Api POST '/api/auth/login' @{ username = $AdminUsername; password = "$AdminPassword-wrong" }).Status 401
$login = Invoke-Api POST '/api/auth/login' @{ username = $AdminUsername; password = $AdminPassword }
$token = if ($login.Status -eq 200 -and $login.Json) { $login.Json.accessToken } else { $null }
Test-Check 'login returns a bearer token' $(if ($token -and $login.Json.tokenType -eq 'Bearer') { 'yes' } else { "HTTP $($login.Status)" }) 'yes'
if (-not $token) {
    Write-Host "`nCannot continue without a token." -ForegroundColor Red
    exit 1
}
$auth = @{ Authorization = "Bearer $token" }
Test-Check 'API with the token' (Invoke-Api GET '/api/processed-upgrades?limit=1' -Headers $auth).Status 200
Test-Check 'API with a tampered token' (Invoke-Api GET '/api/processed-upgrades?limit=1' -Headers @{ Authorization = "Bearer $($token.Substring(0, $token.Length - 2))xx" }).Status 401
Test-Check 'health stays public' (Invoke-Api GET '/actuator/health').Status 200

Write-Section 'Ingestion'
$batch = @(
    @{ userId = "$prefix-alice"; userName = 'Alice'; age = 19; balance = 120.5; parentEmail = "$prefix-alice.parent@example.com" },
    @{ userId = "$prefix-age18"; userName = 'Min'; age = 18; balance = 30 },
    @{ userId = "$prefix-age23"; userName = 'Max'; age = 23; balance = 30 },
    @{ userId = "$prefix-age17"; userName = 'Young'; age = 17; balance = 50 },
    @{ userId = "$prefix-age24"; userName = 'Old'; age = 24; balance = 50 },
    @{ userId = "$prefix-poor"; userName = 'Poor'; age = 20; balance = 29.99; parentEmail = "$prefix-poor.parent@example.com" },
    @{ userId = "$prefix-noname"; userName = ''; age = 17; balance = 12.75 })
$batchJson = '[' + (($batch | ForEach-Object { ConvertTo-Json $_ -Compress }) -join ',') + ']'
$batchResult = Invoke-Api POST '/api/batch-upgrade' $batchJson -Headers $auth
Test-Check 'batch of 7 accepted' "$($batchResult.Status)/$(if ($batchResult.Json) { $batchResult.Json.accepted })" '202/7'
Test-Check 'real-time request accepted' (Invoke-Api POST '/api/realtime-upgrade' @{ userId = "$prefix-rt"; userName = 'Dana'; age = 21; balance = 45 } -Headers $auth).Status 202
$idempotent = $auth + @{ 'Idempotency-Key' = "$prefix-key" }
foreach ($attempt in 1..3) {
    $null = Invoke-Api POST '/api/realtime-upgrade' @{ userId = "$prefix-idem"; userName = 'Ivy'; age = 21; balance = 80 } -Headers $idempotent
}

Write-Section 'Validation errors'
Test-Check 'missing userId' (Invoke-Api POST '/api/realtime-upgrade' '{"userName":"x","age":20,"balance":50}' -Headers $auth).Status 400
Test-Check 'invalid parentEmail' (Invoke-Api POST '/api/realtime-upgrade' '{"userId":"z","age":20,"balance":50,"parentEmail":"nope"}' -Headers $auth).Status 400
Test-Check 'empty batch' (Invoke-Api POST '/api/batch-upgrade' '[]' -Headers $auth).Status 400
Test-Check 'malformed JSON' (Invoke-Api POST '/api/realtime-upgrade' '{oops' -Headers $auth).Status 400
Test-Check 'unknown field (strict input)' (Invoke-Api POST '/api/realtime-upgrade' '{"userId":"z","age":20,"balance":50,"isAdmin":true}' -Headers $auth).Status 400

Write-Section 'Processing (Kafka, eligibility, PostgreSQL, outbox)'
$deadline = (Get-Date).AddSeconds($ProcessingTimeoutSec)
do {
    Start-Sleep -Seconds 1
    $rows = ConvertTo-Array (Invoke-Api GET '/api/processed-upgrades?limit=5000' -Headers $auth).Json |
        Where-Object { $_.userId.StartsWith("$prefix-") }
    $done = @($rows | Where-Object { $_.notificationSent }).Count
} while ($done -lt 9 -and (Get-Date) -lt $deadline)
Test-Check 'all 9 distinct requests processed and notified' $done 9
function Get-Status([string]$Suffix) {
    $row = $rows | Where-Object { $_.userId -eq "$prefix-$Suffix" } | Select-Object -First 1
    if ($row) { return $row.status } else { return 'missing' }
}
Test-Check 'age 19, $120.50, parent email' (Get-Status 'alice') 'ELIGIBLE'
Test-Check 'age 18 (lower bound), $30 (minimum)' (Get-Status 'age18') 'ELIGIBLE'
Test-Check 'age 23 (upper bound)' (Get-Status 'age23') 'ELIGIBLE'
Test-Check 'age 17' (Get-Status 'age17') 'INELIGIBLE'
Test-Check 'age 24' (Get-Status 'age24') 'INELIGIBLE'
Test-Check 'balance $29.99' (Get-Status 'poor') 'INELIGIBLE'
Test-Check 'real-time: age 21, $45' (Get-Status 'rt') 'ELIGIBLE'
$noname = $rows | Where-Object { $_.userId -eq "$prefix-noname" } | Select-Object -First 1
Test-Check 'blank name, age 17, $12.75: every reason recorded' $(if ($noname) { @($noname.reasons).Count } else { 'missing' }) 3
Test-Check 'Idempotency-Key sent 3 times: stored once' @($rows | Where-Object { $_.userId -eq "$prefix-idem" }).Count 1
$ineligible = ConvertTo-Array (Invoke-Api GET '/api/processed-upgrades?status=INELIGIBLE&limit=5000' -Headers $auth).Json
Test-Check 'filter by status' @($ineligible | Where-Object { $_.userId.StartsWith("$prefix-") }).Count 4
Test-Check 'filter by userId' @(ConvertTo-Array (Invoke-Api GET "/api/processed-upgrades?userId=$prefix-alice" -Headers $auth).Json).Count 1

Write-Section 'Notifications'
$emails = ConvertTo-Array (Invoke-Api GET '/api/notifications' -Headers $auth).Json
Test-Check 'one email per user' @($emails | Where-Object { $_.role -eq 'USER' -and $_.recipient.StartsWith("$prefix-") }).Count 9
$parents = @($emails | Where-Object { $_.role -eq 'PARENT' -and $_.recipient.StartsWith("$prefix-") } | ForEach-Object { $_.recipient })
Test-Check 'parent email only for the eligible user with a parent' ($parents -join ',') "$prefix-alice.parent@example.com"
$decline = $emails | Where-Object { $_.recipient -eq "$prefix-poor" } | Select-Object -First 1
Test-Check 'decline email lists the reason' $(if ($decline) { $decline.body.Contains('at least $30') } else { 'missing' }) 'True'

Write-Section 'Guardrails'
$page = Invoke-Api GET '/login'
Test-Check 'UI sign-in page is served' $page.Status 200
foreach ($header in 'Content-Security-Policy', 'X-Frame-Options', 'X-Content-Type-Options', 'Referrer-Policy') {
    Test-Check "UI sends $header" $(if ($page.Headers[$header]) { 'yes' } else { 'no' }) 'yes'
}
if ($SkipRateLimit) { Write-Host '  (rate limit check skipped)' }
else {
    # 200 concurrent requests without a token: each backend replica (burst 40, its own bucket) answers some with 429
    # before authentication runs.
    Add-Type -AssemblyName System.Net.Http
    [System.Net.ServicePointManager]::DefaultConnectionLimit = 200
    $client = New-Object System.Net.Http.HttpClient
    try {
        $tasks = @(1..200 | ForEach-Object { $client.GetAsync("$ApiUrl/api/processed-upgrades") })
        [System.Threading.Tasks.Task]::WaitAll([System.Threading.Tasks.Task[]]$tasks)
        $codes = $tasks | ForEach-Object { [int]$_.Result.StatusCode }
        $throttled = @($codes | Where-Object { $_ -eq 429 }).Count
        $other = @($codes | Where-Object { $_ -ne 429 -and $_ -ne 401 }).Count
        Test-Check 'rate limit throttles a burst before authentication' $(if ($throttled -gt 0 -and $other -eq 0) { 'yes' } else { "429=$throttled other=$other" }) 'yes'
    }
    finally { $client.Dispose() }
}

Write-Host ''
$color = if ($script:failed -eq 0) { 'Green' } else { 'Red' }
Write-Host ("RESULT: {0} passed, {1} failed" -f $script:passed, $script:failed) -ForegroundColor $color
if ($script:failed -gt 0) { exit 1 }

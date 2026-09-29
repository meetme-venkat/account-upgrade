# Soak test: sends $Batches batches of $BatchSize requests as fast as possible, waits for processing
# to finish, and appends one row per run to test-results/soak-history.md.
param([int]$Batches = 10, [int]$BatchSize = 10000, [string]$Prefix = 'soak', [int]$Port = 8080, [string]$Note = '')
Add-Type -AssemblyName System.Net.Http
$client = New-Object System.Net.Http.HttpClient
$client.Timeout = [TimeSpan]::FromMinutes(5)
$base = "http://localhost:$Port"
$appPid = (Get-NetTCPConnection -LocalPort $Port -State Listen).OwningProcess

function Metric($name) { (Invoke-RestMethod "$base/actuator/metrics/$name").measurements[0].value }
function Threads() { (Get-Process -Id $appPid).Threads.Count }

$start = Metric 'upgrade.processed'
$deadLettersBefore = Metric 'upgrade.broker.dead-letters'
$threadsBefore = Threads
$accepted = 0; $rejected = 0
$sw = [Diagnostics.Stopwatch]::StartNew()
for ($b = 0; $b -lt $Batches; $b++) {
    $json = '[' + ((1..$BatchSize | ForEach-Object { '{"userId":"' + $Prefix + '-' + $b + '-' + $_ + '","userName":"S","age":20,"balance":50,"parentEmail":"p@example.com"}' }) -join ',') + ']'
    $resp = $client.PostAsync("$base/api/batch-upgrade", (New-Object System.Net.Http.StringContent($json, [Text.Encoding]::UTF8, 'application/json'))).Result
    $body = $resp.Content.ReadAsStringAsync().Result | ConvertFrom-Json
    $accepted += $body.accepted; $rejected += $body.rejected
    "batch $b -> HTTP $([int]$resp.StatusCode) accepted=$($body.accepted) rejected=$($body.rejected) pending=$(Metric 'upgrade.broker.pending') at $($sw.ElapsedMilliseconds) ms"
}
do { Start-Sleep -Milliseconds 200; $n = Metric 'upgrade.processed' } while ($n -lt $start + $accepted -and $sw.Elapsed.TotalSeconds -lt 300)
$processed = $n - $start
$seconds = $sw.Elapsed.TotalSeconds
$rate = [int]($processed / $seconds)
$deadLetters = (Metric 'upgrade.broker.dead-letters') - $deadLettersBefore
$threadsAfter = Threads
$heapMb = [math]::Round((Get-Process -Id $appPid).WorkingSet64 / 1MB)

"accepted=$accepted rejected=$rejected processed=$processed in $([int]($seconds * 1000)) ms  (~$rate events/s end-to-end)"
"dead letters=$deadLetters  emails sent=$(Metric 'upgrade.notifications.sent')  pending=$(Metric 'upgrade.broker.pending')"
"OS threads before/after: $threadsBefore / $threadsAfter"

# ---------------------------------------------------------------- append to markdown history
$outDir = Join-Path $PSScriptRoot '..\test-results'
New-Item -ItemType Directory -Force $outDir | Out-Null
$mdPath = Join-Path $outDir 'soak-history.md'
$utf8 = New-Object System.Text.UTF8Encoding($false)
if (-not (Test-Path $mdPath)) {
    [IO.File]::WriteAllText($mdPath, @'
# Soak test history

Rows are appended by `scripts/soak.ps1`, one per run. Findings and analysis are in [`TEST_REPORT.md`](../TEST_REPORT.md).

- **Rejected:** batch items refused because the queues were full (back-pressure), not lost data.
- **Lost:** accepted minus processed; this must always be 0.
- **Process memory:** OS working set of the Java process, not the live heap. Use `jcmd <pid> GC.class_histogram` for heap detail.

| Run at | Events sent | Accepted | Rejected | Processed | Lost | Dead letters | Seconds | Events/s | Threads before -> after | Process memory (MB) | Note |
|---|---|---|---|---|---|---|---|---|---|---|---|
'@ + [Environment]::NewLine, $utf8)
}
$row = "| $(Get-Date -Format 'yyyy-MM-dd HH:mm') | $($Batches * $BatchSize) | $accepted | $rejected | $processed | $($accepted - $processed) | $deadLetters | $([math]::Round($seconds, 1)) | $rate | $threadsBefore -> $threadsAfter | $heapMb | $($Note -replace '\|', '\|') |"
[IO.File]::AppendAllText($mdPath, $row + [Environment]::NewLine, $utf8)
"Row appended to $((Resolve-Path $mdPath).Path)"

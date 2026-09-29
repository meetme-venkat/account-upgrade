# Live corner-case suite. Run against a freshly started app (it asserts exact record counts).
# Writes the results to test-results/corner-cases.md.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
$base = 'http://localhost:8080'
$client = New-Object System.Net.Http.HttpClient
$results = New-Object System.Collections.ArrayList

$alreadyProcessed = (Invoke-RestMethod "$base/actuator/metrics/upgrade.processed").measurements[0].value
if ($alreadyProcessed -gt 0) {
    throw "The app has already processed $alreadyProcessed requests. Restart it first: this suite checks exact counts."
}

function Call($method, $path, $body, $contentType = 'application/json') {
    $req = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::new($method), "$base$path")
    if ($null -ne $body) { $req.Content = New-Object System.Net.Http.StringContent($body, [Text.Encoding]::UTF8, $contentType) }
    $resp = $client.SendAsync($req).Result
    [pscustomobject]@{ Code = [int]$resp.StatusCode; Body = $resp.Content.ReadAsStringAsync().Result }
}

function Check($group, $name, $pass, $detail) {
    [void]$results.Add([pscustomobject]@{ Group = $group; Case = $name; Result = $(if ($pass) { 'PASS' } else { 'FAIL' }); Detail = $detail })
}

function Expect($group, $name, $method, $path, $body, $code, $contains = $null, $contentType = 'application/json') {
    $r = Call $method $path $body $contentType
    $ok = ($r.Code -eq $code) -and (($null -eq $contains) -or $r.Body.Contains($contains))
    $snippet = $r.Body; if ($snippet.Length -gt 150) { $snippet = $snippet.Substring(0, 150) + '...' }
    Check $group $name $ok "HTTP $($r.Code) $snippet"
    $r
}

# ---------------------------------------------------------------- 1. Ingestion validation
$g = '1. Validation'
Expect $g 'Missing userId' POST /api/realtime-upgrade '{"userName":"A","age":20,"balance":50}' 400 'userId is required' | Out-Null
Expect $g 'Blank userId "   "' POST /api/realtime-upgrade '{"userId":"   ","userName":"A","age":20,"balance":50}' 400 'userId is required' | Out-Null
Expect $g 'Invalid parentEmail' POST /api/realtime-upgrade '{"userId":"v1","userName":"A","age":20,"balance":50,"parentEmail":"not-an-email"}' 400 'valid email' | Out-Null
Expect $g 'Malformed JSON' POST /api/realtime-upgrade '{"userId":' 400 'Malformed request' | Out-Null
Expect $g 'Empty body' POST /api/realtime-upgrade '' 400 'Malformed request' | Out-Null
Expect $g 'Age not a number' POST /api/realtime-upgrade '{"userId":"v2","userName":"A","age":"abc","balance":50}' 400 'Malformed request' | Out-Null
Expect $g 'Balance not a number' POST /api/realtime-upgrade '{"userId":"v3","userName":"A","age":20,"balance":"lots"}' 400 'Malformed request' | Out-Null
Expect $g 'Array sent to realtime' POST /api/realtime-upgrade '[{"userId":"v4"}]' 400 | Out-Null
Expect $g 'Object sent to batch' POST /api/batch-upgrade '{"userId":"v5"}' 400 | Out-Null
Expect $g 'Empty batch []' POST /api/batch-upgrade '[]' 400 'at least one request' | Out-Null
Expect $g 'Batch with invalid 2nd item' POST /api/batch-upgrade '[{"userId":"v6","userName":"A","age":20,"balance":50},{"userName":"no id"}]' 400 '[1].userId' | Out-Null
$big = '[' + ((1..10001 | ForEach-Object { '{"userId":"big' + $_ + '","userName":"B","age":20,"balance":50}' }) -join ',') + ']'
Expect $g 'Batch over 10,000 items' POST /api/batch-upgrade $big 400 'must not exceed 10000' | Out-Null
Expect $g 'Wrong content type (text/plain)' POST /api/realtime-upgrade 'hello' 415 $null 'text/plain' | Out-Null
Expect $g 'Wrong method (GET on POST endpoint)' GET /api/realtime-upgrade $null 405 | Out-Null
Expect $g 'Unknown path' GET /api/does-not-exist $null 404 | Out-Null
Expect $g 'Invalid status filter' GET '/api/processed-upgrades?status=MAYBE' $null 400 'Invalid value' | Out-Null
Expect $g 'Lower-case status filter' GET '/api/processed-upgrades?status=eligible' $null 400 | Out-Null
Expect $g 'Unknown JSON fields ignored' POST /api/realtime-upgrade '{"userId":"c-unknown","userName":"A","age":20,"balance":50,"favouriteColour":"blue"}' 202 'ACCEPTED' | Out-Null

# ---------------------------------------------------------------- 2. Eligibility corner cases (async; verified below)
# userId, json, expected status, expected reason count, expected reason fragment
$cases = @(
    @('c-age17',     '{"userId":"c-age17","userName":"A","age":17,"balance":50}',           'INELIGIBLE', 1, 'but was 17'),
    @('c-age18',     '{"userId":"c-age18","userName":"A","age":18,"balance":50}',           'ELIGIBLE',   0, $null),
    @('c-age23',     '{"userId":"c-age23","userName":"A","age":23,"balance":50}',           'ELIGIBLE',   0, $null),
    @('c-age24',     '{"userId":"c-age24","userName":"A","age":24,"balance":50}',           'INELIGIBLE', 1, 'but was 24'),
    @('c-age0',      '{"userId":"c-age0","userName":"A","age":0,"balance":50}',             'INELIGIBLE', 1, 'but was 0'),
    @('c-ageneg',    '{"userId":"c-ageneg","userName":"A","age":-5,"balance":50}',          'INELIGIBLE', 1, 'but was -5'),
    @('c-age200',    '{"userId":"c-age200","userName":"A","age":200,"balance":50}',         'INELIGIBLE', 1, 'but was 200'),
    @('c-agenull',   '{"userId":"c-agenull","userName":"A","balance":50}',                  'INELIGIBLE', 1, 'Age is required'),
    @('c-agefloat',  '{"userId":"c-agefloat","userName":"A","age":20.9,"balance":50}',      'ELIGIBLE',   0, $null),
    @('c-bal2999',   '{"userId":"c-bal2999","userName":"A","age":20,"balance":29.99}',      'INELIGIBLE', 1, 'was $29.99'),
    @('c-bal29999',  '{"userId":"c-bal29999","userName":"A","age":20,"balance":29.999}',    'INELIGIBLE', 1, 'was $29.999'),
    @('c-bal30',     '{"userId":"c-bal30","userName":"A","age":20,"balance":30}',           'ELIGIBLE',   0, $null),
    @('c-bal3000',   '{"userId":"c-bal3000","userName":"A","age":20,"balance":30.00}',      'ELIGIBLE',   0, $null),
    @('c-bal0',      '{"userId":"c-bal0","userName":"A","age":20,"balance":0}',             'INELIGIBLE', 1, 'was $0'),
    @('c-balneg',    '{"userId":"c-balneg","userName":"A","age":20,"balance":-100}',        'INELIGIBLE', 1, 'was $-100'),
    @('c-balhuge',   '{"userId":"c-balhuge","userName":"A","age":20,"balance":999999999999.99}', 'ELIGIBLE', 0, $null),
    @('c-balstr',    '{"userId":"c-balstr","userName":"A","age":20,"balance":"45.5"}',      'ELIGIBLE',   0, $null),
    @('c-balnull',   '{"userId":"c-balnull","userName":"A","age":20}',                      'INELIGIBLE', 1, 'Balance is required'),
    @('c-nameempty', '{"userId":"c-nameempty","userName":"","age":20,"balance":50}',        'INELIGIBLE', 1, 'User name must not be empty'),
    @('c-nameblank', '{"userId":"c-nameblank","userName":"   ","age":20,"balance":50}',     'INELIGIBLE', 1, 'User name must not be empty'),
    @('c-namenull',  '{"userId":"c-namenull","age":20,"balance":50}',                       'INELIGIBLE', 1, 'User name must not be empty'),
    @('c-allfail',   '{"userId":"c-allfail","userName":"","age":99,"balance":1}',           'INELIGIBLE', 3, $null),
    @('c-onlyid',    '{"userId":"c-onlyid"}',                                               'INELIGIBLE', 3, 'required'),
    @('c-unicode',   '{"userId":"c-unicode","userName":"Zoë Łukasz 名","age":21,"balance":31}', 'ELIGIBLE', 0, $null)
)
foreach ($c in $cases) {
    $r = Call POST /api/realtime-upgrade $c[1]
    if ($r.Code -ne 202) { Check '2. Eligibility' "$($c[0]) ingestion" $false "HTTP $($r.Code) $($r.Body)" }
}

# ---------------------------------------------------------------- 3. Notification corner cases
$notifCases = @(
    # userId, json, expected parent notified?
    @('n-elig-parent',   '{"userId":"n-elig-parent","userName":"P","age":20,"balance":50,"parentEmail":"p1@example.com"}', $true),
    @('n-elig-noparent', '{"userId":"n-elig-noparent","userName":"P","age":20,"balance":50}', $false),
    @('n-elig-nullpar',  '{"userId":"n-elig-nullpar","userName":"P","age":20,"balance":50,"parentEmail":null}', $false),
    @('n-elig-emptypar', '{"userId":"n-elig-emptypar","userName":"P","age":20,"balance":50,"parentEmail":""}', $false),
    @('n-inel-parent',   '{"userId":"n-inel-parent","userName":"P","age":40,"balance":50,"parentEmail":"p2@example.com"}', $false)
)
foreach ($c in $notifCases) { Call POST /api/realtime-upgrade $c[1] | Out-Null }

# ---------------------------------------------------------------- 4. Duplicates, ordering, batch mix
$r = Call POST /api/realtime-upgrade '{"userId":"d-dup","userName":"D","age":20,"balance":50}'
$r2 = Call POST /api/realtime-upgrade '{"userId":"d-dup","userName":"D","age":20,"balance":50}'
$e1 = ($r.Body | ConvertFrom-Json).eventId; $e2 = ($r2.Body | ConvertFrom-Json).eventId
Check '4. Duplicates/batch' 'Same user submitted twice gets distinct eventIds' ($e1 -ne $e2) "$e1 / $e2"

$seq = '[' + ((1..20 | ForEach-Object { '{"userId":"o-seq","userName":"S","age":' + $(if ($_ % 2) { 20 } else { 30 }) + ',"balance":50}' }) -join ',') + ']'
$rs = Call POST /api/batch-upgrade $seq
Check '4. Duplicates/batch' '20 events for same user in one batch accepted' ($rs.Code -eq 202 -and ($rs.Body | ConvertFrom-Json).accepted -eq 20) "HTTP $($rs.Code)"

$mix = '[{"userId":"b-ok","userName":"B","age":19,"balance":100,"parentEmail":"bp@example.com"},{"userId":"b-bad","userName":"","age":16,"balance":5}]'
$rm = Call POST /api/batch-upgrade $mix
Check '4. Duplicates/batch' 'Mixed batch returns receipts in input order' ($rm.Code -eq 202 -and (($rm.Body | ConvertFrom-Json).receipts | ForEach-Object userId) -join ',' -eq 'b-ok,b-bad') "HTTP $($rm.Code)"

# snapshot the (bounded) outbox before the load test rotates these emails out
Start-Sleep -Milliseconds 1500
$notifs = (Call GET /api/notifications $null).Body | ConvertFrom-Json
$preLoad = (Call GET /api/processed-upgrades $null).Body | ConvertFrom-Json

# ---------------------------------------------------------------- 5. Throughput / concurrency
$load = '[' + ((1..5000 | ForEach-Object { '{"userId":"load' + $_ + '","userName":"L","age":' + (16 + ($_ % 10)) + ',"balance":' + (25 + ($_ % 10)) + '}' }) -join ',') + ']'
$sw = [Diagnostics.Stopwatch]::StartNew()
$rl = Call POST /api/batch-upgrade $load
$ingestMs = $sw.ElapsedMilliseconds
Check '5. Load' 'Batch of 5,000 accepted' ($rl.Code -eq 202 -and ($rl.Body | ConvertFrom-Json).accepted -eq 5000) "HTTP $($rl.Code) in $ingestMs ms"

$tasks = 1..300 | ForEach-Object {
    $content = New-Object System.Net.Http.StringContent(('{"userId":"par' + $_ + '","userName":"P","age":20,"balance":50}'), [Text.Encoding]::UTF8, 'application/json')
    $client.PostAsync("$base/api/realtime-upgrade", $content)
}
[System.Threading.Tasks.Task]::WaitAll([System.Threading.Tasks.Task[]]$tasks)
$okCount = ($tasks | Where-Object { [int]$_.Result.StatusCode -eq 202 }).Count
Check '5. Load' '300 concurrent real-time requests accepted' ($okCount -eq 300) "$okCount/300 returned 202"

# ---------------------------------------------------------------- wait for async processing to drain
$expectedTotal = 1 + $cases.Count + $notifCases.Count + 2 + 20 + 2 + 5000 + 300
$deadline = (Get-Date).AddSeconds(30)
do {
    Start-Sleep -Milliseconds 300
    $all = (Call GET /api/processed-upgrades $null).Body | ConvertFrom-Json
} while ($all.Count -lt $expectedTotal -and (Get-Date) -lt $deadline)
$drainMs = $sw.ElapsedMilliseconds
Check '5. Load' "All $expectedTotal events processed" ($all.Count -eq $expectedTotal) "$($all.Count) processed; load drained ~$drainMs ms after batch start"

$byUser = @{}; foreach ($p in $all) { if (-not $byUser.ContainsKey($p.userId)) { $byUser[$p.userId] = @() }; $byUser[$p.userId] += $p }
$outboxNow = (Call GET /api/notifications $null).Body | ConvertFrom-Json
Check '5. Load' 'Email outbox stays bounded after load' ($outboxNow.Count -le 1000) "$($outboxNow.Count) retained"

# ---------------------------------------------------------------- verify eligibility
foreach ($c in $cases) {
    $rec = @($byUser[$c[0]])[0]
    $ok = $rec -and $rec.status -eq $c[2] -and @($rec.reasons).Count -eq $c[3] -and $rec.notificationSent -eq $true -and $rec.processedAt
    if ($c[4]) { $ok = $ok -and ((@($rec.reasons) -join ' | ').Contains($c[4])) }
    $reasonText = if (@($rec.reasons).Count) { @($rec.reasons) -join ' | ' } else { '-' }
    Check '2. Eligibility' $c[0] $ok "$($rec.status); $reasonText"
}

# ---------------------------------------------------------------- verify notifications
foreach ($c in $notifCases) {
    $rec = @($byUser[$c[0]])[0]
    $mine = @($notifs | Where-Object { $_.eventId -eq $rec.eventId })
    $userMsg = @($mine | Where-Object role -eq 'USER').Count
    $parentMsg = @($mine | Where-Object role -eq 'PARENT').Count
    $ok = $userMsg -eq 1 -and ($parentMsg -eq $(if ($c[2]) { 1 } else { 0 }))
    Check '3. Notifications' $c[0] $ok "$($rec.status); user emails=$userMsg, parent emails=$parentMsg"
}
$declined = @($notifs | Where-Object { $_.eventId -eq @($byUser['c-allfail'])[0].eventId })[0]
Check '3. Notifications' 'Decline email lists every reason' ($declined.body -match 'User name.*Age.*Balance') $declined.body

# ---------------------------------------------------------------- verify duplicates / ordering / filters
Check '4. Duplicates/batch' 'Both duplicate submissions processed and stored' (@($byUser['d-dup']).Count -eq 2) "$(@($byUser['d-dup']).Count) records"
$seqStatuses = (@($byUser['o-seq']) | ForEach-Object status) -join ','
$expectedSeq = (1..20 | ForEach-Object { if ($_ % 2) { 'ELIGIBLE' } else { 'INELIGIBLE' } }) -join ','
Check '4. Duplicates/batch' 'Same-user events processed in submission order' ($seqStatuses -eq $expectedSeq) 'alternating ELIGIBLE/INELIGIBLE preserved'
Check '4. Duplicates/batch' 'Mixed batch outcomes' ((@($byUser['b-ok'])[0].status -eq 'ELIGIBLE') -and (@($byUser['b-bad'])[0].status -eq 'INELIGIBLE')) 'b-ok ELIGIBLE, b-bad INELIGIBLE'
Check '4. Duplicates/batch' 'No request was stored from rejected (400) calls' (-not $byUser.ContainsKey('v1') -and -not $byUser.ContainsKey('v6') -and -not $byUser.ContainsKey('big1')) 'v1, v6, big1 absent'

$eligible = (Call GET '/api/processed-upgrades?status=ELIGIBLE' $null).Body | ConvertFrom-Json
$inelig = (Call GET '/api/processed-upgrades?status=INELIGIBLE' $null).Body | ConvertFrom-Json
Check '6. Query' 'status filters partition all records' (($eligible.Count + $inelig.Count) -eq $all.Count -and -not ($eligible | Where-Object status -ne 'ELIGIBLE')) "ELIGIBLE=$($eligible.Count), INELIGIBLE=$($inelig.Count)"
$combo = (Call GET '/api/processed-upgrades?status=INELIGIBLE&userId=c-age17' $null).Body | ConvertFrom-Json
Check '6. Query' 'status + userId combined filter' (@($combo).Count -eq 1) "$(@($combo).Count) record"
$none = Call GET '/api/processed-upgrades?userId=nobody' $null
Check '6. Query' 'Unknown userId returns empty list' ($none.Code -eq 200 -and $none.Body -eq '[]') "HTTP $($none.Code) $($none.Body)"
$sorted = $true
foreach ($u in $byUser.Keys) { $l = @($byUser[$u]); for ($i = 1; $i -lt $l.Count; $i++) { if ([datetime]$l[$i].processedAt -lt [datetime]$l[$i-1].processedAt) { $sorted = $false } } }
Check '6. Query' 'Per-user results ordered by processedAt' $sorted ''
$loadStats = @($all | Where-Object { $_.userId -like 'load*' } | Group-Object status | ForEach-Object { "$($_.Name)=$($_.Count)" }) -join ', '
Check '5. Load' 'Load batch outcomes' $true $loadStats

$health = Call GET /actuator/health $null
Check '6. Query' 'Health endpoint' ($health.Code -eq 200 -and $health.Body.Contains('UP')) $health.Body

$passCount = @($results | Where-Object Result -eq 'PASS').Count
$failCount = @($results | Where-Object Result -eq 'FAIL').Count

# ---------------------------------------------------------------- markdown results (overwritten on every run)
$outDir = Join-Path $PSScriptRoot '..\test-results'
New-Item -ItemType Directory -Force $outDir | Out-Null
$md = New-Object System.Text.StringBuilder
[void]$md.AppendLine('# Corner-case results (latest run)')
[void]$md.AppendLine('')
[void]$md.AppendLine('This file is generated by `scripts/corner-cases.ps1` and is overwritten on every run. Findings and analysis are in [`TEST_REPORT.md`](../TEST_REPORT.md).')
[void]$md.AppendLine('')
[void]$md.AppendLine("- **Run at:** $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')")
[void]$md.AppendLine("- **Target:** $base")
[void]$md.AppendLine("- **Result:** $(if ($failCount -eq 0) { 'ALL PASS' } else { 'FAILURES' }): $passCount passed, $failCount failed, $($results.Count) total")
foreach ($group in ($results | Group-Object Group | Sort-Object Name)) {
    [void]$md.AppendLine('')
    [void]$md.AppendLine("## $($group.Name)")
    [void]$md.AppendLine('')
    [void]$md.AppendLine('| Case | Result | Observed |')
    [void]$md.AppendLine('|---|---|---|')
    foreach ($row in $group.Group) {
        $detail = ($row.Detail -replace '\s+', ' ') -replace '\|', '\|'
        if ($detail.Length -gt 180) { $detail = $detail.Substring(0, 180) + '...' }
        $mark = if ($row.Result -eq 'PASS') { 'PASS' } else { '**FAIL**' }
        [void]$md.AppendLine("| $($row.Case -replace '\|', '\|') | $mark | $detail |")
    }
}
$mdPath = Join-Path $outDir 'corner-cases.md'
[IO.File]::WriteAllText($mdPath, $md.ToString(), (New-Object System.Text.UTF8Encoding($false)))

$results | Format-Table -AutoSize -Wrap Group, Case, Result, Detail | Out-String -Width 220
"TOTAL: $($results.Count)  PASS: $passCount  FAIL: $failCount"
"Results written to $((Resolve-Path $mdPath).Path)"

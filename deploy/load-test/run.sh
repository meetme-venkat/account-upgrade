#!/bin/sh
# Runs the peak load test and records the evidence: the k6 summary (throughput, latency, status codes), then a timeline
# sampled every few seconds until every request is processed and notified (processed, backlog, unsent emails,
# PostgreSQL connections, CPU and memory per pod), and finally lost requests and dead letters.
#
#   deploy/load-test/run.sh [label]          e.g. deploy/load-test/run.sh before
#
# Output goes to the terminal and to deploy/load-test/results/<time>-<label>/. Needs kubectl access to the cluster
# (KUBE_CONTEXT, default rancher-desktop). The rate limiter caps a single client at 20 requests/s per backend pod, so
# a capacity test disables it first (see README.md).
set -eu
# Git Bash on Windows would rewrite the absolute paths passed to kubectl exec (e.g. /opt/kafka/...).
export MSYS_NO_PATHCONV=1

LABEL=${1:-run}
REQUESTS=${REQUESTS:-10000}
CONTEXT=${KUBE_CONTEXT:-rancher-desktop}
NAMESPACE=account-upgrade
TIMEOUT_SECONDS=${TIMEOUT_SECONDS:-900}
HERE=$(cd "$(dirname "$0")" && pwd)
RUN_ID="peak-$(date +%s)"
OUT="$HERE/results/$(date +%Y%m%d-%H%M%S)-$LABEL"
mkdir -p "$OUT"

k() { kubectl --context "$CONTEXT" --namespace "$NAMESPACE" "$@"; }
sql() { k exec postgres-0 -- psql -U postgres -d account_upgrade -tAc "$1"; }
dead_letters() {
    k exec kafka-0 -- /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 \
        --topic upgrade-requests-dlq | awk -F: '{ sum += $3 } END { print sum + 0 }'
}

echo "Run $RUN_ID ($LABEL, $REQUESTS requests), results in $OUT"
{
    echo "## Configuration"
    k get deployment account-upgrade-backend -o \
        jsonpath='{range .spec.template.spec.containers[0].env[*]}{.name}={.value}{"\n"}{end}' | grep -E "KAFKA|PARTITIONS|RATE_LIMIT|HIKARI" || true
    echo "backend replicas: $(k get deployment account-upgrade-backend -o jsonpath='{.spec.replicas}')"
    echo "kafka memory limit: $(k get statefulset kafka -o jsonpath='{.spec.template.spec.containers[0].resources.limits.memory}')"
    echo "postgres memory limit: $(k get statefulset postgres -o jsonpath='{.spec.template.spec.containers[0].resources.limits.memory}')"
    k exec kafka-0 -- /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe \
        --topic upgrade-requests | head -1
} | tee "$OUT/configuration.txt"

DLQ_BEFORE=$(dead_letters)
k delete job peak-load --ignore-not-found --wait=true >/dev/null
(cd "$HERE" && kubectl --context "$CONTEXT" kustomize .) | sed "s/__RUN_ID__/\"$RUN_ID\"/; s/__REQUESTS__/\"$REQUESTS\"/" | k apply -f - >/dev/null
START=$(date +%s)

MINE="user_id like '$RUN_ID-%'"
echo "seconds,processed,unsent_emails,pg_connections_active,pg_connections_total,top_pods" | tee "$OUT/timeline.csv"
while :; do
    NOW=$(date +%s)
    ROW=$(sql "select (select count(*) from processed_upgrades where $MINE),
                      (select count(*) from notification_outbox o join processed_upgrades p on p.event_id = o.event_id
                        where p.$MINE and o.sent_at is null),
                      (select count(*) from pg_stat_activity where datname = 'account_upgrade' and state = 'active'),
                      (select count(*) from pg_stat_activity where datname = 'account_upgrade')" | tr '|' ',')
    TOP=$(k top pods --no-headers 2>/dev/null | awk '$1 !~ /peak-load|schema/ { printf "%s=%s/%s ", $1, $2, $3 }' |
        sed -E 's/account-upgrade-backend-[a-z0-9]+-/backend-/g; s/account-upgrade-frontend-[a-z0-9]+-/frontend-/g')
    echo "$((NOW - START)),$ROW,$TOP" | tee -a "$OUT/timeline.csv"
    PROCESSED=$(echo "$ROW" | cut -d, -f1)
    UNSENT=$(echo "$ROW" | cut -d, -f2)
    JOB_DONE=$(k get job peak-load -o jsonpath='{.status.succeeded}{.status.failed}')
    if [ -n "$JOB_DONE" ] && [ "$PROCESSED" -ge "$REQUESTS" ] && [ "$UNSENT" -eq 0 ]; then break; fi
    if [ $((NOW - START)) -ge "$TIMEOUT_SECONDS" ]; then echo "Timed out after $TIMEOUT_SECONDS s"; break; fi
    sleep 3
done
END=$(date +%s)

k logs job/peak-load | grep '^K6_SUMMARY' | sed 's/^K6_SUMMARY //' > "$OUT/k6-summary.json"
# Exact times from the k6 container and from the records themselves (the timeline also counts the pod's start-up).
K6_START=$(k get pods -l job-name=peak-load -o jsonpath='{.items[0].status.containerStatuses[0].state.terminated.startedAt}')
K6_END=$(k get pods -l job-name=peak-load -o jsonpath='{.items[0].status.containerStatuses[0].state.terminated.finishedAt}')
{
    echo "## Result"
    echo "k6: $(cat "$OUT/k6-summary.json")"
    echo "k6 container: $K6_START -> $K6_END"
    echo "seconds from the first request to: $(sql "select 'last accepted ' || round(extract(epoch from timestamptz '$K6_END' - timestamptz '$K6_START'))
        || ', last processed ' || round(extract(epoch from max(p.processed_at) - timestamptz '$K6_START'), 1)
        || ', last email sent ' || round(extract(epoch from (select max(o.sent_at) from notification_outbox o
               join processed_upgrades q on q.event_id = o.event_id where q.$MINE) - timestamptz '$K6_START'), 1)
        from processed_upgrades p where p.$MINE")"
    echo "timeline until processed and notified: $((END - START)) s (includes the k6 pod start-up)"
    echo "processed: $(sql "select count(*) from processed_upgrades where $MINE")"
    echo "eligible / ineligible: $(sql "select string_agg(status || '=' || n, ', ') from (select status, count(*) n from processed_upgrades where $MINE group by status) s")"
    echo "dead letters added: $(( $(dead_letters) - DLQ_BEFORE ))"
} | tee "$OUT/result.txt"

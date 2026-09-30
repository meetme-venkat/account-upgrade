#!/bin/sh
# Email relay benchmark: inserts EMAILS pending emails straight into notification_outbox, as a burst of decisions would,
# and measures how fast the relays (one per backend pod) deliver them. The relay alone: no HTTP, Kafka or eligibility.
#
#   deploy/load-test/relay-bench.sh [label]          e.g. EMAILS=150000 deploy/load-test/relay-bench.sh before
#
# The time comes from the rows themselves: from their insert (created_at) to the last delivery (sent_at). The rows are
# deleted afterwards. Run it while nothing else produces emails. Output: deploy/load-test/results/<time>-relay-<label>/.
set -eu
# Git Bash on Windows would rewrite the absolute paths passed to kubectl exec.
export MSYS_NO_PATHCONV=1

LABEL=${1:-run}
EMAILS=${EMAILS:-150000}
CONTEXT=${KUBE_CONTEXT:-rancher-desktop}
TIMEOUT_SECONDS=${TIMEOUT_SECONDS:-600}
HERE=$(cd "$(dirname "$0")" && pwd)
RUN_ID="relay-bench-$(date +%s)"
OUT="$HERE/results/$(date +%Y%m%d-%H%M%S)-relay-$LABEL"
mkdir -p "$OUT"

k() { kubectl --context "$CONTEXT" --namespace account-upgrade "$@"; }
sql() { k exec postgres-0 -- psql -U postgres -d account_upgrade -tAc "$1"; }

echo "Relay benchmark $RUN_ID ($LABEL, $EMAILS emails), results in $OUT"
{
    echo "## Configuration"
    echo "backend image: $(k get deployment account-upgrade-backend -o jsonpath='{.spec.template.spec.containers[0].image}')"
    echo "backend replicas: $(k get deployment account-upgrade-backend -o jsonpath='{.spec.replicas}')"
    k get deployment account-upgrade-backend -o \
        jsonpath='{range .spec.template.spec.containers[0].env[*]}{.name}={.value}{"\n"}{end}' | grep RELAY || true
    echo "pending before: $(sql "select count(*) from notification_outbox where sent_at is null")"
} | tee "$OUT/configuration.txt"

START=$(date +%s)
sql "INSERT INTO notification_outbox (event_id, role, recipient, subject, body, created_at, next_attempt_at)
     SELECT '$RUN_ID-' || g, 'USER', 'user-' || g, 'Your account upgrade is approved',
            'Hi user-' || g || ', good news! Your account is eligible and has been upgraded.', now(), now()
     FROM generate_series(1, $EMAILS) g" >/dev/null

# The pending count uses the pending-rows index; nothing else should be adding emails meanwhile.
echo "seconds,pending,top_pods" | tee "$OUT/timeline.csv"
while :; do
    NOW=$(date +%s)
    PENDING=$(sql "select count(*) from notification_outbox where sent_at is null")
    TOP=$(k top pods --no-headers 2>/dev/null | awk '$1 ~ /backend|postgres/ { printf "%s=%s/%s ", $1, $2, $3 }' |
        sed -E 's/account-upgrade-backend-[a-z0-9]+-/backend-/g')
    echo "$((NOW - START)),$PENDING,$TOP" | tee -a "$OUT/timeline.csv"
    if [ "$PENDING" -eq 0 ]; then break; fi
    if [ $((NOW - START)) -ge "$TIMEOUT_SECONDS" ]; then echo "Timed out after $TIMEOUT_SECONDS s"; break; fi
    sleep 2
done

{
    echo "## Result"
    sql "select 'delivered ' || count(sent_at) || ' of ' || count(*)
               || ' in ' || round(extract(epoch from max(sent_at) - min(created_at))::numeric, 1) || ' s: '
               || round(count(sent_at) / extract(epoch from max(sent_at) - min(created_at))::numeric) || ' emails/s'
               || ', attempts > 1: ' || count(*) filter (where attempts > 1)
         from notification_outbox where event_id like '$RUN_ID-%'"
} | tee "$OUT/result.txt"

sql "DELETE FROM notification_outbox WHERE event_id LIKE '$RUN_ID-%'" >/dev/null
echo "Benchmark rows deleted."

#!/bin/sh
# Installs or updates the monitoring stack (Prometheus, Loki, Alloy, Grafana, Splunk, Kafka and PostgreSQL
# exporters). Idempotent.
#
#   deploy/k8s/monitoring/install.sh            bash, Git Bash or Linux, with kubectl access to the cluster
#
# Needs the application deployed first (the exporters use its namespace and database secret). The Grafana admin
# password (secret grafana-admin) and the Splunk admin password and HEC token (secret splunk) are created once,
# randomly; this script never prints them, and keeps them on later runs.
set -eu
# Git Bash on Windows would rewrite absolute paths passed to kubectl.
export MSYS_NO_PATHCONV=1

CONTEXT=${KUBE_CONTEXT:-rancher-desktop}
k() { kubectl --context "$CONTEXT" "$@"; }
random() { head -c 32 /dev/urandom | base64 | tr -d '/+=' | head -c "$1"; }
# Relative paths from here on: with MSYS_NO_PATHCONV, a Windows kubectl could not open /d/... paths.
cd "$(dirname "$0")"

k get namespace account-upgrade >/dev/null 2>&1 || {
    echo "Namespace account-upgrade not found: deploy the application first (Jenkins pipeline)." >&2
    exit 1
}

k apply -f namespace.yaml
if ! k -n monitoring get secret grafana-admin >/dev/null 2>&1; then
    echo "Creating secret grafana-admin (random admin password)"
    k -n monitoring create secret generic grafana-admin --from-literal=admin-password="$(random 24)"
fi
if ! k -n monitoring get secret splunk >/dev/null 2>&1; then
    echo "Creating secret splunk (random admin password and HEC token)"
    # A HEC token is a UUID.
    token=$(od -An -tx1 -N16 /dev/urandom | tr -d ' \n' | sed -E 's/(.{8})(.{4})(.{4})(.{4})(.{12})/\1-\2-\3-\4-\5/')
    k -n monitoring create secret generic splunk \
        --from-literal=admin-password="$(random 24)" --from-literal=hec-token="$token"
    unset token
fi

k kustomize . | k apply -f -
# Picks up configuration changes (a ConfigMap change alone does not restart a pod). Grafana too: it reads its
# data sources only at startup. Splunk is left running: its settings come from the secret, read at startup.
k -n monitoring rollout restart deployment/prometheus deployment/loki deployment/alloy deployment/grafana
for target in "account-upgrade deployment/kafka-exporter" "account-upgrade deployment/postgres-exporter" \
              "monitoring deployment/prometheus" "monitoring deployment/loki" "monitoring deployment/alloy" \
              "monitoring deployment/grafana"; do
    set -- $target
    k -n "$1" rollout status "$2" --timeout=180s
done
# Splunk's first start sets itself up (a few minutes).
k -n monitoring rollout status deployment/splunk --timeout=600s

cat <<'EOF'

Grafana:  http://127.0.0.1:3000   (user admin)
Password: kubectl -n monitoring get secret grafana-admin -o jsonpath='{.data.admin-password}' | base64 -d
Splunk:   http://127.0.0.1:8000   (user admin)
Password: kubectl -n monitoring get secret splunk -o jsonpath='{.data.admin-password}' | base64 -d
Prometheus (targets, queries): kubectl -n monitoring port-forward svc/prometheus 9090  ->  http://127.0.0.1:9090
EOF

#!/bin/sh
# Installs or updates the monitoring stack (Prometheus, Grafana, Kafka and PostgreSQL exporters). Idempotent.
#
#   deploy/k8s/monitoring/install.sh            bash, Git Bash or Linux, with kubectl access to the cluster
#
# Needs the application deployed first (the exporters use its namespace and database secret). The Grafana admin
# password is created once, randomly, in the secret grafana-admin; this script never prints it.
set -eu
# Git Bash on Windows would rewrite absolute paths passed to kubectl.
export MSYS_NO_PATHCONV=1

CONTEXT=${KUBE_CONTEXT:-rancher-desktop}
HERE=$(cd "$(dirname "$0")" && pwd)
k() { kubectl --context "$CONTEXT" "$@"; }

k get namespace account-upgrade >/dev/null 2>&1 || {
    echo "Namespace account-upgrade not found: deploy the application first (Jenkins pipeline)." >&2
    exit 1
}

k apply -f "$HERE/namespace.yaml"
if ! k -n monitoring get secret grafana-admin >/dev/null 2>&1; then
    echo "Creating secret grafana-admin (random admin password)"
    k -n monitoring create secret generic grafana-admin \
        --from-literal=admin-password="$(head -c 24 /dev/urandom | base64 | tr -d '/+=' | head -c 24)"
fi

(cd "$HERE" && k kustomize .) | k apply -f -
# Picks up changes to the scrape configuration (a ConfigMap change alone does not restart the pod).
k -n monitoring rollout restart deployment/prometheus
for target in "account-upgrade deployment/kafka-exporter" "account-upgrade deployment/postgres-exporter" \
              "monitoring deployment/prometheus" "monitoring deployment/grafana"; do
    set -- $target
    k -n "$1" rollout status "$2" --timeout=180s
done

cat <<'EOF'

Grafana:  http://127.0.0.1:3000   (user admin)
Password: kubectl -n monitoring get secret grafana-admin -o jsonpath='{.data.admin-password}' | base64 -d
Prometheus (targets, queries): kubectl -n monitoring port-forward svc/prometheus 9090  ->  http://127.0.0.1:9090
EOF

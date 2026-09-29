#!/bin/sh
# Writes the SPA's runtime config from environment variables at container start.
set -eu
cat > /usr/share/nginx/html/config.json <<JSON
{
  "apiBaseUrl": "${API_BASE_URL:-}",
  "pollIntervalMs": ${POLL_INTERVAL_MS:-3000}
}
JSON
echo "runtime config: apiBaseUrl='${API_BASE_URL:-}' pollIntervalMs=${POLL_INTERVAL_MS:-3000} backend=${BACKEND_URL}"

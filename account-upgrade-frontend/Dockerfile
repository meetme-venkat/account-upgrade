# Independently deployable frontend: static Angular build served by unprivileged nginx.
#   docker build -t account-upgrade-frontend .
#   docker run -p 4200:8080 -e BACKEND_URL=http://host.docker.internal:8080 account-upgrade-frontend
#
# Runtime environment (no rebuild needed; validated at startup, see PRODUCTION_GUARDRAILS.md):
#   BACKEND_URL           upstream nginx proxies /api and /actuator/health to
#   API_BASE_URL          set only if the browser should call the backend directly (cross-origin; needs backend CORS)
#   POLL_INTERVAL_MS      list auto-refresh interval, >= 1000
#   API_RATE_LIMIT_RPS    per-client API rate through the proxy
#   API_RATE_LIMIT_BURST  requests allowed at once above that rate
#   HSTS_VALUE            Strict-Transport-Security value; set only when served over HTTPS

FROM node:24-alpine AS build
WORKDIR /app
COPY package.json package-lock.json .npmrc ./
RUN npm ci --ignore-scripts
COPY . .
# Guardrails: unit tests and the production build (with its size budgets) must pass to produce an image.
RUN npm run test:ci && npx ng build --configuration production

# Runs as uid 101, listens on 8080; no root at runtime.
FROM nginxinc/nginx-unprivileged:1.29-alpine
ENV BACKEND_URL=http://account-upgrade-backend:8080 \
    API_BASE_URL="" \
    POLL_INTERVAL_MS=3000 \
    API_RATE_LIMIT_RPS=20 \
    API_RATE_LIMIT_BURST=40 \
    HSTS_VALUE=""
COPY docker/nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --chmod=755 docker/10-guardrails.envsh docker/15-resolver.envsh docker/40-runtime-config.sh /docker-entrypoint.d/
COPY --from=build /app/dist/account-upgrade-frontend/browser /usr/share/nginx/html
# Only config.json is rewritten at startup; everything else stays read-only to the nginx user.
USER root
RUN chown 101:101 /usr/share/nginx/html/config.json
USER 101
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s CMD wget -qO- http://127.0.0.1:8080/healthz || exit 1

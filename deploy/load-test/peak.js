// Peak load: REQUESTS real-time upgrade requests (one HTTP call each) sent as fast as VUS concurrent clients can, from
// inside the cluster (no host port forwarding in the way). Run by the Job in this folder; see README.md.
//
// The mix matches the corner-case load: age 16 + i % 10, balance 25 + i % 10 (30 % eligible), a parent email on every
// other request, so processing and the email outbox do realistic work. User ids start with RUN_ID, so the run's records
// can be counted in PostgreSQL afterwards.
import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';

const API = __ENV.API_URL || 'http://account-upgrade-backend.account-upgrade.svc.cluster.local:8080';
const REQUESTS = parseInt(__ENV.REQUESTS || '10000', 10);
const VUS = parseInt(__ENV.VUS || '200', 10);
const RUN_ID = __ENV.RUN_ID || 'peak';

export const options = {
    scenarios: {
        peak: { executor: 'shared-iterations', vus: VUS, iterations: REQUESTS, maxDuration: '10m' },
    },
    summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
    // One connection per client, reused, as a real client pool would.
    noConnectionReuse: false,
};

export function setup() {
    const login = http.post(`${API}/api/auth/login`,
        JSON.stringify({ username: __ENV.ADMIN_USERNAME || 'admin', password: __ENV.ADMIN_PASSWORD || 'admin' }),
        { headers: { 'Content-Type': 'application/json' } });
    if (login.status !== 200) {
        exec.test.abort(`login failed: HTTP ${login.status}`);
    }
    return { token: login.json('accessToken') };
}

export default function (data) {
    const i = exec.scenario.iterationInTest;
    const request = {
        userId: `${RUN_ID}-${i}`,
        userName: 'Peak',
        age: 16 + (i % 10),
        balance: 25 + (i % 10),
    };
    if (i % 2 === 1) {
        request.parentEmail = `${RUN_ID}-${i}.parent@example.com`;
    }
    const response = http.post(`${API}/api/realtime-upgrade`, JSON.stringify(request), {
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${data.token}` },
        tags: { name: 'realtime-upgrade' },
    });
    check(response, { 'accepted (202)': r => r.status === 202 });
}

// One JSON line with what the results document needs, plus the status code counts.
export function handleSummary(data) {
    const m = data.metrics;
    const duration = m.http_req_duration ? m.http_req_duration.values : {};
    const summary = {
        runId: RUN_ID,
        requests: REQUESTS,
        vus: VUS,
        testSeconds: data.state.testRunDurationMs / 1000,
        httpRequests: m.http_reqs ? m.http_reqs.values.count : 0,
        requestsPerSecond: m.http_reqs ? m.http_reqs.values.rate : 0,
        accepted: m.checks ? m.checks.values.passes : 0,
        notAccepted: m.checks ? m.checks.values.fails : 0,
        failedRate: m.http_req_failed ? m.http_req_failed.values.rate : 0,
        latencyMs: duration,
    };
    return { stdout: `K6_SUMMARY ${JSON.stringify(summary)}\n` };
}

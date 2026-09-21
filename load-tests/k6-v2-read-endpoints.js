// Week-4 load test for the migrated Java /v2 READ endpoints.
// Compares latency/throughput against the same endpoints on Node (run twice: once with
// BASE_URL pointing at Node /api, once at Java /v2, and compare the summaries).
//
//   k6 run -e BASE_URL=http://localhost:8081 -e PREFIX=/v2 -e USER_JWT=<token> k6-v2-read-endpoints.js
//   k6 run -e BASE_URL=https://<node-host> -e PREFIX=/api -e USER_JWT=<token> k6-v2-read-endpoints.js
//
// Only GET reads are exercised here (safe to load-test). Banner cons + any write path are
// excluded on purpose.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081';
const PREFIX = __ENV.PREFIX || '/v2';
const USER_JWT = __ENV.USER_JWT || '';
const DEVICE = __ENV.DEVICE_TYPE || 'ANDROID';

const authHeaders = { 'vm-user-auth': USER_JWT };

// Per-endpoint latency trends so the summary shows each endpoint separately.
const tMaster = new Trend('ep_master', true);
const tCategory = new Trend('ep_category', true);
const tCms = new Trend('ep_cms', true);
const tExplore = new Trend('ep_explore', true);
const tMembership = new Trend('ep_membership', true);
const tNotifCount = new Trend('ep_notification_count', true);
const tSupportI18n = new Trend('ep_support_i18n', true);
const tUpdates = new Trend('ep_updates_manifest', true);

export const options = {
  scenarios: {
    steady: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 25 },
        { duration: '1m', target: 50 },
        { duration: '1m', target: 100 },
        { duration: '30s', target: 0 },
      ],
      gracefulRampDown: '10s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],       // <1% errors
    http_req_duration: ['p(95)<800'],     // 95th percentile under 800ms
  },
};

function get(path, headers, trend) {
  const res = http.get(`${BASE_URL}${PREFIX}${path}`, { headers });
  trend.add(res.timings.duration);
  check(res, {
    'status 200': (r) => r.status === 200,
    'success flag or manifest': (r) => {
      try {
        const b = r.json();
        return b.success === true || b.version !== undefined || b.languages !== undefined;
      } catch { return false; }
    },
  });
  return res;
}

export default function () {
  get(`/master?deviceType=${DEVICE}`, authHeaders, tMaster);
  get(`/v1/category?page=1&limit=10&status=true`, authHeaders, tCategory);
  get(`/v1/cms/details?userType=user&type=1`, {}, tCms);
  get(`/v1/explore?page=1&limit=10`, authHeaders, tExplore);
  get(`/v1/membership-discount?type=recharge&page=1&limit=10`, authHeaders, tMembership);
  get(`/v1/notification/count`, authHeaders, tNotifCount);
  get(`/v1/support/i18n?type=languages`, {}, tSupportI18n);
  get(`/updates/manifest.json?platform=android`, {}, tUpdates);
  sleep(1);
}

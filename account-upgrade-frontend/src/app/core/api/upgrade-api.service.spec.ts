import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { RUNTIME_CONFIG } from '../config/runtime-config';
import { ProcessedUpgrade, UpgradeRequest } from './models';
import { UpgradeApiService } from './upgrade-api.service';

const BASE = 'http://backend.test';
const REQUEST: UpgradeRequest = {
  userId: 'u1',
  userName: 'Ann',
  age: 20,
  balance: 50,
  parentEmail: null,
};

describe('UpgradeApiService', () => {
  let api: UpgradeApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: RUNTIME_CONFIG, useValue: { apiBaseUrl: BASE, pollIntervalMs: 1000 } },
      ],
    });
    api = TestBed.inject(UpgradeApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('posts a real-time request to the configured backend with the Idempotency-Key header', () => {
    let eventId: string | undefined;
    api.submitRealtime(REQUEST, '  key-1  ').subscribe((receipt) => (eventId = receipt.eventId));

    const req = http.expectOne(`${BASE}/api/realtime-upgrade`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(REQUEST);
    expect(req.request.headers.get('Idempotency-Key')).toBe('key-1');
    req.flush({ userId: 'u1', eventId: 'e1', source: 'REALTIME', status: 'ACCEPTED' });

    expect(eventId).toBe('e1');
  });

  it('omits the Idempotency-Key header when no key is given', () => {
    api.submitBatch([REQUEST, REQUEST], '').subscribe();

    const req = http.expectOne(`${BASE}/api/batch-upgrade`);
    expect(req.request.body).toHaveLength(2);
    expect(req.request.headers.has('Idempotency-Key')).toBe(false);
    req.flush({ total: 2, accepted: 2, rejected: 0, receipts: [] });
  });

  it('sends only the filters that are set and returns processed upgrades newest first', () => {
    let result: ProcessedUpgrade[] = [];
    api
      .processedUpgrades({ status: 'INELIGIBLE', userId: ' ' })
      .subscribe((items) => (result = items));

    const req = http.expectOne((r) => r.url === `${BASE}/api/processed-upgrades`);
    expect(req.request.params.get('status')).toBe('INELIGIBLE');
    expect(req.request.params.has('userId')).toBe(false);
    req.flush([processed('older'), processed('newer')]);

    expect(result.map((p) => p.eventId)).toEqual(['newer', 'older']);
  });

  it('reports the backend as up only when actuator health says UP', () => {
    const results: boolean[] = [];
    api.health().subscribe((up) => results.push(up));
    api.health().subscribe((up) => results.push(up));

    const [first, second] = http.match(`${BASE}/actuator/health`);
    first.flush({ status: 'UP' });
    second.flush({ status: 'DOWN' });

    expect(results).toEqual([true, false]);
  });
});

function processed(eventId: string): ProcessedUpgrade {
  return {
    eventId,
    userId: 'u1',
    source: 'BATCH',
    status: 'INELIGIBLE',
    reasons: ['Age must be between 18 and 23 (inclusive) but was 17'],
    notificationSent: true,
    processedAt: '2026-09-28T12:00:00Z',
  };
}

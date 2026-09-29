import {
  HttpClient,
  HttpErrorResponse,
  provideHttpClient,
  withInterceptors,
} from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { toApiError } from '../api/api-error';
import { REQUEST_TIMEOUT_MS, timeoutInterceptor } from './timeout.interceptor';

const TIMEOUT_MS = 50;

describe('timeoutInterceptor', () => {
  let http: HttpClient;
  let controller: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([timeoutInterceptor])),
        provideHttpClientTesting(),
        { provide: REQUEST_TIMEOUT_MS, useValue: TIMEOUT_MS },
      ],
    });
    http = TestBed.inject(HttpClient);
    controller = TestBed.inject(HttpTestingController);
  });

  const wait = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

  it('fails a request that gets no response in time with a readable 408', async () => {
    let error: unknown;
    http.get('/api/processed-upgrades').subscribe({ error: (e: unknown) => (error = e) });
    const pending = controller.expectOne('/api/processed-upgrades');

    await wait(TIMEOUT_MS * 3);

    expect(error).toBeInstanceOf(HttpErrorResponse);
    expect(toApiError(error)).toEqual({
      status: 408,
      message: 'Request timed out',
      details: ['The backend did not respond within 0 seconds.'],
    });
    expect(pending.cancelled).toBe(true);
  });

  it('lets a timely response through', () => {
    let body: unknown;
    http.get('/api/notifications').subscribe((b) => (body = b));

    controller.expectOne('/api/notifications').flush([]);

    expect(body).toEqual([]);
    controller.verify();
  });
});

import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { map, Observable } from 'rxjs';
import { RUNTIME_CONFIG } from '../config/runtime-config';
import {
  BatchIngestionResponse,
  EmailMessage,
  IngestionReceipt,
  ProcessedUpgrade,
  ProcessedUpgradeFilter,
  UpgradeRequest,
} from './models';

const IDEMPOTENCY_KEY = 'Idempotency-Key';

/** Typed client for the account-upgrade-backend REST API. */
@Injectable({ providedIn: 'root' })
export class UpgradeApiService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = inject(RUNTIME_CONFIG).apiBaseUrl;

  /** POST /api/realtime-upgrade. Resolves once the event is queued, not once it is processed. */
  submitRealtime(request: UpgradeRequest, idempotencyKey?: string): Observable<IngestionReceipt> {
    return this.http.post<IngestionReceipt>(this.url('/api/realtime-upgrade'), request, {
      headers: idempotencyHeaders(idempotencyKey),
    });
  }

  /** POST /api/batch-upgrade. */
  submitBatch(
    requests: UpgradeRequest[],
    idempotencyKey?: string,
  ): Observable<BatchIngestionResponse> {
    return this.http.post<BatchIngestionResponse>(this.url('/api/batch-upgrade'), requests, {
      headers: idempotencyHeaders(idempotencyKey),
    });
  }

  /** GET /api/processed-upgrades, newest first. */
  processedUpgrades(filter: ProcessedUpgradeFilter = {}): Observable<ProcessedUpgrade[]> {
    let params = new HttpParams();
    if (filter.status) {
      params = params.set('status', filter.status);
    }
    if (filter.userId?.trim()) {
      params = params.set('userId', filter.userId.trim());
    }
    return this.http
      .get<ProcessedUpgrade[]>(this.url('/api/processed-upgrades'), { params })
      .pipe(map((items) => [...items].reverse()));
  }

  /** GET /api/notifications (the backend's mock outbox), newest first. */
  notifications(): Observable<EmailMessage[]> {
    return this.http
      .get<EmailMessage[]>(this.url('/api/notifications'))
      .pipe(map((items) => [...items].reverse()));
  }

  /** GET /actuator/health; emits true only when the backend reports UP. */
  health(): Observable<boolean> {
    return this.http
      .get<{ status: string }>(this.url('/actuator/health'))
      .pipe(map((body) => body.status === 'UP'));
  }

  private url(path: string): string {
    return this.baseUrl + path;
  }
}

function idempotencyHeaders(key?: string): HttpHeaders {
  const trimmed = key?.trim();
  return trimmed ? new HttpHeaders({ [IDEMPOTENCY_KEY]: trimmed }) : new HttpHeaders();
}

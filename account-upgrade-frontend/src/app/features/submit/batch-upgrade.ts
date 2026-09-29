import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { FormArray, ReactiveFormsModule } from '@angular/forms';
import { finalize } from 'rxjs';
import { ApiError, toApiError } from '../../core/api/api-error';
import { BatchIngestionResponse, UpgradeRequest } from '../../core/api/models';
import { UpgradeApiService } from '../../core/api/upgrade-api.service';
import { ErrorPanel } from '../../shared/error-panel';
import { IdempotencyKeyField } from '../../shared/idempotency-key-field';
import { ReceiptTable } from '../../shared/receipt-table';
import {
  createUpgradeRequestForm,
  SAMPLE_BATCH,
  toUpgradeRequest,
  UpgradeRequestForm,
} from '../../shared/upgrade-request-form';

/** Builds a list of requests (row by row or as pasted JSON) and sends it to POST /api/batch-upgrade. */
@Component({
  selector: 'app-batch-upgrade',
  imports: [ReactiveFormsModule, IdempotencyKeyField, ErrorPanel, ReceiptTable],
  templateUrl: './batch-upgrade.html',
})
export class BatchUpgrade {
  private readonly api = inject(UpgradeApiService);

  protected readonly rows = new FormArray<UpgradeRequestForm>(
    SAMPLE_BATCH.map((request) => createUpgradeRequestForm(request)),
  );
  protected readonly jsonMode = signal(false);
  protected readonly jsonText = signal('');
  protected readonly jsonError = signal<string | null>(null);
  protected readonly idempotencyKey = signal('');
  protected readonly submitting = signal(false);
  protected readonly response = signal<BatchIngestionResponse | null>(null);
  protected readonly error = signal<ApiError | null>(null);

  addRow(): void {
    this.rows.push(createUpgradeRequestForm());
  }

  removeRow(index: number): void {
    this.rows.removeAt(index);
  }

  loadSample(): void {
    this.setRows(SAMPLE_BATCH);
    if (this.jsonMode()) {
      this.jsonText.set(JSON.stringify(SAMPLE_BATCH, null, 2));
    }
  }

  /** Switches between the row editor and the JSON editor, carrying the data across. */
  toggleJsonMode(): void {
    if (this.jsonMode()) {
      if (!this.applyJson()) {
        return;
      }
    } else {
      this.jsonText.set(JSON.stringify(this.currentRequests(), null, 2));
    }
    this.jsonError.set(null);
    this.jsonMode.update((on) => !on);
  }

  submit(): void {
    if (this.jsonMode() && !this.applyJson()) {
      return;
    }
    if (this.rows.length === 0) {
      this.error.set({ status: 0, message: 'Add at least one request', details: [] });
      return;
    }
    if (this.rows.invalid) {
      this.rows.markAllAsTouched();
      this.error.set({
        status: 0,
        message: 'Fix the highlighted rows before sending',
        details: [],
      });
      return;
    }
    this.submitting.set(true);
    this.error.set(null);
    this.response.set(null);
    this.api
      .submitBatch(this.currentRequests(), this.idempotencyKey())
      .pipe(finalize(() => this.submitting.set(false)))
      .subscribe({
        next: (response) => this.response.set(response),
        error: (err: unknown) => {
          // A 503 still carries per-item receipts when the broker rejected every item.
          if (err instanceof HttpErrorResponse && isBatchResponse(err.error)) {
            this.response.set(err.error);
          }
          this.error.set(toApiError(err));
        },
      });
  }

  private currentRequests(): UpgradeRequest[] {
    return this.rows.controls.map((row) => toUpgradeRequest(row.getRawValue()));
  }

  private applyJson(): boolean {
    try {
      const parsed: unknown = JSON.parse(this.jsonText());
      if (!Array.isArray(parsed)) {
        throw new Error('expected a JSON array of requests');
      }
      this.setRows(parsed as Partial<UpgradeRequest>[]);
      this.jsonError.set(null);
      return true;
    } catch (e) {
      this.jsonError.set(e instanceof Error ? e.message : String(e));
      return false;
    }
  }

  private setRows(requests: Partial<UpgradeRequest>[]): void {
    this.rows.clear();
    requests.forEach((request) => this.rows.push(createUpgradeRequestForm(request ?? {})));
  }
}

function isBatchResponse(body: unknown): body is BatchIngestionResponse {
  return typeof body === 'object' && body !== null && 'receipts' in body;
}

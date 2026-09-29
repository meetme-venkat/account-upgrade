import { Component, inject, signal } from '@angular/core';
import { ReactiveFormsModule } from '@angular/forms';
import { finalize } from 'rxjs';
import { ApiError, toApiError } from '../../core/api/api-error';
import { IngestionReceipt } from '../../core/api/models';
import { UpgradeApiService } from '../../core/api/upgrade-api.service';
import { ErrorPanel } from '../../shared/error-panel';
import { IdempotencyKeyField } from '../../shared/idempotency-key-field';
import { ReceiptTable } from '../../shared/receipt-table';
import { createUpgradeRequestForm, toUpgradeRequest } from '../../shared/upgrade-request-form';
import { RequestFields } from './request-fields';

/** Sends a single request to POST /api/realtime-upgrade. */
@Component({
  selector: 'app-realtime-upgrade',
  imports: [ReactiveFormsModule, RequestFields, IdempotencyKeyField, ErrorPanel, ReceiptTable],
  template: `
    <form [formGroup]="form" (ngSubmit)="submit()" novalidate>
      <app-request-fields [form]="form" />
      <app-idempotency-key-field [(key)]="idempotencyKey" />

      <div class="actions">
        <button type="submit" [disabled]="submitting()">
          {{ submitting() ? 'Sending...' : 'Send real-time request' }}
        </button>
        <button type="button" class="btn-secondary" (click)="reset()">Clear</button>
      </div>
    </form>

    <app-error-panel [error]="error()" />
    @if (receipt(); as receipt) {
      <div class="alert alert-info">
        Queued on <code>upgrade-requests</code>. Eligibility is decided asynchronously.
      </div>
      <app-receipt-table [receipts]="[receipt]" />
    }
  `,
})
export class RealtimeUpgrade {
  private readonly api = inject(UpgradeApiService);

  protected readonly form = createUpgradeRequestForm();
  protected readonly idempotencyKey = signal('');
  protected readonly submitting = signal(false);
  protected readonly receipt = signal<IngestionReceipt | null>(null);
  protected readonly error = signal<ApiError | null>(null);

  submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.submitting.set(true);
    this.error.set(null);
    this.receipt.set(null);
    this.api
      .submitRealtime(toUpgradeRequest(this.form.getRawValue()), this.idempotencyKey())
      .pipe(finalize(() => this.submitting.set(false)))
      .subscribe({
        next: (receipt) => this.receipt.set(receipt),
        error: (err: unknown) => this.error.set(toApiError(err)),
      });
  }

  reset(): void {
    this.form.reset();
    this.idempotencyKey.set('');
    this.receipt.set(null);
    this.error.set(null);
  }
}

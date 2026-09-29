import { DatePipe } from '@angular/common';
import { Component, computed, inject, input, linkedSignal, signal } from '@angular/core';
import { ProcessingStatus } from '../../core/api/models';
import { UpgradeApiService } from '../../core/api/upgrade-api.service';
import { RUNTIME_CONFIG } from '../../core/config/runtime-config';
import { ErrorPanel } from '../../shared/error-panel';
import { polledQuery } from '../../shared/polled-query';

const PAGE_SIZE = 50;

/** Lists GET /api/processed-upgrades with filters, auto-refresh and client-side paging. */
@Component({
  selector: 'app-processed-upgrades-page',
  imports: [DatePipe, ErrorPanel],
  templateUrl: './processed-upgrades-page.html',
})
export class ProcessedUpgradesPage {
  private readonly api = inject(UpgradeApiService);

  /** Bound from the {@code ?userId=} query parameter (links from ingestion receipts). */
  readonly userId = input<string>();

  protected readonly userIdFilter = linkedSignal(() => this.userId() ?? '');
  protected readonly statusFilter = signal<ProcessingStatus | ''>('');
  protected readonly autoRefresh = signal(true);
  protected readonly page = linkedSignal({
    source: () => [this.userIdFilter(), this.statusFilter()],
    computation: () => 0,
  });

  protected readonly query = polledQuery({
    params: computed(() => ({ userId: this.userIdFilter(), status: this.statusFilter() || null })),
    autoRefresh: this.autoRefresh,
    intervalMs: inject(RUNTIME_CONFIG).pollIntervalMs,
    fetch: (filter) => this.api.processedUpgrades(filter),
  });

  protected readonly items = computed(() => this.query.data() ?? []);
  protected readonly stats = computed(() => {
    const items = this.items();
    return {
      total: items.length,
      eligible: items.filter((i) => i.status === 'ELIGIBLE').length,
      ineligible: items.filter((i) => i.status === 'INELIGIBLE').length,
      notificationFailed: items.filter((i) => !i.notificationSent).length,
    };
  });
  protected readonly pageCount = computed(() =>
    Math.max(1, Math.ceil(this.items().length / PAGE_SIZE)),
  );
  protected readonly pageItems = computed(() =>
    this.items().slice(this.page() * PAGE_SIZE, (this.page() + 1) * PAGE_SIZE),
  );

  protected setStatus(value: string): void {
    this.statusFilter.set(value as ProcessingStatus | '');
  }
}

import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { RecipientRole } from '../../core/api/models';
import { UpgradeApiService } from '../../core/api/upgrade-api.service';
import { RUNTIME_CONFIG } from '../../core/config/runtime-config';
import { ErrorPanel } from '../../shared/error-panel';
import { polledQuery } from '../../shared/polled-query';

/** Shows the backend's mock email outbox (GET /api/notifications). */
@Component({
  selector: 'app-notifications-page',
  imports: [DatePipe, ErrorPanel],
  templateUrl: './notifications-page.html',
})
export class NotificationsPage {
  private readonly api = inject(UpgradeApiService);

  protected readonly search = signal('');
  protected readonly role = signal<RecipientRole | ''>('');
  protected readonly autoRefresh = signal(true);

  protected readonly query = polledQuery({
    params: signal(null),
    autoRefresh: this.autoRefresh,
    intervalMs: inject(RUNTIME_CONFIG).pollIntervalMs,
    fetch: () => this.api.notifications(),
  });

  protected readonly messages = computed(() => {
    const term = this.search().trim().toLowerCase();
    const role = this.role();
    return (this.query.data() ?? []).filter(
      (m) =>
        (!role || m.role === role) &&
        (!term ||
          [m.recipient, m.subject, m.body].some((field) => field.toLowerCase().includes(term))),
    );
  });

  protected setRole(value: string): void {
    this.role.set(value as RecipientRole | '');
  }
}

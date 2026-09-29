import { Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { IngestionReceipt } from '../core/api/models';

/** Ingestion receipts with a link to each user's processed outcome. */
@Component({
  selector: 'app-receipt-table',
  imports: [RouterLink],
  template: `
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>User</th>
            <th>Status</th>
            <th>Event ID</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          @for (receipt of receipts(); track $index) {
            <tr>
              <td>{{ receipt.userId }}</td>
              <td>
                <span
                  class="badge"
                  [class.badge-ok]="receipt.status === 'ACCEPTED'"
                  [class.badge-bad]="receipt.status === 'REJECTED'"
                  >{{ receipt.status }}</span
                >
                @if (receipt.error) {
                  <div class="muted small">{{ receipt.error }}</div>
                }
              </td>
              <td class="mono">{{ receipt.eventId ?? '–' }}</td>
              <td>
                @if (receipt.status === 'ACCEPTED') {
                  <a [routerLink]="['/processed']" [queryParams]="{ userId: receipt.userId }"
                    >View outcome</a
                  >
                }
              </td>
            </tr>
          }
        </tbody>
      </table>
    </div>
  `,
})
export class ReceiptTable {
  readonly receipts = input.required<IngestionReceipt[]>();
}

import { Component, signal } from '@angular/core';
import { BatchUpgrade } from './batch-upgrade';
import { RealtimeUpgrade } from './realtime-upgrade';

@Component({
  selector: 'app-submit-page',
  imports: [RealtimeUpgrade, BatchUpgrade],
  template: `
    <header class="page-header">
      <h1>Submit upgrade requests</h1>
      <p class="muted">
        Requests are queued and checked asynchronously. To be eligible, the name must not be empty,
        age must be 18 to 23 and balance at least $30.
      </p>
    </header>

    <div class="tabs" role="tablist">
      <button
        type="button"
        role="tab"
        [class.active]="tab() === 'realtime'"
        [attr.aria-selected]="tab() === 'realtime'"
        (click)="tab.set('realtime')"
      >
        Real-time
      </button>
      <button
        type="button"
        role="tab"
        [class.active]="tab() === 'batch'"
        [attr.aria-selected]="tab() === 'batch'"
        (click)="tab.set('batch')"
      >
        Batch
      </button>
    </div>

    <section class="card">
      @if (tab() === 'realtime') {
        <app-realtime-upgrade />
      } @else {
        <app-batch-upgrade />
      }
    </section>
  `,
})
export class SubmitPage {
  protected readonly tab = signal<'realtime' | 'batch'>('realtime');
}

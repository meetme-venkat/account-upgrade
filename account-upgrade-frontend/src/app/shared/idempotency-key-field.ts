import { Component, model } from '@angular/core';
import { newIdempotencyKey } from './upgrade-request-form';

/** Optional Idempotency-Key input with a generator. Keep the key to retry a submission safely. */
@Component({
  selector: 'app-idempotency-key-field',
  template: `
    <label class="idempotency">
      Idempotency-Key
      <span class="muted">(optional; resend with the same key to retry safely)</span>
      <span class="input-row">
        <input
          [value]="key()"
          (input)="key.set($any($event.target).value)"
          placeholder="none"
          autocomplete="off"
        />
        <button type="button" class="btn-secondary" (click)="key.set(generate())">Generate</button>
      </span>
    </label>
  `,
})
export class IdempotencyKeyField {
  readonly key = model('');
  protected readonly generate = newIdempotencyKey;
}

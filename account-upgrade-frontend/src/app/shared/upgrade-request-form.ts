import { FormControl, FormGroup, Validators } from '@angular/forms';
import { UpgradeRequest } from '../core/api/models';

/**
 * Form model for one upgrade request. Only the structural checks the backend enforces at
 * ingestion are validated here (userId required, parentEmail well-formed). Eligibility rules
 * (age, balance, name) are deliberately left to the backend so ineligible requests can be sent.
 */
export type UpgradeRequestForm = FormGroup<{
  userId: FormControl<string>;
  userName: FormControl<string>;
  age: FormControl<number | null>;
  balance: FormControl<number | null>;
  parentEmail: FormControl<string>;
}>;

export function createUpgradeRequestForm(
  initial: Partial<UpgradeRequest> = {},
): UpgradeRequestForm {
  return new FormGroup({
    userId: new FormControl(initial.userId ?? '', {
      nonNullable: true,
      validators: [Validators.required, Validators.pattern(/\S/)],
    }),
    userName: new FormControl(initial.userName ?? '', { nonNullable: true }),
    age: new FormControl<number | null>(initial.age ?? null, [Validators.pattern(/^-?\d+$/)]),
    balance: new FormControl<number | null>(initial.balance ?? null),
    parentEmail: new FormControl(initial.parentEmail ?? '', {
      nonNullable: true,
      validators: [Validators.email],
    }),
  });
}

/** Maps form values to the request body: trims text and sends blanks as {@code null}. */
export function toUpgradeRequest(value: UpgradeRequestForm['value']): UpgradeRequest {
  return {
    userId: (value.userId ?? '').trim(),
    // An empty name is sent as "" (not null) so the backend records the userName rule failure.
    userName: value.userName ?? '',
    age: toNumber(value.age),
    balance: toNumber(value.balance),
    parentEmail: value.parentEmail?.trim() || null,
  };
}

function toNumber(value: number | string | null | undefined): number | null {
  if (value === null || value === undefined || value === '') {
    return null;
  }
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

/** A fresh idempotency key; reuse it when retrying the same submission. */
export function newIdempotencyKey(): string {
  return crypto.randomUUID();
}

export const SAMPLE_BATCH: UpgradeRequest[] = [
  {
    userId: 'u100',
    userName: 'Alice',
    age: 19,
    balance: 120.5,
    parentEmail: 'alice.parent@example.com',
  },
  { userId: 'u101', userName: 'Bob', age: 23, balance: 30, parentEmail: null },
  { userId: 'u102', userName: '', age: 17, balance: 12.75, parentEmail: 'carl.parent@example.com' },
];

import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { BatchIngestionResponse } from '../../core/api/models';
import { UpgradeApiService } from '../../core/api/upgrade-api.service';
import { BatchUpgrade } from './batch-upgrade';

describe('BatchUpgrade', () => {
  let fixture: ComponentFixture<BatchUpgrade>;
  let element: HTMLElement;
  const api = { submitBatch: vi.fn() };

  beforeEach(async () => {
    api.submitBatch.mockReset();
    await TestBed.configureTestingModule({
      imports: [BatchUpgrade],
      providers: [provideRouter([]), { provide: UpgradeApiService, useValue: api }],
    }).compileComponents();
    fixture = TestBed.createComponent(BatchUpgrade);
    element = fixture.nativeElement;
    await fixture.whenStable();
  });

  function button(label: string): HTMLButtonElement {
    const match = Array.from(element.querySelectorAll('button')).find(
      (b) => b.textContent?.trim() === label,
    );
    if (!match) {
      throw new Error(`No button "${label}"`);
    }
    return match;
  }

  async function click(label: string): Promise<void> {
    button(label).click();
    await fixture.whenStable();
  }

  async function typeJson(text: string): Promise<void> {
    const textarea = element.querySelector<HTMLTextAreaElement>('textarea')!;
    textarea.value = text;
    textarea.dispatchEvent(new Event('input'));
    await fixture.whenStable();
  }

  const accepted = (total: number): BatchIngestionResponse => ({
    total,
    accepted: total,
    rejected: 0,
    receipts: Array.from({ length: total }, (_, i) => ({
      userId: `u${i}`,
      eventId: `e${i}`,
      source: 'BATCH' as const,
      status: 'ACCEPTED' as const,
    })),
  });

  it('sends the sample batch and shows the summary', async () => {
    api.submitBatch.mockReturnValue(of(accepted(3)));

    await click('Send batch');

    const [requests, key] = api.submitBatch.mock.calls[0];
    expect(requests.map((r: { userId: string }) => r.userId)).toEqual(['u100', 'u101', 'u102']);
    expect(key).toBe('');
    expect(element.querySelectorAll('.stat-value')[1].textContent).toBe('3');
  });

  it('edits the batch as JSON, rejecting invalid JSON without losing the editor', async () => {
    api.submitBatch.mockReturnValue(of(accepted(1)));
    await click('Edit as JSON');
    expect(element.querySelector('textarea')!.value).toContain('"userId": "u100"');

    await typeJson('{"not": "an array"}');
    await click('Send batch');
    expect(api.submitBatch).not.toHaveBeenCalled();
    expect(element.textContent).toContain('Invalid JSON: expected a JSON array of requests');

    await typeJson('[{"userId":"j1","userName":"Jo","age":20,"balance":40,"parentEmail":null}]');
    await click('Send batch');
    expect(api.submitBatch.mock.calls[0][0]).toEqual([
      { userId: 'j1', userName: 'Jo', age: 20, balance: 40, parentEmail: null },
    ]);
  });

  it('refuses to send an empty batch or rows with a missing userId', async () => {
    await click('Add row');
    await click('Send batch');
    expect(element.textContent).toContain('Fix the highlighted rows before sending');

    for (let i = 0; i < 4; i++) {
      element.querySelector<HTMLButtonElement>('button[aria-label="Remove row"]')!.click();
      await fixture.whenStable();
    }
    await click('Send batch');
    expect(element.textContent).toContain('Add at least one request');
    expect(api.submitBatch).not.toHaveBeenCalled();
  });

  it('shows per-item receipts together with the error when the broker rejects everything', async () => {
    const body: BatchIngestionResponse = {
      total: 1,
      accepted: 0,
      rejected: 1,
      receipts: [{ userId: 'u100', source: 'BATCH', status: 'REJECTED', error: 'queue full' }],
    };
    api.submitBatch.mockReturnValue(
      throwError(() => new HttpErrorResponse({ status: 503, error: body })),
    );

    await click('Send batch');

    expect(element.textContent).toContain('queue full');
    expect(element.querySelector('[role="alert"]')?.textContent).toContain('HTTP 503');
  });
});

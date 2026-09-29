import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { ProcessedUpgrade } from '../../core/api/models';
import { UpgradeApiService } from '../../core/api/upgrade-api.service';
import { RUNTIME_CONFIG } from '../../core/config/runtime-config';
import { ProcessedUpgradesPage } from './processed-upgrades-page';

/** Filters are debounced by 200 ms before a fetch. */
const settle = async (fixture: ComponentFixture<unknown>) => {
  await new Promise((resolve) => setTimeout(resolve, 250));
  await fixture.whenStable();
};

const item = (
  eventId: string,
  status: ProcessedUpgrade['status'],
  sent = true,
): ProcessedUpgrade => ({
  eventId,
  userId: `user-${eventId}`,
  source: 'REALTIME',
  status,
  reasons: status === 'INELIGIBLE' ? ['Balance must be at least $30 but was $10'] : [],
  notificationSent: sent,
  processedAt: '2026-09-28T12:00:00Z',
});

describe('ProcessedUpgradesPage', () => {
  let fixture: ComponentFixture<ProcessedUpgradesPage>;
  let element: HTMLElement;
  const api = { processedUpgrades: vi.fn() };

  beforeEach(async () => {
    api.processedUpgrades.mockReset();
    api.processedUpgrades.mockReturnValue(
      of([item('a', 'ELIGIBLE'), item('b', 'INELIGIBLE'), item('c', 'INELIGIBLE', false)]),
    );
    await TestBed.configureTestingModule({
      imports: [ProcessedUpgradesPage],
      providers: [
        { provide: UpgradeApiService, useValue: api },
        { provide: RUNTIME_CONFIG, useValue: { apiBaseUrl: '', pollIntervalMs: 60_000 } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(ProcessedUpgradesPage);
    element = fixture.nativeElement;
  });

  const stats = () =>
    Array.from(element.querySelectorAll('.stat-value')).map((s) => s.textContent?.trim());

  it('shows outcomes, reasons and summary counts', async () => {
    await settle(fixture);

    expect(api.processedUpgrades).toHaveBeenCalledWith({ userId: '', status: null });
    expect(element.querySelectorAll('tbody tr')).toHaveLength(3);
    expect(element.textContent).toContain('Balance must be at least $30 but was $10');
    expect(stats()).toEqual(['3', '1', '2', '1']);
  });

  it('filters by the userId from the query string and by status', async () => {
    fixture.componentRef.setInput('userId', 'u42');
    await settle(fixture);
    expect(api.processedUpgrades).toHaveBeenLastCalledWith({ userId: 'u42', status: null });

    const select = element.querySelector('select')!;
    select.value = 'INELIGIBLE';
    select.dispatchEvent(new Event('change'));
    await settle(fixture);

    expect(api.processedUpgrades).toHaveBeenLastCalledWith({ userId: 'u42', status: 'INELIGIBLE' });
  });

  it('refetches on Refresh and shows backend errors', async () => {
    await settle(fixture);
    api.processedUpgrades.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 0 })));

    const refresh = Array.from(element.querySelectorAll('button')).find(
      (b) => b.textContent?.trim() === 'Refresh',
    )!;
    refresh.click();
    await settle(fixture);

    expect(api.processedUpgrades).toHaveBeenCalledTimes(2);
    expect(element.querySelector('[role="alert"]')?.textContent).toContain('Backend unreachable');
    // The last good data stays visible while the backend is unreachable.
    expect(element.querySelectorAll('tbody tr')).toHaveLength(3);
  });

  it('pages through more than 50 results', async () => {
    api.processedUpgrades.mockReturnValue(
      of(Array.from({ length: 120 }, (_, i) => item(`e${i}`, 'ELIGIBLE'))),
    );
    await settle(fixture);
    expect(element.querySelectorAll('tbody tr')).toHaveLength(50);
    expect(element.textContent).toContain('Page 1 of 3');

    const next = Array.from(element.querySelectorAll('button')).find(
      (b) => b.textContent?.trim() === 'Next',
    )!;
    next.click();
    next.click();
    await fixture.whenStable();

    expect(element.textContent).toContain('Page 3 of 3');
    expect(element.querySelectorAll('tbody tr')).toHaveLength(20);
  });
});

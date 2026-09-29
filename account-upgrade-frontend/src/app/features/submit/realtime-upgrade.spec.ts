import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { UpgradeApiService } from '../../core/api/upgrade-api.service';
import { RealtimeUpgrade } from './realtime-upgrade';

describe('RealtimeUpgrade', () => {
  let fixture: ComponentFixture<RealtimeUpgrade>;
  let element: HTMLElement;
  const api = { submitRealtime: vi.fn() };

  beforeEach(async () => {
    api.submitRealtime.mockReset();
    await TestBed.configureTestingModule({
      imports: [RealtimeUpgrade],
      providers: [provideRouter([]), { provide: UpgradeApiService, useValue: api }],
    }).compileComponents();
    fixture = TestBed.createComponent(RealtimeUpgrade);
    element = fixture.nativeElement;
    await fixture.whenStable();
  });

  function type(label: string, value: string): void {
    const input = element.querySelector<HTMLInputElement>(`input[formcontrolname="${label}"]`)!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }

  async function submit(): Promise<void> {
    element.querySelector<HTMLButtonElement>('button[type="submit"]')!.click();
    await fixture.whenStable();
  }

  it('does not call the API when userId is missing', async () => {
    await submit();

    expect(api.submitRealtime).not.toHaveBeenCalled();
    expect(element.textContent).toContain('User ID is required');
  });

  it('submits the request and shows the receipt', async () => {
    api.submitRealtime.mockReturnValue(
      of({ userId: 'u200', eventId: 'evt-1', source: 'REALTIME', status: 'ACCEPTED' }),
    );
    type('userId', 'u200');
    type('userName', 'Dana');
    type('age', '25');
    type('balance', '29.99');

    await submit();

    expect(api.submitRealtime).toHaveBeenCalledWith(
      { userId: 'u200', userName: 'Dana', age: 25, balance: 29.99, parentEmail: null },
      '',
    );
    expect(element.textContent).toContain('evt-1');
    expect(element.textContent).toContain('ACCEPTED');
  });

  it('shows backend validation errors', async () => {
    api.submitRealtime.mockReturnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 400,
            error: {
              title: 'Validation failed',
              errors: ['parentEmail: parentEmail must be a valid email address'],
            },
          }),
      ),
    );
    type('userId', 'u1');

    await submit();

    expect(element.querySelector('[role="alert"]')?.textContent).toContain(
      'parentEmail must be a valid email',
    );
  });
});

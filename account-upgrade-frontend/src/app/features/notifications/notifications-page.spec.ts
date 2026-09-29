import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { EmailMessage } from '../../core/api/models';
import { UpgradeApiService } from '../../core/api/upgrade-api.service';
import { RUNTIME_CONFIG } from '../../core/config/runtime-config';
import { NotificationsPage } from './notifications-page';

const message = (role: EmailMessage['role'], recipient: string, subject: string): EmailMessage => ({
  eventId: `evt-${recipient}`,
  role,
  recipient,
  subject,
  body: `Hello ${recipient}`,
  createdAt: '2026-09-28T12:00:00Z',
});

describe('NotificationsPage', () => {
  let fixture: ComponentFixture<NotificationsPage>;
  let element: HTMLElement;

  beforeEach(async () => {
    const api = {
      notifications: () =>
        of([
          message('USER', 'u1', 'Your account upgrade was approved'),
          message('PARENT', 'mom@example.com', 'Account upgrade approved for u1'),
          message('USER', 'u2', 'Your account upgrade request was declined'),
        ]),
    };
    await TestBed.configureTestingModule({
      imports: [NotificationsPage],
      providers: [
        { provide: UpgradeApiService, useValue: api },
        { provide: RUNTIME_CONFIG, useValue: { apiBaseUrl: '', pollIntervalMs: 60_000 } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(NotificationsPage);
    element = fixture.nativeElement;
    await new Promise((resolve) => setTimeout(resolve, 250));
    await fixture.whenStable();
  });

  const subjects = () =>
    Array.from(element.querySelectorAll('article h3')).map((h) => h.textContent?.trim());

  it('lists every simulated email', () => {
    expect(subjects()).toHaveLength(3);
  });

  it('filters by recipient role', async () => {
    const select = element.querySelector('select')!;
    select.value = 'PARENT';
    select.dispatchEvent(new Event('change'));
    await fixture.whenStable();

    expect(subjects()).toEqual(['Account upgrade approved for u1']);
  });

  it('searches recipient, subject and body text', async () => {
    const search = element.querySelector<HTMLInputElement>('input[type="search"]')!;
    search.value = 'DECLINED';
    search.dispatchEvent(new Event('input'));
    await fixture.whenStable();

    expect(subjects()).toEqual(['Your account upgrade request was declined']);

    search.value = 'nobody';
    search.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    expect(element.textContent).toContain('No notifications match.');
  });
});

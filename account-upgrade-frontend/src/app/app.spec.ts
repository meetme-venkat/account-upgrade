import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { of } from 'rxjs';
import { App } from './app';
import { UpgradeApiService } from './core/api/upgrade-api.service';
import { AuthService } from './core/auth/auth.service';

describe('App', () => {
  const loggedIn = signal(true);
  const auth = {
    loggedIn,
    username: signal('admin'),
    logout: vi.fn(() => loggedIn.set(false)),
  };

  async function render(up: boolean, signedIn = true): Promise<HTMLElement> {
    loggedIn.set(signedIn);
    auth.logout.mockClear();
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideRouter([]),
        { provide: UpgradeApiService, useValue: { health: () => of(up) } },
        { provide: AuthService, useValue: auth },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    return fixture.nativeElement;
  }

  it('renders the navigation and the signed-in user', async () => {
    const element = await render(true);
    const links = Array.from(element.querySelectorAll('nav a')).map((a) => a.textContent?.trim());
    expect(links).toEqual(['Submit', 'Processed', 'Notifications']);
    expect(element.querySelector('.user')?.textContent).toContain('admin');
  });

  it('hides the navigation until the user signs in', async () => {
    const element = await render(true, false);
    expect(element.querySelectorAll('nav a').length).toBe(0);
    expect(element.querySelector('.user')).toBeNull();
  });

  it('signs out and goes to the login page', async () => {
    const element = await render(true);
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);

    element.querySelector<HTMLButtonElement>('.user button')!.click();

    expect(auth.logout).toHaveBeenCalled();
    expect(navigate).toHaveBeenCalledWith(['/login']);
  });

  it('shows the backend health', async () => {
    const element = await render(false);
    expect(element.querySelector('.health')?.textContent).toContain('Backend down');
  });
});

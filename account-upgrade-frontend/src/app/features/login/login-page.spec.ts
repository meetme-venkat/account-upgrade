import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { LoginPage, safeReturnUrl } from './login-page';

describe('LoginPage', () => {
  let fixture: ComponentFixture<LoginPage>;
  let element: HTMLElement;
  let navigateByUrl: ReturnType<typeof vi.spyOn>;
  const auth = { login: vi.fn() };

  async function render(inputs: { returnUrl?: string; reason?: string } = {}): Promise<void> {
    auth.login.mockReset();
    await TestBed.configureTestingModule({
      imports: [LoginPage],
      providers: [provideRouter([]), { provide: AuthService, useValue: auth }],
    }).compileComponents();
    navigateByUrl = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    fixture = TestBed.createComponent(LoginPage);
    for (const [name, value] of Object.entries(inputs)) {
      fixture.componentRef.setInput(name, value);
    }
    element = fixture.nativeElement;
    await fixture.whenStable();
  }

  function type(name: string, value: string): void {
    const input = element.querySelector<HTMLInputElement>(`input[formcontrolname="${name}"]`)!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }

  async function submit(): Promise<void> {
    element.querySelector<HTMLButtonElement>('button[type="submit"]')!.click();
    await fixture.whenStable();
  }

  it('requires both fields', async () => {
    await render();

    await submit();

    expect(auth.login).not.toHaveBeenCalled();
    expect(element.textContent).toContain('Username is required');
    expect(element.textContent).toContain('Password is required');
  });

  it('signs in and goes back to the page the user wanted', async () => {
    await render({ returnUrl: '/processed?status=ELIGIBLE' });
    auth.login.mockReturnValue(of(undefined));
    type('username', ' admin ');
    type('password', 'admin');

    await submit();

    expect(auth.login).toHaveBeenCalledWith('admin', 'admin');
    expect(navigateByUrl).toHaveBeenCalledWith('/processed?status=ELIGIBLE');
  });

  it('goes to the submit page by default', async () => {
    await render();
    auth.login.mockReturnValue(of(undefined));
    type('username', 'admin');
    type('password', 'admin');

    await submit();

    expect(navigateByUrl).toHaveBeenCalledWith('/submit');
  });

  it('shows a clear message for wrong credentials and clears the password', async () => {
    await render();
    auth.login.mockReturnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 401,
            error: { title: 'Login failed', detail: 'Invalid username or password' },
          }),
      ),
    );
    type('username', 'admin');
    type('password', 'wrong');

    await submit();

    expect(element.querySelector('[role="alert"]')?.textContent).toContain(
      'Invalid username or password',
    );
    expect(element.querySelector<HTMLInputElement>('input[type="password"]')!.value).toBe('');
    expect(navigateByUrl).not.toHaveBeenCalled();
  });

  it('shows other failures as they are', async () => {
    await render();
    auth.login.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 0 })));
    type('username', 'admin');
    type('password', 'admin');

    await submit();

    expect(element.textContent).toContain('Backend unreachable');
  });

  it('explains why the user was sent back to sign in', async () => {
    await render({ reason: 'expired' });

    expect(element.textContent).toContain('Your session has ended');
  });
});

describe('safeReturnUrl', () => {
  it('keeps paths inside the app', () => {
    expect(safeReturnUrl('/notifications')).toBe('/notifications');
  });

  it('falls back to the submit page for anything else', () => {
    expect(safeReturnUrl(undefined)).toBe('/submit');
    expect(safeReturnUrl('')).toBe('/submit');
    expect(safeReturnUrl('https://evil.example')).toBe('/submit');
    expect(safeReturnUrl('//evil.example')).toBe('/submit');
    expect(safeReturnUrl('/login?returnUrl=%2Fsubmit')).toBe('/submit');
  });
});

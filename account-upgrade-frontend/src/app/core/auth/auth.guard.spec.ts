import { TestBed } from '@angular/core/testing';
import {
  ActivatedRouteSnapshot,
  provideRouter,
  Router,
  RouterStateSnapshot,
  UrlTree,
} from '@angular/router';
import { authGuard, guestGuard } from './auth.guard';
import { AuthService } from './auth.service';

describe('auth guards', () => {
  const auth = { isAuthenticated: vi.fn<() => boolean>() };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), { provide: AuthService, useValue: auth }],
    });
  });

  const run = (guard: typeof authGuard, url = '/processed?status=ELIGIBLE') =>
    TestBed.runInInjectionContext(() =>
      guard({} as ActivatedRouteSnapshot, { url } as RouterStateSnapshot),
    );
  const serialize = (result: unknown) => TestBed.inject(Router).serializeUrl(result as UrlTree);

  it('lets a logged-in user through', () => {
    auth.isAuthenticated.mockReturnValue(true);

    expect(run(authGuard)).toBe(true);
  });

  it('sends everyone else to the login page, remembering where they were going', () => {
    auth.isAuthenticated.mockReturnValue(false);

    expect(serialize(run(authGuard))).toBe('/login?returnUrl=%2Fprocessed%3Fstatus%3DELIGIBLE');
  });

  it('shows the login page only to users who are not logged in', () => {
    auth.isAuthenticated.mockReturnValue(false);
    expect(run(guestGuard)).toBe(true);

    auth.isAuthenticated.mockReturnValue(true);
    expect(serialize(run(guestGuard))).toBe('/submit');
  });
});

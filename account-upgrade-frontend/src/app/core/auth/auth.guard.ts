import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

/** Pages that need a login: without a valid session, go to the login page and come back afterwards. */
export const authGuard: CanActivateFn = (_route, state) =>
  inject(AuthService).isAuthenticated() ||
  inject(Router).createUrlTree(['/login'], { queryParams: { returnUrl: state.url } });

/** The login page itself: someone already logged in goes straight to the app. */
export const guestGuard: CanActivateFn = () =>
  !inject(AuthService).isAuthenticated() || inject(Router).createUrlTree(['/submit']);

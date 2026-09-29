import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { AuthService } from './auth.service';

/**
 * Sends the access token with every API call ({@code Authorization: Bearer <token>}). When the backend
 * rejects it (401: missing, expired or invalid), the session ends and the user is sent to the login
 * page, returning to the current page afterwards.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  if (!auth.needsToken(req.url)) {
    return next(req);
  }
  const router = inject(Router);
  const token = auth.token();
  const request = token ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : req;
  return next(request).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && error.status === 401) {
        auth.logout();
        if (!router.url.startsWith('/login')) {
          void router.navigate(['/login'], {
            queryParams: { returnUrl: router.url, reason: 'expired' },
          });
        }
      }
      return throwError(() => error);
    }),
  );
};

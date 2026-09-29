import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject, InjectionToken } from '@angular/core';
import { throwError, timeout } from 'rxjs';

/** Upper bound for any backend call; ingestion answers in milliseconds, so this only trips on a hang. */
export const REQUEST_TIMEOUT_MS = new InjectionToken<number>('REQUEST_TIMEOUT_MS', {
  factory: () => 15_000,
});

/** Fails requests that get no response in time, as a problem-style 408 the error panel can show. */
export const timeoutInterceptor: HttpInterceptorFn = (req, next) => {
  const ms = inject(REQUEST_TIMEOUT_MS);
  return next(req).pipe(
    // `each`, not `first`: HttpClient emits HttpSentEvent immediately, which would satisfy `first`
    // at once; `each` bounds the wait between that event and the response.
    timeout({
      each: ms,
      with: () =>
        throwError(
          () =>
            new HttpErrorResponse({
              status: 408,
              statusText: 'Request Timeout',
              url: req.url,
              error: {
                title: 'Request timed out',
                detail: `The backend did not respond within ${Math.round(ms / 1000)} seconds.`,
              },
            }),
        ),
    }),
  );
};

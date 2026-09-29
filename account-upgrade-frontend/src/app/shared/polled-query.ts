import { Signal, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import {
  catchError,
  combineLatest,
  debounceTime,
  exhaustMap,
  map,
  Observable,
  of,
  startWith,
  Subject,
  switchMap,
  timer,
} from 'rxjs';
import { ApiError, toApiError } from '../core/api/api-error';

export interface PolledQuery<T> {
  readonly data: Signal<T | undefined>;
  readonly error: Signal<ApiError | null>;
  readonly lastUpdated: Signal<Date | null>;
  refresh(): void;
}

type Outcome<T> = { ok: true; value: T } | { ok: false; error: ApiError };

/**
 * Re-runs {@code fetch} whenever {@code params} change, on demand via {@code refresh()}, and every
 * {@code intervalMs} while {@code autoRefresh} is on. A slow response is never overlapped by the
 * next tick. Must be called in an injection context; it stops when that context is destroyed.
 */
export function polledQuery<P, T>(options: {
  params: Signal<P>;
  autoRefresh: Signal<boolean>;
  intervalMs: number;
  fetch: (params: P) => Observable<T>;
}): PolledQuery<T> {
  const data = signal<T | undefined>(undefined);
  const error = signal<ApiError | null>(null);
  const lastUpdated = signal<Date | null>(null);
  const refresh$ = new Subject<void>();

  combineLatest([
    toObservable(options.params).pipe(debounceTime(200)),
    toObservable(options.autoRefresh),
    refresh$.pipe(startWith(undefined)),
  ])
    .pipe(
      switchMap(([params, auto]) =>
        (auto ? timer(0, options.intervalMs) : of(0)).pipe(
          exhaustMap(() =>
            options.fetch(params).pipe(
              map((value): Outcome<T> => ({ ok: true, value })),
              catchError((err: unknown) => of<Outcome<T>>({ ok: false, error: toApiError(err) })),
            ),
          ),
        ),
      ),
      takeUntilDestroyed(),
    )
    .subscribe((outcome) => {
      if (outcome.ok) {
        data.set(outcome.value);
        error.set(null);
        lastUpdated.set(new Date());
      } else {
        error.set(outcome.error);
      }
    });

  return {
    data: data.asReadonly(),
    error: error.asReadonly(),
    lastUpdated: lastUpdated.asReadonly(),
    refresh: () => refresh$.next(),
  };
}

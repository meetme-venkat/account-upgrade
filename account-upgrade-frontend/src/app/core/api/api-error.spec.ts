import { HttpErrorResponse } from '@angular/common/http';
import { toApiError } from './api-error';

describe('toApiError', () => {
  it('uses the problem title and field errors from a validation failure', () => {
    const error = new HttpErrorResponse({
      status: 400,
      error: {
        status: 400,
        title: 'Validation failed',
        detail: 'Request validation failed',
        errors: ['[1].userId: userId is required'],
      },
    });

    expect(toApiError(error)).toEqual({
      status: 400,
      message: 'Validation failed',
      details: ['[1].userId: userId is required'],
    });
  });

  it('falls back to the problem detail when there is no errors list', () => {
    const error = new HttpErrorResponse({
      status: 503,
      error: {
        title: 'Event broker unavailable',
        detail: 'The request could not be queued, please retry later',
      },
    });

    expect(toApiError(error)).toEqual({
      status: 503,
      message: 'Event broker unavailable',
      details: ['The request could not be queued, please retry later'],
    });
  });

  it('explains a network failure', () => {
    expect(toApiError(new HttpErrorResponse({ status: 0 })).message).toBe('Backend unreachable');
  });

  it('handles a non-problem error body', () => {
    const result = toApiError(new HttpErrorResponse({ status: 500, error: 'boom' }));
    expect(result).toEqual({ status: 500, message: 'Request failed (HTTP 500)', details: [] });
  });
});

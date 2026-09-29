import { HttpErrorResponse } from '@angular/common/http';
import { ProblemDetail } from './models';

/** A backend or network failure, flattened for display. */
export interface ApiError {
  status: number;
  message: string;
  details: string[];
}

/** Converts any error thrown by HttpClient into an {@link ApiError}. */
export function toApiError(error: unknown): ApiError {
  if (!(error instanceof HttpErrorResponse)) {
    return { status: 0, message: 'Unexpected error', details: [String(error)] };
  }
  if (error.status === 0) {
    return {
      status: 0,
      message: 'Backend unreachable',
      details: ['Check that account-upgrade-backend is running and the API URL is correct.'],
    };
  }
  const problem = isProblemDetail(error.error) ? error.error : undefined;
  return {
    status: error.status,
    message: problem?.title ?? `Request failed (HTTP ${error.status})`,
    details: problem?.errors ?? (problem?.detail ? [problem.detail] : []),
  };
}

function isProblemDetail(body: unknown): body is ProblemDetail {
  return typeof body === 'object' && body !== null && ('title' in body || 'detail' in body);
}

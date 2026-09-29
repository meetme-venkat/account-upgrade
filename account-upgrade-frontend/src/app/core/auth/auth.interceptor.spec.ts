import {
  HttpClient,
  HttpErrorResponse,
  provideHttpClient,
  withInterceptors,
} from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { authInterceptor } from './auth.interceptor';
import { AuthService, LOGIN_PATH } from './auth.service';

describe('authInterceptor', () => {
  let http: HttpClient;
  let controller: HttpTestingController;
  const auth = {
    token: vi.fn<() => string | null>(),
    logout: vi.fn(),
    needsToken: (url: string) => url.startsWith('/api/') && url !== LOGIN_PATH,
  };
  const router = { url: '/processed', navigate: vi.fn().mockResolvedValue(true) };

  beforeEach(() => {
    auth.token.mockReset().mockReturnValue('jwt-1');
    auth.logout.mockReset();
    router.url = '/processed';
    router.navigate.mockClear();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: auth },
        { provide: Router, useValue: router },
      ],
    });
    http = TestBed.inject(HttpClient);
    controller = TestBed.inject(HttpTestingController);
  });

  afterEach(() => controller.verify());

  it('sends the token with API calls', () => {
    http.get('/api/processed-upgrades').subscribe();

    const request = controller.expectOne('/api/processed-upgrades');
    expect(request.request.headers.get('Authorization')).toBe('Bearer jwt-1');
    request.flush([]);
  });

  it('does not send a token with the login itself or with non-API calls', () => {
    http.post(LOGIN_PATH, {}).subscribe();
    http.get('/actuator/health').subscribe();

    expect(controller.expectOne(LOGIN_PATH).request.headers.has('Authorization')).toBe(false);
    expect(controller.expectOne('/actuator/health').request.headers.has('Authorization')).toBe(
      false,
    );
  });

  it('sends API calls without a token when logged out (the backend then answers 401)', () => {
    auth.token.mockReturnValue(null);
    http.get('/api/notifications').subscribe({ error: () => undefined });

    expect(controller.expectOne('/api/notifications').request.headers.has('Authorization')).toBe(
      false,
    );
  });

  it('ends the session on 401 and sends the user to the login page, returning afterwards', () => {
    let error: unknown;
    http.get('/api/processed-upgrades').subscribe({ error: (e: unknown) => (error = e) });

    controller
      .expectOne('/api/processed-upgrades')
      .flush({ title: 'Unauthorized' }, { status: 401, statusText: 'Unauthorized' });

    expect(auth.logout).toHaveBeenCalled();
    expect(router.navigate).toHaveBeenCalledWith(['/login'], {
      queryParams: { returnUrl: '/processed', reason: 'expired' },
    });
    expect((error as HttpErrorResponse).status).toBe(401);
  });

  it('does not redirect again when already on the login page', () => {
    router.url = '/login?returnUrl=%2Fprocessed';
    http.get('/api/processed-upgrades').subscribe({ error: () => undefined });

    controller
      .expectOne('/api/processed-upgrades')
      .flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(auth.logout).toHaveBeenCalled();
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('passes other errors through without ending the session', () => {
    let error: unknown;
    http.get('/api/processed-upgrades').subscribe({ error: (e: unknown) => (error = e) });

    controller
      .expectOne('/api/processed-upgrades')
      .flush(null, { status: 503, statusText: 'Service Unavailable' });

    expect((error as HttpErrorResponse).status).toBe(503);
    expect(auth.logout).not.toHaveBeenCalled();
    expect(router.navigate).not.toHaveBeenCalled();
  });
});

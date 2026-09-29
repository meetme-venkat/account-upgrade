import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { RUNTIME_CONFIG } from '../config/runtime-config';
import { AuthService, LOGIN_PATH, NOW, SESSION_STORAGE, Session } from './auth.service';

/** A minimal in-memory Storage. */
export function memoryStorage(initial: Record<string, string> = {}): Storage {
  const data = new Map(Object.entries(initial));
  return {
    get length() {
      return data.size;
    },
    clear: () => data.clear(),
    getItem: (key) => data.get(key) ?? null,
    key: (index) => [...data.keys()][index] ?? null,
    removeItem: (key) => void data.delete(key),
    setItem: (key, value) => void data.set(key, value),
  };
}

const KEY = 'account-upgrade.session';
const NOW_MS = Date.parse('2026-09-29T10:00:00Z');

describe('AuthService', () => {
  let storage: Storage;
  let now: number;

  function setup(
    saved?: string,
    apiBaseUrl = '',
  ): {
    auth: AuthService;
    http: HttpTestingController;
  } {
    storage = memoryStorage(saved === undefined ? {} : { [KEY]: saved });
    now = NOW_MS;
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: SESSION_STORAGE, useValue: storage },
        { provide: NOW, useValue: () => now },
        { provide: RUNTIME_CONFIG, useValue: { apiBaseUrl, pollIntervalMs: 3000 } },
      ],
    });
    return { auth: TestBed.inject(AuthService), http: TestBed.inject(HttpTestingController) };
  }

  const session = (overrides: Partial<Session> = {}): Session => ({
    username: 'admin',
    token: 'jwt-1',
    expiresAt: NOW_MS + 60_000,
    ...overrides,
  });

  it('logs in, keeps the token and stores the session for page reloads', () => {
    const { auth, http } = setup();
    let done = false;

    auth.login('admin', 'admin').subscribe(() => (done = true));
    const request = http.expectOne(LOGIN_PATH);
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ username: 'admin', password: 'admin' });
    request.flush({
      accessToken: 'jwt-1',
      tokenType: 'Bearer',
      expiresIn: 3600,
      expiresAt: '2026-09-29T11:00:00Z',
    });

    expect(done).toBe(true);
    expect(auth.token()).toBe('jwt-1');
    expect(auth.isAuthenticated()).toBe(true);
    expect(auth.loggedIn()).toBe(true);
    expect(auth.username()).toBe('admin');
    expect(JSON.parse(storage.getItem(KEY)!)).toEqual({
      username: 'admin',
      token: 'jwt-1',
      expiresAt: Date.parse('2026-09-29T11:00:00Z'),
    });
    http.verify();
  });

  it('sends the login to the configured backend', () => {
    const { auth, http } = setup(undefined, 'https://api.example.com');

    auth.login('admin', 'admin').subscribe();

    http.expectOne('https://api.example.com' + LOGIN_PATH);
  });

  it('restores a saved session', () => {
    const { auth } = setup(JSON.stringify(session()));

    expect(auth.token()).toBe('jwt-1');
    expect(auth.username()).toBe('admin');
  });

  it('ignores a corrupt or incomplete saved session', () => {
    expect(setup('{not json').auth.loggedIn()).toBe(false);
    TestBed.resetTestingModule();
    expect(setup(JSON.stringify({ username: 'admin' })).auth.loggedIn()).toBe(false);
  });

  it('treats an expired token as logged out and ends the session', () => {
    const { auth } = setup(JSON.stringify(session()));

    now = NOW_MS + 60_000;

    expect(auth.token()).toBeNull();
    expect(auth.loggedIn()).toBe(true);
    expect(auth.isAuthenticated()).toBe(false);
    expect(auth.loggedIn()).toBe(false);
    expect(storage.getItem(KEY)).toBeNull();
  });

  it('logs out', () => {
    const { auth } = setup(JSON.stringify(session()));

    auth.logout();

    expect(auth.token()).toBeNull();
    expect(auth.isAuthenticated()).toBe(false);
    expect(storage.getItem(KEY)).toBeNull();
  });

  it('knows which requests need the token', () => {
    const { auth } = setup();

    expect(auth.needsToken('/api/processed-upgrades')).toBe(true);
    expect(auth.needsToken('/api/realtime-upgrade')).toBe(true);
    expect(auth.needsToken(LOGIN_PATH)).toBe(false);
    expect(auth.needsToken('/actuator/health')).toBe(false);
    expect(auth.needsToken('https://elsewhere.example.com/api/x')).toBe(false);
  });

  it('works without session storage', () => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: SESSION_STORAGE, useValue: null },
      ],
    });
    const auth = TestBed.inject(AuthService);

    auth.login('admin', 'admin').subscribe();
    TestBed.inject(HttpTestingController).expectOne(LOGIN_PATH).flush({
      accessToken: 't',
      tokenType: 'Bearer',
      expiresIn: 60,
      expiresAt: '2999-01-01T00:00:00Z',
    });

    expect(auth.token()).toBe('t');
    auth.logout();
    expect(auth.token()).toBeNull();
  });
});

import { HttpClient } from '@angular/common/http';
import { computed, inject, Injectable, InjectionToken, signal } from '@angular/core';
import { map, Observable } from 'rxjs';
import { RUNTIME_CONFIG } from '../config/runtime-config';

/** Response of POST /api/auth/login. */
export interface LoginResponse {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
  expiresAt: string;
}

/** The logged-in user's access token and when it expires (epoch milliseconds). */
export interface Session {
  username: string;
  token: string;
  expiresAt: number;
}

export const LOGIN_PATH = '/api/auth/login';
const SESSION_KEY = 'account-upgrade.session';

/**
 * Where the session is kept: sessionStorage, so it survives a page reload but not closing the tab.
 * Null when storage is unavailable (e.g. blocked); the session then lasts until the page reloads.
 */
export const SESSION_STORAGE = new InjectionToken<Storage | null>('SESSION_STORAGE', {
  factory: () => {
    try {
      return globalThis.sessionStorage ?? null;
    } catch {
      return null;
    }
  },
});

/** Current time in epoch milliseconds; replaceable in tests. */
export const NOW = new InjectionToken<() => number>('NOW', { factory: () => () => Date.now() });

/** Logs in against the backend and holds the resulting access token for the API calls. */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = inject(RUNTIME_CONFIG).apiBaseUrl;
  private readonly storage = inject(SESSION_STORAGE);
  private readonly now = inject(NOW);
  private readonly session = signal<Session | null>(this.restore());

  /** Whether a session exists (for templates; expiry is checked by {@link isAuthenticated}). */
  readonly loggedIn = computed(() => this.session() !== null);
  readonly username = computed(() => this.session()?.username ?? null);

  /** POST /api/auth/login; on success the token is used for every following API call. */
  login(username: string, password: string): Observable<void> {
    return this.http.post<LoginResponse>(this.baseUrl + LOGIN_PATH, { username, password }).pipe(
      map((response) =>
        this.start({
          username,
          token: response.accessToken,
          expiresAt: Date.parse(response.expiresAt),
        }),
      ),
    );
  }

  logout(): void {
    this.session.set(null);
    this.storage?.removeItem(SESSION_KEY);
  }

  /** True while the session's token is unexpired; ends an expired session. */
  isAuthenticated(): boolean {
    if (this.token() !== null) {
      return true;
    }
    if (this.session() !== null) {
      this.logout();
    }
    return false;
  }

  /** The access token, or null when logged out or expired. */
  token(): string | null {
    const session = this.session();
    return session && session.expiresAt > this.now() ? session.token : null;
  }

  /** Requests to this backend's API, which need the token (all but the login itself). */
  needsToken(url: string): boolean {
    return url.startsWith(this.baseUrl + '/api/') && url !== this.baseUrl + LOGIN_PATH;
  }

  private start(session: Session): void {
    this.session.set(session);
    this.storage?.setItem(SESSION_KEY, JSON.stringify(session));
  }

  private restore(): Session | null {
    try {
      const saved = JSON.parse(
        this.storage?.getItem(SESSION_KEY) ?? 'null',
      ) as Partial<Session> | null;
      return saved &&
        typeof saved.username === 'string' &&
        typeof saved.token === 'string' &&
        typeof saved.expiresAt === 'number'
        ? (saved as Session)
        : null;
    } catch {
      return null;
    }
  }
}

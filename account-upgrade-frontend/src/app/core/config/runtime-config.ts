import { InjectionToken } from '@angular/core';

/**
 * Settings read at startup from {@code /config.json}, so one build can be deployed against any
 * backend. The Docker image writes that file from environment variables.
 */
export interface RuntimeConfig {
  /** Backend origin, e.g. {@code https://api.example.com}. Empty means same origin (reverse proxy). */
  apiBaseUrl: string;
  /** How often list pages refresh while auto-refresh is on. */
  pollIntervalMs: number;
}

export const DEFAULT_RUNTIME_CONFIG: RuntimeConfig = { apiBaseUrl: '', pollIntervalMs: 3000 };

export const RUNTIME_CONFIG = new InjectionToken<RuntimeConfig>('RUNTIME_CONFIG', {
  factory: () => DEFAULT_RUNTIME_CONFIG,
});

/** Loads {@code config.json}; falls back to defaults so the app still starts if it is missing. */
export async function loadRuntimeConfig(url = 'config.json'): Promise<RuntimeConfig> {
  try {
    const response = await fetch(url, { cache: 'no-store' });
    if (!response.ok) {
      return DEFAULT_RUNTIME_CONFIG;
    }
    const loaded = (await response.json()) as Partial<RuntimeConfig>;
    return {
      apiBaseUrl: (loaded.apiBaseUrl ?? DEFAULT_RUNTIME_CONFIG.apiBaseUrl).replace(/\/+$/, ''),
      pollIntervalMs: Number(loaded.pollIntervalMs) || DEFAULT_RUNTIME_CONFIG.pollIntervalMs,
    };
  } catch {
    return DEFAULT_RUNTIME_CONFIG;
  }
}

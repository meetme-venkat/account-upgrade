import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { routes } from './app.routes';
import { authInterceptor } from './core/auth/auth.interceptor';
import { RUNTIME_CONFIG, RuntimeConfig } from './core/config/runtime-config';
import { timeoutInterceptor } from './core/http/timeout.interceptor';

export function appConfig(runtimeConfig: RuntimeConfig): ApplicationConfig {
  return {
    providers: [
      provideBrowserGlobalErrorListeners(),
      provideRouter(routes, withComponentInputBinding()),
      provideHttpClient(withFetch(), withInterceptors([authInterceptor, timeoutInterceptor])),
      { provide: RUNTIME_CONFIG, useValue: runtimeConfig },
    ],
  };
}

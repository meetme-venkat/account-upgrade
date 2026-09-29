import { Routes } from '@angular/router';
import { authGuard, guestGuard } from './core/auth/auth.guard';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'submit' },
  {
    path: 'login',
    title: 'Sign in | Account Upgrades',
    canActivate: [guestGuard],
    loadComponent: () => import('./features/login/login-page').then((m) => m.LoginPage),
  },
  {
    path: 'submit',
    canActivate: [authGuard],
    title: 'Submit | Account Upgrades',
    loadComponent: () => import('./features/submit/submit-page').then((m) => m.SubmitPage),
  },
  {
    path: 'processed',
    canActivate: [authGuard],
    title: 'Processed | Account Upgrades',
    loadComponent: () =>
      import('./features/processed/processed-upgrades-page').then((m) => m.ProcessedUpgradesPage),
  },
  {
    path: 'notifications',
    canActivate: [authGuard],
    title: 'Notifications | Account Upgrades',
    loadComponent: () =>
      import('./features/notifications/notifications-page').then((m) => m.NotificationsPage),
  },
  { path: '**', redirectTo: 'submit' },
];

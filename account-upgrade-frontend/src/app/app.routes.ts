import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'submit' },
  {
    path: 'submit',
    title: 'Submit | Account Upgrades',
    loadComponent: () => import('./features/submit/submit-page').then((m) => m.SubmitPage),
  },
  {
    path: 'processed',
    title: 'Processed | Account Upgrades',
    loadComponent: () =>
      import('./features/processed/processed-upgrades-page').then((m) => m.ProcessedUpgradesPage),
  },
  {
    path: 'notifications',
    title: 'Notifications | Account Upgrades',
    loadComponent: () =>
      import('./features/notifications/notifications-page').then((m) => m.NotificationsPage),
  },
  { path: '**', redirectTo: 'submit' },
];

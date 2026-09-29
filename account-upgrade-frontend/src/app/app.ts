import { Component, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { catchError, of, switchMap, timer } from 'rxjs';
import { UpgradeApiService } from './core/api/upgrade-api.service';
import { AuthService } from './core/auth/auth.service';

const HEALTH_CHECK_MS = 10_000;

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {
  private readonly api = inject(UpgradeApiService);
  private readonly router = inject(Router);
  protected readonly auth = inject(AuthService);

  /** Backend health: undefined while the first check runs. */
  protected readonly backendUp = toSignal(
    timer(0, HEALTH_CHECK_MS).pipe(
      switchMap(() => this.api.health().pipe(catchError(() => of(false)))),
    ),
  );

  logout(): void {
    this.auth.logout();
    void this.router.navigate(['/login']);
  }
}

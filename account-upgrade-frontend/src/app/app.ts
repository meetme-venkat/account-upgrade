import { Component, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { catchError, of, switchMap, timer } from 'rxjs';
import { UpgradeApiService } from './core/api/upgrade-api.service';

const HEALTH_CHECK_MS = 10_000;

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {
  private readonly api = inject(UpgradeApiService);

  /** Backend health: undefined while the first check runs. */
  protected readonly backendUp = toSignal(
    timer(0, HEALTH_CHECK_MS).pipe(
      switchMap(() => this.api.health().pipe(catchError(() => of(false)))),
    ),
  );
}

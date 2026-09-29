import { Component, inject, input, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { finalize } from 'rxjs';
import { ApiError, toApiError } from '../../core/api/api-error';
import { AuthService } from '../../core/auth/auth.service';
import { ErrorPanel } from '../../shared/error-panel';

const DEFAULT_PAGE = '/submit';

/** Signs in with the administrator's credentials; the backend returns the access token. */
@Component({
  selector: 'app-login-page',
  imports: [ReactiveFormsModule, ErrorPanel],
  template: `
    <section class="card login">
      <h1>Sign in</h1>
      <p class="muted">Sign in to submit and review account upgrade requests.</p>

      @if (reason() === 'expired') {
        <div class="alert alert-info" role="status">Your session has ended. Sign in again.</div>
      }

      <form [formGroup]="form" (ngSubmit)="submit()" novalidate>
        <label>
          Username
          <input formControlName="username" autocomplete="username" autofocus />
          @if (form.controls.username.touched && form.controls.username.invalid) {
            <span class="field-error">Username is required</span>
          }
        </label>
        <label>
          Password
          <input type="password" formControlName="password" autocomplete="current-password" />
          @if (form.controls.password.touched && form.controls.password.invalid) {
            <span class="field-error">Password is required</span>
          }
        </label>

        <div class="actions">
          <button type="submit" [disabled]="submitting()">
            {{ submitting() ? 'Signing in...' : 'Sign in' }}
          </button>
        </div>
      </form>

      <app-error-panel [error]="error()" />
    </section>
  `,
  styles: `
    .login {
      max-width: 380px;
      margin: 48px auto 0;
    }

    form {
      display: grid;
      gap: 12px;
      margin-top: 16px;
    }

    label {
      display: grid;
      gap: 4px;
    }
  `,
})
export class LoginPage {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  /** Query parameters: where to go after signing in, and why the user was sent here. */
  readonly returnUrl = input<string>();
  readonly reason = input<string>();

  protected readonly form = new FormGroup({
    username: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
    password: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
  });
  protected readonly submitting = signal(false);
  protected readonly error = signal<ApiError | null>(null);

  submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const { username, password } = this.form.getRawValue();
    this.submitting.set(true);
    this.error.set(null);
    this.auth
      .login(username.trim(), password)
      .pipe(finalize(() => this.submitting.set(false)))
      .subscribe({
        next: () => void this.router.navigateByUrl(safeReturnUrl(this.returnUrl())),
        error: (err: unknown) => {
          this.form.controls.password.reset();
          const apiError = toApiError(err);
          this.error.set(
            apiError.status === 401
              ? { ...apiError, message: 'Invalid username or password', details: [] }
              : apiError,
          );
        },
      });
  }
}

/** Only paths inside this app: never another site ('//evil.example') or back to the login page. */
export function safeReturnUrl(url: string | undefined): string {
  return url && url.startsWith('/') && !url.startsWith('//') && !url.startsWith('/login')
    ? url
    : DEFAULT_PAGE;
}

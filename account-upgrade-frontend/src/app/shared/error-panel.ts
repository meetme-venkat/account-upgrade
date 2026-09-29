import { Component, input } from '@angular/core';
import { ApiError } from '../core/api/api-error';

@Component({
  selector: 'app-error-panel',
  template: `
    @if (error(); as error) {
      <div class="alert alert-error" role="alert">
        <strong>{{ error.message }}</strong>
        @if (error.details.length) {
          <ul>
            @for (detail of error.details; track $index) {
              <li>{{ detail }}</li>
            }
          </ul>
        }
      </div>
    }
  `,
})
export class ErrorPanel {
  readonly error = input<ApiError | null>(null);
}

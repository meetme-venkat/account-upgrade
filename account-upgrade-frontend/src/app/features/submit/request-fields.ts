import { Component, input } from '@angular/core';
import { ReactiveFormsModule } from '@angular/forms';
import { UpgradeRequestForm } from '../../shared/upgrade-request-form';

/** The five request inputs, laid out as a form grid. */
@Component({
  selector: 'app-request-fields',
  imports: [ReactiveFormsModule],
  template: `
    <div class="form-grid" [formGroup]="form()">
      <label>
        User ID <span class="required">*</span>
        <input formControlName="userId" placeholder="u200" autocomplete="off" />
        @if (form().controls.userId.touched && form().controls.userId.invalid) {
          <span class="field-error">User ID is required</span>
        }
      </label>
      <label>
        User name
        <input formControlName="userName" placeholder="Dana" autocomplete="off" />
      </label>
      <label>
        Age
        <input type="number" formControlName="age" placeholder="21" />
        @if (form().controls.age.invalid) {
          <span class="field-error">Age must be a whole number</span>
        }
      </label>
      <label>
        Balance ($)
        <input type="number" step="0.01" formControlName="balance" placeholder="45.00" />
      </label>
      <label class="span-2">
        Parent email <span class="muted">(optional)</span>
        <input type="email" formControlName="parentEmail" placeholder="parent@example.com" />
        @if (form().controls.parentEmail.invalid) {
          <span class="field-error">Enter a valid email address</span>
        }
      </label>
    </div>
  `,
})
export class RequestFields {
  readonly form = input.required<UpgradeRequestForm>();
}

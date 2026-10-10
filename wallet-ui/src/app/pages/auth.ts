import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { Api, errorMessage } from '../api';

@Component({
  selector: 'app-auth',
  imports: [FormsModule],
  template: `
    <div class="auth">
      <div class="brand big">Pay<span>Mesh</span></div>
      <p class="muted">Pay anyone, recharge, pay bills. Instantly.</p>

      <div class="card">
        <div class="tabs">
          <button type="button" [class.active]="mode() === 'login'" (click)="switch('login')">Login</button>
          <button type="button" [class.active]="mode() === 'register'" (click)="switch('register')">Create account</button>
        </div>

        @if (mode() === 'login') {
          <form #f="ngForm" (ngSubmit)="f.valid && submit()">
            <label>Mobile number or email
              <input name="username" [(ngModel)]="username" required autocomplete="username" />
            </label>
            <label>Password
              <input name="password" type="password" [(ngModel)]="password" required autocomplete="current-password" />
            </label>
            @if (error()) { <p class="error">{{ error() }}</p> }
            <button class="primary" [disabled]="busy() || f.invalid">{{ busy() ? 'Signing in…' : 'Login' }}</button>
          </form>
        } @else {
          <form #f="ngForm" (ngSubmit)="f.valid && submit()">
            <label>Full name
              <input name="fullName" [(ngModel)]="reg.fullName" required maxlength="100" autocomplete="name" />
            </label>
            <label>Mobile number
              <input name="mobileNo" [(ngModel)]="reg.mobileNo" required pattern="[6-9][0-9]{9}" inputmode="numeric"
                     maxlength="10" placeholder="10 digit mobile" autocomplete="tel-national" #m="ngModel" />
              @if (m.touched && m.invalid) { <small class="error">Enter a valid 10 digit mobile number</small> }
            </label>
            <label>Email
              <input name="emailId" type="email" [(ngModel)]="reg.emailId" required email autocomplete="email" #e="ngModel" />
              @if (e.touched && e.invalid) { <small class="error">Enter a valid email</small> }
            </label>
            <label>Password
              <input name="password" type="password" [(ngModel)]="reg.password" required minlength="8" maxlength="64"
                     autocomplete="new-password" #p="ngModel" />
              @if (p.touched && p.invalid) { <small class="error">At least 8 characters</small> }
            </label>
            <div class="row">
              <label>Address <small class="muted">(optional)</small>
                <input name="address" [(ngModel)]="reg.address" maxlength="255" autocomplete="street-address" />
              </label>
              <label>PAN <small class="muted">(optional)</small>
                <input name="pan" [(ngModel)]="reg.pan" pattern="[A-Z]{5}[0-9]{4}[A-Z]" maxlength="10"
                       placeholder="ABCDE1234F" (ngModelChange)="reg.pan = $event.toUpperCase()" />
              </label>
            </div>
            @if (error()) { <p class="error">{{ error() }}</p> }
            <button class="primary" [disabled]="busy() || f.invalid">{{ busy() ? 'Creating…' : 'Create account' }}</button>
          </form>
        }
      </div>
    </div>
  `,
})
export class AuthPage {
  private api = inject(Api);
  private router = inject(Router);

  mode = signal<'login' | 'register'>('login');
  busy = signal(false);
  error = signal('');

  username = '';
  password = '';
  reg = { fullName: '', mobileNo: '', emailId: '', password: '', address: '', pan: '' };

  switch(mode: 'login' | 'register') {
    this.mode.set(mode);
    this.error.set('');
  }

  async submit() {
    this.busy.set(true);
    this.error.set('');
    try {
      if (this.mode() === 'login') {
        await this.api.login(this.username, this.password);
      } else {
        await this.api.register({ ...this.reg, address: this.reg.address || null, pan: this.reg.pan || null });
      }
      this.router.navigate(['/']);
    } catch (e) {
      this.error.set(errorMessage(e));
    } finally {
      this.busy.set(false);
    }
  }
}

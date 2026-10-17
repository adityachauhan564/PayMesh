import { Component, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Api, errorMessage } from '../api';

/**
 * Where the lockout email's link lands. Unlocking needs the owner's click: mail security scanners open links, and
 * some run the page's JavaScript, so neither opening the link nor loading this page may unlock the account.
 */
@Component({
  selector: 'app-unlock',
  imports: [RouterLink],
  template: `
    <div class="auth">
      <div class="brand big">Pay<span>Mesh</span></div>
      <div class="card">
        @if (!token) {
          <p class="error">This unlock link is incomplete.</p>
          <a routerLink="/login">Back to login</a>
        } @else {
          <p class="muted">Your account was locked after too many failed logins. Unlock it if that was you.</p>
          @if (error()) { <p class="error">{{ error() }}</p> }
          <button class="primary" type="button" [disabled]="busy()" (click)="unlock()">
            {{ busy() ? 'Unlocking…' : 'Unlock my account' }}
          </button>
        }
      </div>
    </div>
  `,
})
export class UnlockPage {
  private api = inject(Api);
  private router = inject(Router);

  readonly token = inject(ActivatedRoute).snapshot.queryParamMap.get('token');
  busy = signal(false);
  error = signal('');

  async unlock() {
    this.busy.set(true);
    this.error.set('');
    try {
      await this.api.unlock(this.token!);
      this.router.navigate(['/login'], { queryParams: { unlocked: 1 } });
    } catch (e) {
      this.error.set(errorMessage(e));
    } finally {
      this.busy.set(false);
    }
  }
}

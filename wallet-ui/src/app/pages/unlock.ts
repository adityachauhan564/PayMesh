import { Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Api, errorMessage } from '../api';

/**
 * Where the lockout email's link lands. Unlocking takes a POST from this page, so mail scanners that open links
 * to check them can't unlock anyone.
 */
@Component({
  selector: 'app-unlock',
  imports: [RouterLink],
  template: `
    <div class="auth">
      <div class="brand big">Pay<span>Mesh</span></div>
      <div class="card">
        @if (error()) {
          <p class="error">{{ error() }}</p>
          <a routerLink="/login">Back to login</a>
        } @else {
          <p class="muted">Unlocking your account…</p>
        }
      </div>
    </div>
  `,
})
export class UnlockPage implements OnInit {
  private api = inject(Api);
  private router = inject(Router);
  private route = inject(ActivatedRoute);

  error = signal('');

  async ngOnInit() {
    const token = this.route.snapshot.queryParamMap.get('token');
    if (!token) {
      this.error.set('This unlock link is incomplete.');
      return;
    }
    try {
      await this.api.unlock(token);
      this.router.navigate(['/login'], { queryParams: { unlocked: 1 } });
    } catch (e) {
      this.error.set(errorMessage(e));
    }
  }
}

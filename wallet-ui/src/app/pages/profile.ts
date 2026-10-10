import { Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api, errorMessage } from '../api';

@Component({
  selector: 'app-profile',
  imports: [FormsModule],
  template: `
    @let user = api.user()!;
    <section class="card">
      <h2>Profile</h2>
      <p class="muted">Mobile {{ user.mobileNo }}@if (user.pan) { · PAN {{ user.pan }} }</p>
      <form #pf="ngForm" (ngSubmit)="pf.valid && saveProfile()">
        <label>Full name <input name="fullName" [(ngModel)]="profile.fullName" required maxlength="100" /></label>
        <label>Email <input name="emailId" type="email" [(ngModel)]="profile.emailId" required email /></label>
        <label>Address <input name="address" [(ngModel)]="profile.address" maxlength="255" /></label>
        @if (profileMsg()) { <p [class]="profileOk() ? 'ok' : 'error'">{{ profileMsg() }}</p> }
        <button class="primary" [disabled]="pf.invalid">Save profile</button>
      </form>
    </section>

    <section class="card">
      <h2>Wallet limits</h2>
      @if (limitsLoaded()) {
      <form #lf="ngForm" (ngSubmit)="lf.valid && saveLimits()">
        <div class="row">
          <label>Daily spend limit (₹)
            <input name="dailyLimit" type="number" [(ngModel)]="limits.dailyLimit" required min="100" max="100000" />
          </label>
          <label>Payments per day
            <input name="dailyTransactionLimit" type="number" [(ngModel)]="limits.dailyTransactionLimit" required min="1" max="50" />
          </label>
        </div>
        @if (limitsMsg()) { <p [class]="limitsOk() ? 'ok' : 'error'">{{ limitsMsg() }}</p> }
        <button class="primary" [disabled]="lf.invalid">Save limits</button>
      </form>
      } @else { <p class="muted">{{ limitsMsg() || 'Loading…' }}</p> }
    </section>

    <section class="card">
      <button type="button" (click)="api.logout()">Log out</button>
      <button type="button" class="danger" (click)="deleteAccount()">Delete account</button>
    </section>
  `,
})
export class ProfilePage implements OnInit {
  protected api = inject(Api);

  profile = { fullName: '', emailId: '', address: '' };
  limits = { dailyLimit: 10000, dailyTransactionLimit: 10 };
  profileMsg = signal('');
  profileOk = signal(false);
  limitsMsg = signal('');
  limitsOk = signal(false);
  limitsLoaded = signal(false);

  ngOnInit() {
    const u = this.api.user()!;
    this.profile = { fullName: u.fullName, emailId: u.emailId, address: u.address ?? '' };
    this.api.wallet().then((w) => {
      this.limits = { dailyLimit: w.dailyLimit, dailyTransactionLimit: w.dailyTransactionLimit };
      this.limitsLoaded.set(true);
    }).catch((e) => this.limitsMsg.set(errorMessage(e)));
  }

  async saveProfile() {
    try {
      await this.api.updateMe(this.profile);
      this.profileOk.set(true);
      this.profileMsg.set('Profile updated');
    } catch (e) {
      this.profileOk.set(false);
      this.profileMsg.set(errorMessage(e));
    }
  }

  async saveLimits() {
    try {
      await this.api.updateLimits(this.limits.dailyLimit, this.limits.dailyTransactionLimit);
      this.limitsOk.set(true);
      this.limitsMsg.set('Limits updated');
    } catch (e) {
      this.limitsOk.set(false);
      this.limitsMsg.set(errorMessage(e));
    }
  }

  async deleteAccount() {
    const w = await this.api.wallet().catch(() => null);
    if (w && w.balance > 0) {
      alert(`Your wallet still has ₹${w.balance}. Withdraw it before deleting your account.`);
      return;
    }
    if (confirm('Delete your account permanently? This cannot be undone.')) {
      await this.api.deleteMe();
    }
  }
}

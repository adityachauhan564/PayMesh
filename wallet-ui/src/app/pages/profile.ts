import QRCode from 'qrcode';
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
      <h3>Two-factor login</h3>
      @if (recoveryCodes().length) {
        <p class="ok">Two-factor login is on. Other devices were signed out.</p>
        <p class="muted">Save these recovery codes somewhere safe. Each works once if you lose your phone; they won't be shown again.</p>
        <pre class="codes">{{ recoveryCodes().join('
') }}</pre>
      } @else if (twoFactorOn()) {
        <p class="muted">On. Payments over Rs. 5,000 ask for a code.</p>
        <form #df="ngForm" (ngSubmit)="df.valid && disable2fa()">
          <div class="row">
            <label>Password <input name="pw" type="password" [(ngModel)]="tfPassword" required autocomplete="current-password" /></label>
            <label>Code <input name="code" [(ngModel)]="tfCode" required autocomplete="one-time-code" maxlength="19" /></label>
          </div>
          <button type="submit" [disabled]="df.invalid">Turn off</button>
        </form>
      } @else if (setup()) {
        <p class="muted">Scan this with Google Authenticator, Authy or 1Password, then enter the 6-digit code it shows.</p>
        @if (qr()) { <img class="qr" [src]="qr()" alt="QR code for your authenticator app" width="200" height="200" /> }
        <p class="muted small">Can't scan? Enter this key instead: <code>{{ setup()!.secret }}</code></p>
        <form #ef="ngForm" (ngSubmit)="ef.valid && enable2fa()">
          <label>Code <input name="code" [(ngModel)]="tfCode" required autocomplete="one-time-code" inputmode="numeric" maxlength="6" /></label>
          <button class="primary" [disabled]="ef.invalid">Turn on</button>
        </form>
      } @else {
        <p class="muted">Ask for a code from your phone at login and for large payments.</p>
        <form #sf="ngForm" (ngSubmit)="sf.valid && setup2fa()">
          <label>Password <input name="pw" type="password" [(ngModel)]="tfPassword" required autocomplete="current-password" /></label>
          <button type="submit" [disabled]="sf.invalid">Set up</button>
        </form>
      }
      @if (tfError()) { <p class="error">{{ tfError() }}</p> }
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

  twoFactorOn = signal(false);
  setup = signal<{ secret: string; otpauthUri: string } | null>(null);
  qr = signal('');
  recoveryCodes = signal<string[]>([]);
  tfError = signal('');
  tfPassword = '';
  tfCode = '';

  ngOnInit() {
    const u = this.api.user()!;
    this.twoFactorOn.set(!!u.twoFactorEnabled);
    this.profile = { fullName: u.fullName, emailId: u.emailId, address: u.address ?? '' };
    this.api.wallet().then((w) => {
      this.limits = { dailyLimit: w.dailyLimit, dailyTransactionLimit: w.dailyTransactionLimit };
      this.limitsLoaded.set(true);
    }).catch((e) => this.limitsMsg.set(errorMessage(e)));
  }

  async setup2fa() {
    await this.twoFactor(async () => {
      const setup = await this.api.setup2fa(this.tfPassword);
      this.qr.set(await QRCode.toDataURL(setup.otpauthUri, { width: 200, margin: 1 }));
      this.setup.set(setup);
    });
  }

  async enable2fa() {
    await this.twoFactor(async () => {
      this.recoveryCodes.set((await this.api.enable2fa(this.tfCode)).recoveryCodes);
      this.twoFactorOn.set(true);
      this.setup.set(null);
    });
  }

  async disable2fa() {
    await this.twoFactor(async () => {
      await this.api.disable2fa(this.tfPassword, this.tfCode);
      this.twoFactorOn.set(false);
    });
  }

  private async twoFactor(action: () => Promise<void>) {
    this.tfError.set('');
    try {
      await action();
      this.tfPassword = '';
      this.tfCode = '';
    } catch (e) {
      this.tfError.set(errorMessage(e));
    }
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

import { Component, inject, OnInit, signal } from '@angular/core';
import { CurrencyPipe, KeyValuePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { Api, BILL_CATEGORIES, Txn, TxnType, errorMessage, isProblem, sleep } from '../api';

const TABS: { type: TxnType; label: string }[] = [
  { type: 'TRANSFER', label: 'Send' },
  { type: 'ADD_MONEY', label: 'Add money' },
  { type: 'BILL_PAYMENT', label: 'Recharge & bills' },
  { type: 'WITHDRAW', label: 'Withdraw' },
];

@Component({
  selector: 'app-pay',
  imports: [FormsModule, CurrencyPipe, KeyValuePipe, RouterLink],
  template: `
    <section class="card">
      @if (result(); as r) {
        <div class="result" [class]="r.status">
          <div class="big-icon">{{ r.status === 'SUCCESSFUL' ? '✓' : r.status === 'REJECTED' ? '✕' : '' }}</div>
          @if (r.status === 'PENDING') { <div class="spinner"></div> }
          <h2>{{ r.amount | currency: 'INR' }}</h2>
          <p>
            @switch (r.status) {
              @case ('SUCCESSFUL') { Payment successful }
              @case ('REJECTED') { Payment failed: {{ r.failureReason }} }
              @default { Processing your payment… }
            }
          </p>
          <small class="muted">Transaction ID: {{ r.transactionId }}</small>
          @if (r.status !== 'PENDING') {
            <div class="row">
              <button type="button" (click)="reset()">Make another payment</button>
              <a class="button primary" routerLink="/">Done</a>
            </div>
          }
        </div>
      } @else {
        <div class="tabs">
          @for (t of tabs; track t.type) {
            <button type="button" [class.active]="type() === t.type" (click)="select(t.type)">{{ t.label }}</button>
          }
        </div>
        @if (balance() !== null) {
          <p class="muted">Wallet balance: <strong>{{ balance() | currency: 'INR' }}</strong></p>
        }

        <form #f="ngForm" (ngSubmit)="f.valid && pay()">
          @switch (type()) {
            @case ('TRANSFER') {
              <label>Receiver's mobile number
                <div class="row tight">
                  <input name="mobile" [(ngModel)]="mobile" required pattern="[6-9][0-9]{9}" maxlength="10" inputmode="numeric"
                         (ngModelChange)="receiver.set(null)" placeholder="10 digit mobile" />
                  <button type="button" (click)="find()" [disabled]="!mobile">Find</button>
                </div>
              </label>
              @if (receiver(); as r) {
                <p class="receiver">Paying <strong>{{ r.fullName }}</strong></p>
              }
            }
            @case ('BILL_PAYMENT') {
              <label>Category
                <select name="billCategory" [(ngModel)]="billCategory" required>
                  @for (c of categories | keyvalue; track c.key) { <option [value]="c.key">{{ c.value }}</option> }
                </select>
              </label>
              <label>{{ billCategory === 'MOBILE_RECHARGE' ? 'Mobile number' : 'Consumer / account number' }}
                <input name="billAccount" [(ngModel)]="billAccount" required maxlength="30" />
              </label>
            }
          }
          <label>Amount (₹)
            <input name="amount" type="number" [(ngModel)]="amount" required min="1" max="100000" step="0.01" inputmode="decimal" />
          </label>
          @if (type() === 'TRANSFER') {
            <label>Remark <small class="muted">(optional)</small>
              <input name="remark" [(ngModel)]="remark" maxlength="100" placeholder="e.g. Dinner" />
            </label>
          }
          @if (type() === 'ADD_MONEY' || type() === 'WITHDRAW') {
            <p class="muted small">Bank transfer is simulated in this demo.</p>
          }
          @if (error()) { <p class="error">{{ error() }}</p> }
          @if (stepUpNeeded()) {
            <label>Authenticator code
              <input name="stepUpCode" [(ngModel)]="stepUpCode" required autocomplete="one-time-code" maxlength="19" />
            </label>
          }
          <button class="primary" [disabled]="busy() || f.invalid || (type() === 'TRANSFER' && !receiver())">
            {{ busy() ? 'Please wait…' : submitLabel() }}
          </button>
        </form>
      }
    </section>
  `,
})
export class PayPage implements OnInit {
  private api = inject(Api);
  private route = inject(ActivatedRoute);

  tabs = TABS;
  categories = BILL_CATEGORIES;

  type = signal<TxnType>('TRANSFER');
  balance = signal<number | null>(null);
  receiver = signal<{ userId: number; fullName: string } | null>(null);
  result = signal<Txn | null>(null);
  busy = signal(false);
  error = signal('');

  mobile = '';
  amount: number | null = null;
  remark = '';
  billCategory = 'MOBILE_RECHARGE';
  billAccount = '';

  ngOnInit() {
    const t = this.route.snapshot.queryParamMap.get('type') as TxnType | null;
    if (t && TABS.some((x) => x.type === t)) this.type.set(t);
    this.refreshBalance();
  }

  submitLabel() {
    return { TRANSFER: 'Pay', ADD_MONEY: 'Add money', BILL_PAYMENT: 'Pay bill', WITHDRAW: 'Withdraw' }[this.type()];
  }

  select(t: TxnType) {
    this.type.set(t);
    this.error.set('');
  }

  async find() {
    this.error.set('');
    try {
      const r = await this.api.lookup(this.mobile);
      if (r.userId === this.api.user()?.userId) throw new Error('That is your own number');
      this.receiver.set(r);
    } catch (e) {
      this.error.set(e instanceof Error ? e.message : errorMessage(e));
    }
  }

  /**
   * One key per payment attempt. Kept when the request may have reached the server (network error, 5xx), so
   * pressing Pay again replays instead of paying twice; renewed once the server has answered.
   */
  private idempotencyKey = crypto.randomUUID();

  /** A large payment asked for the authenticator code (step-up); the retry keeps the same idempotency key. */
  stepUpNeeded = signal(false);
  stepUpCode = '';

  async pay() {
    this.busy.set(true);
    this.error.set('');
    try {
      if (this.stepUpNeeded()) {
        await this.api.stepUp(this.stepUpCode);
        this.stepUpNeeded.set(false);
        this.stepUpCode = '';
      }
      const body: Record<string, unknown> = { type: this.type(), amount: this.amount };
      if (this.type() === 'TRANSFER') Object.assign(body, { receiverId: this.receiver()!.userId, remark: this.remark || null });
      if (this.type() === 'BILL_PAYMENT') Object.assign(body, { billCategory: this.billCategory, billAccount: this.billAccount });

      let txn: Txn;
      try {
        txn = await this.api.createTxn(body, this.idempotencyKey);
        this.idempotencyKey = crypto.randomUUID();
      } catch (e) {
        if (isProblem(e, 'step-up-required')) {
          this.stepUpNeeded.set(true); // same attempt once the code is in: keep the key
        } else if (e instanceof HttpErrorResponse && e.status >= 400 && e.status < 500) {
          this.idempotencyKey = crypto.randomUUID();
        }
        throw e;
      }
      this.result.set(txn);
      // The wallet applies the payment asynchronously (Kafka); poll until it settles.
      for (let i = 0; i < 30 && txn.status === 'PENDING'; i++) {
        await sleep(1000);
        txn = await this.api.txn(txn.transactionId);
        this.result.set(txn);
      }
      this.refreshBalance();
    } catch (e) {
      this.error.set(errorMessage(e));
    } finally {
      this.busy.set(false);
    }
  }

  reset() {
    this.result.set(null);
    this.receiver.set(null);
    this.mobile = this.remark = this.billAccount = '';
    this.amount = null;
  }

  private refreshBalance() {
    this.api.wallet().then((w) => this.balance.set(w.balance)).catch(() => {});
  }
}

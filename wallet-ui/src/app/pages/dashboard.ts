import { Component, inject, OnInit, signal } from '@angular/core';
import { CurrencyPipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { RouterLink } from '@angular/router';
import { Api, Txn, Wallet, errorMessage, sleep } from '../api';
import { TxnList } from './txn-list';

@Component({
  selector: 'app-dashboard',
  imports: [CurrencyPipe, RouterLink, TxnList],
  template: `
    <section class="balance-card">
      <small>Wallet balance</small>
      @if (wallet(); as w) {
        <div class="balance">{{ w.balance | currency: 'INR' }}</div>
        <div class="usage">
          <div class="bar"><span [style.width.%]="(100 * w.spentToday) / w.dailyLimit"></span></div>
          <small>Spent today {{ w.spentToday | currency: 'INR' }} of {{ w.dailyLimit | currency: 'INR' : 'symbol' : '1.0-0' }}
            · {{ w.transactionsToday }}/{{ w.dailyTransactionLimit }} payments</small>
        </div>
      } @else if (error()) {
        <p>{{ error() }}</p>
      } @else {
        <div class="balance">…</div>
      }
    </section>

    <section class="actions">
      <a routerLink="/pay" [queryParams]="{ type: 'TRANSFER' }"><span>↗</span>Send money</a>
      <a routerLink="/pay" [queryParams]="{ type: 'ADD_MONEY' }"><span>+</span>Add money</a>
      <a routerLink="/pay" [queryParams]="{ type: 'BILL_PAYMENT' }"><span>⚡</span>Recharge &amp; bills</a>
      <a routerLink="/pay" [queryParams]="{ type: 'WITHDRAW' }"><span>↓</span>Withdraw</a>
    </section>

    <section class="card">
      <div class="card-head">
        <h2>Recent activity</h2>
        <a routerLink="/history">See all</a>
      </div>
      <app-txn-list [txns]="recent()" [meId]="api.user()!.userId" />
    </section>
  `,
})
export class DashboardPage implements OnInit {
  protected api = inject(Api);
  wallet = signal<Wallet | null>(null);
  recent = signal<Txn[]>([]);
  error = signal('');

  async ngOnInit() {
    this.api.history(0, 5).then((t) => this.recent.set(t)).catch(() => {});
    // A brand-new user's wallet is created asynchronously via Kafka; wait for it.
    for (let attempt = 0; attempt < 15; attempt++) {
      try {
        this.wallet.set(await this.api.wallet());
        this.error.set('');
        return;
      } catch (e) {
        this.error.set(errorMessage(e));
        if (!(e instanceof HttpErrorResponse && e.status === 404)) return;
        await sleep(2000);
      }
    }
  }
}

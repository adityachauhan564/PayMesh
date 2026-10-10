import { Component, inject, OnInit, signal } from '@angular/core';
import { Api, Txn, errorMessage } from '../api';
import { TxnList } from './txn-list';

const PAGE_SIZE = 20;

@Component({
  selector: 'app-history',
  imports: [TxnList],
  template: `
    <section class="card">
      <h2>Transaction history</h2>
      <app-txn-list [txns]="txns()" [meId]="api.user()!.userId" />
      @if (error()) { <p class="error">{{ error() }}</p> }
      @if (hasMore()) {
        <button type="button" class="more" (click)="load()" [disabled]="busy()">{{ busy() ? 'Loading…' : 'Load more' }}</button>
      }
    </section>
  `,
})
export class HistoryPage implements OnInit {
  protected api = inject(Api);
  txns = signal<Txn[]>([]);
  hasMore = signal(false);
  busy = signal(false);
  error = signal('');
  private page = 0;

  ngOnInit() {
    this.load();
  }

  async load() {
    this.busy.set(true);
    try {
      const batch = await this.api.history(this.page++, PAGE_SIZE);
      this.txns.update((t) => [...t, ...batch]);
      this.hasMore.set(batch.length === PAGE_SIZE);
    } catch (e) {
      this.error.set(errorMessage(e));
    } finally {
      this.busy.set(false);
    }
  }
}

import { Component, input, signal } from '@angular/core';
import { CurrencyPipe, DatePipe } from '@angular/common';
import { BILL_CATEGORIES, Txn } from '../api';

@Component({
  selector: 'app-txn-list',
  imports: [CurrencyPipe, DatePipe],
  template: `
    @for (t of txns(); track t.transactionId) {
      <button type="button" class="txn" (click)="open.set(open() === t.transactionId ? null : t.transactionId)"
              [attr.aria-expanded]="open() === t.transactionId">
        <span class="icon" [class]="t.type">{{ icon(t) }}</span>
        <span class="what">
          <strong>{{ title(t) }}</strong>
          <small class="muted">{{ t.paymentInitiationDate | date: 'd MMM, h:mm a' }}</small>
        </span>
        <span class="amount" [class.credit]="isCredit(t)" [class.failed]="t.status === 'REJECTED'">
          <span>{{ isCredit(t) ? '+' : '−' }} {{ t.amount | currency: 'INR' }}</span>
          <small class="status" [class]="t.status">{{ t.status.toLowerCase() }}</small>
        </span>
      </button>
      @if (open() === t.transactionId) {
        <dl class="details">
          <dt>Transaction ID</dt><dd><code>{{ t.transactionId }}</code></dd>
          <dt>Status</dt><dd>{{ t.status }}@if (t.failureReason) { — {{ t.failureReason }} }</dd>
          @if (t.type === 'TRANSFER') {
            <dt>From</dt><dd>{{ t.senderName ?? 'User #' + t.senderId }}</dd>
            <dt>To</dt><dd>{{ t.receiverName ?? 'User #' + t.receiverId }}</dd>
          }
          @if (t.billCategory) { <dt>Biller</dt><dd>{{ billName(t) }} · {{ t.billAccount }}</dd> }
          @if (t.remark) { <dt>Remark</dt><dd>{{ t.remark }}</dd> }
          <dt>Date</dt><dd>{{ t.paymentInitiationDate | date: 'medium' }}</dd>
        </dl>
      }
    } @empty {
      <p class="muted center">No transactions yet.</p>
    }
  `,
})
export class TxnList {
  txns = input.required<Txn[]>();
  meId = input.required<number>();
  open = signal<string | null>(null);

  isCredit(t: Txn) {
    return t.receiverId === this.meId();
  }

  billName(t: Txn) {
    return t.billCategory ? BILL_CATEGORIES[t.billCategory] : '';
  }

  title(t: Txn) {
    switch (t.type) {
      case 'ADD_MONEY': return 'Added to wallet';
      case 'WITHDRAW': return 'Withdrawn to bank';
      case 'BILL_PAYMENT': return `${this.billName(t)} · ${t.billAccount}`;
      case 'TRANSFER':
        return this.isCredit(t)
          ? `Received from ${t.senderName ?? 'User #' + t.senderId}`
          : `Paid to ${t.receiverName ?? 'User #' + t.receiverId}`;
    }
  }

  icon(t: Txn) {
    return { ADD_MONEY: '+', WITHDRAW: '↓', BILL_PAYMENT: '⚡', TRANSFER: this.isCredit(t) ? '↙' : '↗' }[t.type];
  }
}

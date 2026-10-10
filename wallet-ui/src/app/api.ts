import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { CanActivateFn, Router } from '@angular/router';
import { Observable, catchError, firstValueFrom, throwError } from 'rxjs';

export interface User {
  userId: number;
  fullName: string;
  mobileNo: string;
  emailId: string;
  address?: string;
  pan?: string;
  createdDate: string;
}

export interface Wallet {
  walletId: number;
  balance: number;
  dailyLimit: number;
  dailyTransactionLimit: number;
  spentToday: number;
  transactionsToday: number;
}

export type TxnType = 'TRANSFER' | 'ADD_MONEY' | 'WITHDRAW' | 'BILL_PAYMENT';

export interface Txn {
  transactionId: string;
  type: TxnType;
  senderId?: number;
  receiverId?: number;
  senderName?: string;
  receiverName?: string;
  amount: number;
  status: 'PENDING' | 'SUCCESSFUL' | 'REJECTED';
  remark?: string;
  failureReason?: string;
  billCategory?: keyof typeof BILL_CATEGORIES;
  billAccount?: string;
  paymentInitiationDate: string;
}

export const BILL_CATEGORIES = {
  MOBILE_RECHARGE: 'Mobile Recharge',
  DTH: 'DTH',
  ELECTRICITY: 'Electricity',
  WATER: 'Water',
  GAS: 'Gas',
  BROADBAND: 'Broadband',
} as const;

interface Session {
  token: string;
  user: User;
}

const SESSION_KEY = 'paymesh.session';

@Injectable({ providedIn: 'root' })
export class Api {
  private http = inject(HttpClient);
  private router = inject(Router);

  readonly session = signal<Session | null>(loadSession());
  readonly user = computed(() => this.session()?.user);

  login(username: string, password: string) {
    return this.startSession(this.http.post<Session>('/api/auth/login', { username, password }));
  }

  register(body: object) {
    return this.startSession(this.http.post<Session>('/api/auth/register', body));
  }

  logout() {
    localStorage.removeItem(SESSION_KEY);
    this.session.set(null);
    this.router.navigate(['/login']);
  }

  async updateMe(body: { fullName: string; emailId: string; address?: string }) {
    const user = await firstValueFrom(this.http.put<User>('/api/users/me', body));
    this.saveSession({ ...this.session()!, user });
  }

  async deleteMe() {
    await firstValueFrom(this.http.delete('/api/users/me'));
    this.logout();
  }

  lookup(mobile: string) {
    return firstValueFrom(this.http.get<{ userId: number; fullName: string }>('/api/users/lookup', { params: { mobile } }));
  }

  wallet() {
    return firstValueFrom(this.http.get<Wallet>('/api/wallets/me'));
  }

  updateLimits(dailyLimit: number, dailyTransactionLimit: number) {
    return firstValueFrom(this.http.put<Wallet>('/api/wallets/me/limits', { dailyLimit, dailyTransactionLimit }));
  }

  createTxn(body: object) {
    return firstValueFrom(this.http.post<Txn>('/api/transactions', body));
  }

  txn(id: string) {
    return firstValueFrom(this.http.get<Txn>(`/api/transactions/${id}`));
  }

  history(page = 0, size = 20) {
    return firstValueFrom(this.http.get<Txn[]>('/api/transactions', { params: { page, size } }));
  }

  private async startSession(request: Observable<Session>) {
    this.saveSession(await firstValueFrom(request));
  }

  private saveSession(s: Session) {
    localStorage.setItem(SESSION_KEY, JSON.stringify(s));
    this.session.set(s);
  }
}

function loadSession(): Session | null {
  try {
    return JSON.parse(localStorage.getItem(SESSION_KEY) ?? 'null');
  } catch {
    return null;
  }
}

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const api = inject(Api);
  const token = api.session()?.token;
  return next(token ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : req).pipe(
    catchError((e: HttpErrorResponse) => {
      if (e.status === 401 && token) api.logout(); // expired / invalid token
      return throwError(() => e);
    }),
  );
};

export const authGuard: CanActivateFn = () => !!inject(Api).session() || inject(Router).createUrlTree(['/login']);

/** Backend errors are ProblemDetails; show their detail. */
export function errorMessage(e: unknown): string {
  if (e instanceof HttpErrorResponse) {
    if (e.status === 0 || e.status === 504) return 'Server is unreachable. Is the backend running?';
    return e.error?.detail ?? e.message;
  }
  return String(e);
}

export const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { CanActivateFn, Router } from '@angular/router';
import { Observable, catchError, firstValueFrom, from, switchMap, throwError } from 'rxjs';

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

@Injectable({ providedIn: 'root' })
export class Api {
  private http = inject(HttpClient);
  private router = inject(Router);

  /**
   * In memory only: the access token never touches localStorage (where an XSS bug could read it). A page reload
   * restores the session from the HttpOnly refresh cookie instead (restore()).
   */
  readonly session = signal<Session | null>(null);
  readonly user = computed(() => this.session()?.user);

  private refreshing: Promise<Session | null> | null = null;

  login(username: string, password: string) {
    return this.startSession(this.http.post<Session>('/api/auth/login', { username, password }));
  }

  register(body: object) {
    return this.startSession(this.http.post<Session>('/api/auth/register', body));
  }

  /** Called once at startup: if the refresh cookie is still good, the user is logged in again. */
  async restore() {
    await this.refresh();
  }

  /** One refresh at a time: concurrent 401s share it. Resolves to null when the session is gone. */
  refresh(): Promise<Session | null> {
    this.refreshing ??= firstValueFrom(this.http.post<Session>('/api/auth/refresh', {}))
      .then((s) => {
        this.session.set(s);
        return s;
      })
      .catch(() => {
        this.session.set(null);
        return null;
      })
      .finally(() => (this.refreshing = null));
    return this.refreshing;
  }

  /** Revokes the session server-side (refresh cookie and current access token), then forgets it here. */
  async logout() {
    try {
      await firstValueFrom(this.http.post('/api/auth/logout', {}));
    } catch {
      // already logged out or offline: forgetting the session locally is what matters to the user
    }
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

  /** The key makes a retry of the same attempt safe: the server replays the first response instead of paying twice. */
  createTxn(body: object, idempotencyKey: string) {
    return firstValueFrom(this.http.post<Txn>('/api/transactions', body, { headers: { 'Idempotency-Key': idempotencyKey } }));
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
    this.session.set(s);
  }
}

/**
 * Adds the access token. On a 401 for a request that carried one, refreshes once (the 15-minute token simply
 * expired) and retries; if the session is gone, logs out. /api/auth/* calls are never retried, so no loops.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const api = inject(Api);
  const token = api.session()?.token;
  const withToken = (t: string | undefined) => (t ? req.clone({ setHeaders: { Authorization: `Bearer ${t}` } }) : req);
  return next(withToken(token)).pipe(
    catchError((e: HttpErrorResponse) => {
      if (e.status !== 401 || !token || req.url.startsWith('/api/auth/')) return throwError(() => e);
      return from(api.refresh()).pipe(
        switchMap((session) => {
          if (!session) {
            api.logout();
            return throwError(() => e);
          }
          return next(withToken(session.token));
        }),
      );
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

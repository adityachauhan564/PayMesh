import { TestBed } from '@angular/core/testing';
import { HttpClient, HttpErrorResponse, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, UrlTree, provideRouter } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { Api, authGuard, authInterceptor, errorMessage } from './api';

const USER = { userId: 1, fullName: 'Asha', mobileNo: '9000000001', emailId: 'asha@example.com', createdDate: '2026-01-01' };
const SESSION = { token: 't1', user: USER };

function setup(withSession: boolean) {
  localStorage.clear();
  TestBed.configureTestingModule({
    providers: [provideHttpClient(withInterceptors([authInterceptor])), provideHttpClientTesting(), provideRouter([])],
  });
  const router = TestBed.inject(Router);
  const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
  const api = TestBed.inject(Api);
  if (withSession) api.session.set(SESSION);
  return { api, http: TestBed.inject(HttpClient), backend: TestBed.inject(HttpTestingController), navigate };
}

/** Lets promise-based code (refresh, then retry) reach its next HTTP call. */
const settle = () => new Promise((r) => setTimeout(r, 0));

describe('authInterceptor', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('adds Bearer header when a session exists', () => {
    const { http, backend } = setup(true);
    http.get('/api/wallets/me').subscribe();
    const req = backend.expectOne('/api/wallets/me');
    expect(req.request.headers.get('Authorization')).toBe('Bearer t1');
    req.flush({});
  });

  it('sends no Authorization header without a session', () => {
    const { http, backend } = setup(false);
    http.get('/api/wallets/me').subscribe();
    const req = backend.expectOne('/api/wallets/me');
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush({});
  });

  it('a 401 refreshes once and retries with the new token', async () => {
    const { api, http, backend } = setup(true);
    const call = firstValueFrom(http.get<{ ok: boolean }>('/api/wallets/me'));

    backend.expectOne('/api/wallets/me').flush(null, { status: 401, statusText: 'Unauthorized' });
    await settle();
    backend.expectOne('/api/auth/refresh').flush({ token: 't2', user: USER });
    await settle();
    const retry = backend.expectOne('/api/wallets/me');
    expect(retry.request.headers.get('Authorization')).toBe('Bearer t2');
    retry.flush({ ok: true });

    expect(await call).toEqual({ ok: true });
    expect(api.session()?.token).toBe('t2');
  });

  it('a failed refresh logs out without looping', async () => {
    const { api, http, backend, navigate } = setup(true);
    const call = firstValueFrom(http.get('/api/wallets/me')).catch((e) => e);

    backend.expectOne('/api/wallets/me').flush(null, { status: 401, statusText: 'Unauthorized' });
    await settle();
    backend.expectOne('/api/auth/refresh').flush(null, { status: 401, statusText: 'Unauthorized' });
    await settle();
    backend.expectOne('/api/auth/logout').flush(null, { status: 204, statusText: 'No Content' });
    await settle();

    expect((await call).status).toBe(401);
    expect(api.session()).toBeNull();
    expect(navigate).toHaveBeenCalledWith(['/login']);
  });

  it('401 on login (no token) does not refresh or log out', async () => {
    const { http, backend, navigate } = setup(false);
    const call = firstValueFrom(http.post('/api/auth/login', {})).catch(() => undefined);
    backend.expectOne('/api/auth/login').flush(null, { status: 401, statusText: 'Unauthorized' });
    await call;
    expect(navigate).not.toHaveBeenCalled();
  });
});

describe('Api session', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('restore uses the refresh cookie', async () => {
    const { api, backend } = setup(false);
    const restored = api.restore();
    backend.expectOne('/api/auth/refresh').flush({ token: 't9', user: USER });
    await restored;
    expect(api.session()?.token).toBe('t9');
  });

  it('restore without a cookie stays logged out', async () => {
    const { api, backend } = setup(false);
    const restored = api.restore();
    backend.expectOne('/api/auth/refresh').flush(null, { status: 401, statusText: 'Unauthorized' });
    await restored;
    expect(api.session()).toBeNull();
  });

  it('refreshes under a cross-tab lock so tabs sharing the cookie take turns', async () => {
    const { api, backend } = setup(false);
    const request = vi.fn((_name: string, callback: () => Promise<unknown>) => callback());
    vi.stubGlobal('navigator', { ...navigator, locks: { request } });
    try {
      const restored = api.restore();
      await settle();
      backend.expectOne('/api/auth/refresh').flush({ token: 't9', user: USER });
      await restored;
      expect(request).toHaveBeenCalledWith('paymesh-refresh', expect.any(Function));
      expect(api.session()?.token).toBe('t9');
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it('keeps the session out of localStorage', async () => {
    const { api, backend } = setup(false);
    const login = api.login('9000000001', 'secret123');
    backend.expectOne('/api/auth/login').flush(SESSION);
    await login;
    expect(api.session()?.token).toBe('t1');
    expect(localStorage.length).toBe(0);
  });

  it('logout tells the server, then forgets the session', async () => {
    const { api, backend, navigate } = setup(true);
    const done = api.logout();
    const req = backend.expectOne('/api/auth/logout');
    expect(req.request.headers.get('Authorization')).toBe('Bearer t1');
    req.flush(null, { status: 204, statusText: 'No Content' });
    await done;
    expect(api.session()).toBeNull();
    expect(navigate).toHaveBeenCalledWith(['/login']);
  });
});

describe('Api.createTxn', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('sends the Idempotency-Key header', () => {
    const { api, backend } = setup(true);
    api.createTxn({ type: 'ADD_MONEY', amount: 100 }, 'k-1');
    const req = backend.expectOne('/api/transactions');
    expect(req.request.headers.get('Idempotency-Key')).toBe('k-1');
    req.flush({});
  });
});

describe('authGuard', () => {
  it('redirects to /login without a session', () => {
    setup(false);
    const result = TestBed.runInInjectionContext(() =>
      authGuard({} as ActivatedRouteSnapshot, {} as RouterStateSnapshot),
    );
    expect(result).toBeInstanceOf(UrlTree);
    expect(TestBed.inject(Router).serializeUrl(result as UrlTree)).toBe('/login');
  });
});

describe('errorMessage', () => {
  it('maps status 0 to unreachable', () => {
    expect(errorMessage(new HttpErrorResponse({ status: 0 }))).toBe('Server is unreachable. Is the backend running?');
  });

  it('returns ProblemDetail detail', () => {
    expect(errorMessage(new HttpErrorResponse({ status: 400, error: { detail: 'Insufficient balance' } }))).toBe(
      'Insufficient balance',
    );
  });
});

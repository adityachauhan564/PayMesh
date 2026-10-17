import { TestBed } from '@angular/core/testing';
import { HttpClient, HttpErrorResponse, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, UrlTree, provideRouter } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { Api, authGuard, authInterceptor, errorMessage } from './api';

const SESSION = {
  token: 't1',
  user: { userId: 1, fullName: 'Asha', mobileNo: '9000000001', emailId: 'asha@example.com', createdDate: '2026-01-01' },
};

function setup(withSession: boolean) {
  localStorage.clear();
  if (withSession) localStorage.setItem('paymesh.session', JSON.stringify(SESSION));
  TestBed.configureTestingModule({
    providers: [provideHttpClient(withInterceptors([authInterceptor])), provideHttpClientTesting(), provideRouter([])],
  });
  const router = TestBed.inject(Router);
  const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
  return {
    api: TestBed.inject(Api),
    http: TestBed.inject(HttpClient),
    backend: TestBed.inject(HttpTestingController),
    navigate,
  };
}

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

  it('401 on an authenticated request logs out', async () => {
    const { api, http, backend, navigate } = setup(true);
    const call = firstValueFrom(http.get('/api/wallets/me')).catch(() => undefined);
    backend.expectOne('/api/wallets/me').flush(null, { status: 401, statusText: 'Unauthorized' });
    await call;
    expect(api.session()).toBeNull();
    expect(localStorage.getItem('paymesh.session')).toBeNull();
    expect(navigate).toHaveBeenCalledWith(['/login']);
  });

  it('401 on login (no token) does not log out or navigate', async () => {
    const { http, backend, navigate } = setup(false);
    const call = firstValueFrom(http.post('/api/auth/login', {})).catch(() => undefined);
    backend.expectOne('/api/auth/login').flush(null, { status: 401, statusText: 'Unauthorized' });
    await call;
    expect(navigate).not.toHaveBeenCalled();
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

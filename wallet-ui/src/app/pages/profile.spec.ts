import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Api } from '../api';
import { ProfilePage } from './profile';

/** Enrolment shows a QR code for the authenticator app (the spec's requirement), not only the 32-character key. */
describe('ProfilePage two-factor setup', () => {
  it('shows a QR code of the otpauth URI after setup', async () => {
    const user = { userId: 1, fullName: 'Asha', mobileNo: '9000000001', emailId: 'a@example.com', createdDate: '2026-01-01' };
    const api = {
      user: () => user,
      wallet: vi.fn().mockResolvedValue({ dailyLimit: 10000, dailyTransactionLimit: 10 }),
      setup2fa: vi.fn().mockResolvedValue({ secret: 'JBSWY3DPEHPK3PXP', otpauthUri: 'otpauth://totp/PayMesh:a@example.com?secret=JBSWY3DPEHPK3PXP' }),
    };
    TestBed.configureTestingModule({ imports: [ProfilePage], providers: [provideRouter([]), { provide: Api, useValue: api }] });
    const fixture = TestBed.createComponent(ProfilePage);
    fixture.detectChanges();

    fixture.componentInstance.tfPassword = 'secret123';
    await fixture.componentInstance.setup2fa();
    await fixture.whenStable();
    fixture.detectChanges();

    const qr = fixture.nativeElement.querySelector('img.qr') as HTMLImageElement | null;
    expect(qr).not.toBeNull();
    expect(qr!.src).toMatch(/^data:image\/png;base64,/);
  });
});

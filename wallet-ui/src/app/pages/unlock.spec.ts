import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { Api } from '../api';
import { UnlockPage } from './unlock';

/** Mail scanners open links (some even run the page's JavaScript): only a click may unlock. */
describe('UnlockPage', () => {
  function render() {
    const unlock = vi.fn().mockResolvedValue(null);
    TestBed.configureTestingModule({
      imports: [UnlockPage],
      providers: [
        provideRouter([{ path: 'login', children: [] }]),
        { provide: Api, useValue: { unlock } },
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap({ token: 'abc' }) } } },
      ],
    });
    const fixture = TestBed.createComponent(UnlockPage);
    fixture.detectChanges();
    return { fixture, unlock };
  }

  it('does not unlock just because the page was opened', async () => {
    const { fixture, unlock } = render();
    await fixture.whenStable();
    expect(unlock).not.toHaveBeenCalled();
  });

  it('unlocks when the owner clicks the button', async () => {
    const { fixture, unlock } = render();
    (fixture.nativeElement.querySelector('button') as HTMLButtonElement).click();
    await fixture.whenStable();
    expect(unlock).toHaveBeenCalledWith('abc');
  });
});

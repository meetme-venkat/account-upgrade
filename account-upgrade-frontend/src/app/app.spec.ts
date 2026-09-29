import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { App } from './app';
import { UpgradeApiService } from './core/api/upgrade-api.service';

describe('App', () => {
  async function render(up: boolean): Promise<HTMLElement> {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideRouter([]),
        { provide: UpgradeApiService, useValue: { health: () => of(up) } },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    return fixture.nativeElement;
  }

  it('renders the navigation', async () => {
    const element = await render(true);
    const links = Array.from(element.querySelectorAll('nav a')).map((a) => a.textContent?.trim());
    expect(links).toEqual(['Submit', 'Processed', 'Notifications']);
  });

  it('shows the backend health', async () => {
    const element = await render(false);
    expect(element.querySelector('.health')?.textContent).toContain('Backend down');
  });
});

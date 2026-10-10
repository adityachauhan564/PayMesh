import { Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { Api } from './api';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    @if (api.user(); as user) {
      <header class="topbar">
        <a routerLink="/" class="brand">Pay<span>Mesh</span></a>
        <nav>
          <a routerLink="/" routerLinkActive="active" [routerLinkActiveOptions]="{ exact: true }">Home</a>
          <a routerLink="/pay" routerLinkActive="active">Pay</a>
          <a routerLink="/history" routerLinkActive="active">History</a>
          <a routerLink="/profile" routerLinkActive="active" class="avatar" [title]="user.fullName">{{ user.fullName[0] }}</a>
        </nav>
      </header>
    }
    <main><router-outlet /></main>
  `,
})
export class App {
  protected api = inject(Api);
}

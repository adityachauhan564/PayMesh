import { Routes } from '@angular/router';
import { authGuard } from './api';
import { AuthPage } from './pages/auth';
import { DashboardPage } from './pages/dashboard';
import { PayPage } from './pages/pay';
import { HistoryPage } from './pages/history';
import { ProfilePage } from './pages/profile';

export const routes: Routes = [
  { path: 'login', component: AuthPage, title: 'Login · PayMesh' },
  {
    path: '',
    canActivate: [authGuard],
    children: [
      { path: '', component: DashboardPage, title: 'PayMesh' },
      { path: 'pay', component: PayPage, title: 'Pay · PayMesh' },
      { path: 'history', component: HistoryPage, title: 'History · PayMesh' },
      { path: 'profile', component: ProfilePage, title: 'Profile · PayMesh' },
    ],
  },
  { path: '**', redirectTo: '' },
];

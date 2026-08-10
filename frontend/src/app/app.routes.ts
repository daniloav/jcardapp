import { Routes } from '@angular/router';
import { adminGuard, autenticadoGuard, visitanteGuard } from './core/guards';

/** Telas carregadas sob demanda: o bundle inicial fica pequeno no celular. */
export const rotas: Routes = [
  {
    path: 'login',
    canActivate: [visitanteGuard],
    loadComponent: () => import('./pages/login.component').then((m) => m.LoginComponent),
    title: 'Entrar · JcardApp',
  },
  {
    path: 'recuperar',
    loadComponent: () => import('./pages/recuperar.component').then((m) => m.RecuperarComponent),
    title: 'Esqueci minha senha · JcardApp',
  },
  {
    path: 'trocar-senha',
    loadComponent: () =>
      import('./pages/trocar-senha.component').then((m) => m.TrocarSenhaComponent),
    title: 'Trocar senha · JcardApp',
  },
  {
    path: 'faturas',
    canActivate: [autenticadoGuard],
    loadComponent: () => import('./pages/faturas.component').then((m) => m.FaturasComponent),
    title: 'Faturas · JcardApp',
  },
  {
    path: 'faturas/:id/minhas-contas',
    canActivate: [autenticadoGuard],
    loadComponent: () =>
      import('./pages/minhas-contas.component').then((m) => m.MinhasContasComponent),
    title: 'Minhas contas · JcardApp',
  },
  {
    path: 'meus-acertos',
    canActivate: [autenticadoGuard],
    loadComponent: () =>
      import('./pages/meus-acertos.component').then((m) => m.MeusAcertosComponent),
    title: 'Meus acertos · JcardApp',
  },
  {
    path: 'admin/importar',
    canActivate: [adminGuard],
    loadComponent: () =>
      import('./pages/admin-importar.component').then((m) => m.AdminImportarComponent),
    title: 'Importar fatura · JcardApp',
  },
  {
    path: 'admin/faturas/:id',
    canActivate: [adminGuard],
    loadComponent: () =>
      import('./pages/admin-fatura.component').then((m) => m.AdminFaturaComponent),
    title: 'Conciliação · JcardApp',
  },
  {
    path: 'admin/utilizadores',
    canActivate: [adminGuard],
    loadComponent: () =>
      import('./pages/admin-usuarios.component').then((m) => m.AdminUsuariosComponent),
    title: 'Utilizadores · JcardApp',
  },
  {
    path: 'admin/cartoes',
    canActivate: [adminGuard],
    loadComponent: () =>
      import('./pages/admin-cartoes.component').then((m) => m.AdminCartoesComponent),
    title: 'Cartões · JcardApp',
  },
  { path: '', pathMatch: 'full', redirectTo: 'faturas' },
  { path: '**', redirectTo: 'faturas' },
];

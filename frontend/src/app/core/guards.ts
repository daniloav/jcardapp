import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';
import { ToastService } from './toast.service';

/** Exige sessão e senha já trocada. */
export const autenticadoGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);

  if (!auth.autenticado()) {
    return router.createUrlTree(['/login']);
  }
  // Conta com a senha provisória não navega para lugar nenhum além da troca —
  // é o mesmo bloqueio que o backend aplica, só que sem o round-trip.
  if (auth.precisaTrocarSenha()) {
    return router.createUrlTree(['/trocar-senha']);
  }
  return true;
};

export const adminGuard: CanActivateFn = (rota, estado) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const toast = inject(ToastService);

  const permitido = autenticadoGuard(rota, estado);
  if (permitido !== true) {
    return permitido;
  }
  if (!auth.isAdmin()) {
    toast.erro('Essa área é só do administrador.');
    return router.createUrlTree(['/inicio']);
  }
  return true;
};

/** Quem já entrou não vê a tela de login de novo. */
export const visitanteGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return auth.autenticado() ? router.createUrlTree(['/inicio']) : true;
};

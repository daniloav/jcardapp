import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { AuthService } from './auth.service';
import { ToastService } from './toast.service';

/**
 * Anexa o JWT e traduz o erro do backend ({@code {message,status}}) num toast.
 *
 * <p>401 derruba a sessão avisando o motivo — sem isso a pessoa fica clicando
 * numa tela que não responde e não entende por quê.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const toast = inject(ToastService);

  const token = auth.token;
  const requisicao = token
    ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } })
    : req;

  return next(requisicao).pipe(
    catchError((erro: HttpErrorResponse) => {
      if (erro.status === 401 && !req.url.includes('/auth/login')) {
        auth.sair('Sua sessão expirou. Entre de novo.');
      } else if (erro.status === 0) {
        toast.erro('Sem conexão com o servidor.');
      } else {
        toast.erro(erro.error?.message ?? 'Não consegui completar a operação.');
      }
      return throwError(() => erro);
    })
  );
};

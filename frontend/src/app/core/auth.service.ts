import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, tap } from 'rxjs';
import { LoginResponse, Usuario } from './models';

const CHAVE_TOKEN = 'jcard.token';
const CHAVE_USUARIO = 'jcard.usuario';

/**
 * Sessão do utilizador.
 *
 * <p>Token e perfil ficam em {@code localStorage} para o app sobreviver ao
 * fechamento — é PWA, a pessoa abre pelo ícone e não quer relogar toda vez.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private http = inject(HttpClient);
  private router = inject(Router);

  readonly usuario = signal<Usuario | null>(this.lerUsuarioSalvo());

  readonly autenticado = computed(() => this.usuario() !== null);
  readonly isAdmin = computed(() => this.usuario()?.admin === true);
  readonly precisaTrocarSenha = computed(() => this.usuario()?.precisaTrocarSenha === true);

  get token(): string | null {
    return localStorage.getItem(CHAVE_TOKEN);
  }

  entrar(login: string, senha: string): Observable<LoginResponse> {
    return this.http.post<LoginResponse>('/api/auth/login', { login, senha })
      .pipe(tap((r) => this.guardar(r)));
  }

  trocarSenha(senhaAtual: string, senhaNova: string): Observable<LoginResponse> {
    // O backend devolve um token novo: o antigo carrega precisaTrocarSenha=true
    // e continuaria barrando a pessoa.
    return this.http.put<LoginResponse>('/api/me/senha', { senhaAtual, senhaNova })
      .pipe(tap((r) => this.guardar(r)));
  }

  sair(motivo?: string): void {
    localStorage.removeItem(CHAVE_TOKEN);
    localStorage.removeItem(CHAVE_USUARIO);
    this.usuario.set(null);
    this.router.navigate(['/login'], motivo ? { queryParams: { motivo } } : {});
  }

  private guardar(r: LoginResponse): void {
    localStorage.setItem(CHAVE_TOKEN, r.token);
    localStorage.setItem(CHAVE_USUARIO, JSON.stringify(r.usuario));
    this.usuario.set(r.usuario);
  }

  private lerUsuarioSalvo(): Usuario | null {
    const bruto = localStorage.getItem(CHAVE_USUARIO);
    if (!bruto || !localStorage.getItem(CHAVE_TOKEN)) {
      return null;
    }
    try {
      return JSON.parse(bruto) as Usuario;
    } catch {
      return null;
    }
  }
}

import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { APP_VERSION } from '../version';

@Component({
  standalone: true,
  imports: [FormsModule, RouterLink],
  template: `
    <div class="tela-auth">
      <div class="caixa-auth">
        <div class="marca-auth">
          <div class="icone" aria-hidden="true">💳</div>
          <h1>JcardApp</h1>
          <p>Entre para ver as contas da fatura.</p>
        </div>

        @if (motivo()) {
          <div class="aviso">{{ motivo() }}</div>
        }

        <form (ngSubmit)="entrar()">
          <label for="login">
            Login
            <input id="login" name="login" [(ngModel)]="login" autocomplete="username"
                   autocapitalize="none" required />
          </label>
          <label for="senha">
            Senha
            <input id="senha" name="senha" type="password" [(ngModel)]="senha"
                   autocomplete="current-password" required />
          </label>
          <button type="submit" [disabled]="carregando()">
            {{ carregando() ? 'Entrando…' : 'Entrar' }}
          </button>
        </form>

        <a class="link-auth" routerLink="/recuperar">Esqueci minha senha</a>

        <a class="link-treinamentos" href="assets/tutoriais/index.html"
           target="_blank" rel="noopener">
          🎓 Primeira vez? Veja como usar o app
        </a>

        <p class="creditos">
          Desenvolvido por <a href="mailto:danilo.av&#64;gmail.com">Luke Skywalker</a>
          <span class="versao">v{{ versao }}</span>
        </p>
      </div>
    </div>
  `,
})
export class LoginComponent {
  private auth = inject(AuthService);
  private router = inject(Router);
  private rota = inject(ActivatedRoute);

  versao = APP_VERSION;
  login = '';
  senha = '';
  carregando = signal(false);
  motivo = signal<string | null>(this.rota.snapshot.queryParamMap.get('motivo'));

  entrar(): void {
    if (!this.login || !this.senha || this.carregando()) {
      return;
    }
    this.carregando.set(true);
    this.auth.entrar(this.login, this.senha).subscribe({
      next: (r) => {
        this.carregando.set(false);
        // Senha provisória: a pessoa não chega a ver o app antes de trocar.
        this.router.navigate([r.precisaTrocarSenha ? '/trocar-senha' : '/faturas']);
      },
      error: () => this.carregando.set(false),
    });
  }
}

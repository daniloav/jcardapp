import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { AuthService } from '../core/auth.service';

@Component({
  standalone: true,
  imports: [FormsModule],
  template: `
    <div class="centro">
      <h1>JcardApp</h1>
      <p class="sub">Entre para ver as contas da fatura.</p>

      @if (motivo()) {
        <div class="aviso">{{ motivo() }}</div>
      }

      <form class="cartao" (ngSubmit)="entrar()">
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
    </div>
  `,
})
export class LoginComponent {
  private auth = inject(AuthService);
  private router = inject(Router);
  private rota = inject(ActivatedRoute);

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

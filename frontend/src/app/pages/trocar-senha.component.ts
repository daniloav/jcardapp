import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { ToastService } from '../core/toast.service';

@Component({
  standalone: true,
  imports: [FormsModule],
  template: `
    <div class="centro">
      <h1>Trocar senha</h1>
      @if (auth.precisaTrocarSenha()) {
        <div class="aviso">
          Você está com a senha provisória. Defina uma senha sua para continuar.
        </div>
      }

      <form class="cartao" (ngSubmit)="salvar()">
        <label for="atual">
          Senha atual
          <input id="atual" name="atual" type="password" [(ngModel)]="atual"
                 autocomplete="current-password" required />
        </label>
        <label for="nova">
          Nova senha (mínimo 8 caracteres)
          <input id="nova" name="nova" type="password" [(ngModel)]="nova"
                 autocomplete="new-password" minlength="8" required />
        </label>
        <label for="confirma">
          Repita a nova senha
          <input id="confirma" name="confirma" type="password" [(ngModel)]="confirma"
                 autocomplete="new-password" required />
        </label>
        <button type="submit" [disabled]="carregando()">Salvar</button>
      </form>
    </div>
  `,
})
export class TrocarSenhaComponent {
  auth = inject(AuthService);
  private router = inject(Router);
  private toast = inject(ToastService);

  atual = '';
  nova = '';
  confirma = '';
  carregando = signal(false);

  salvar(): void {
    if (this.nova !== this.confirma) {
      this.toast.erro('As duas senhas novas não são iguais.');
      return;
    }
    if (this.nova.length < 8) {
      this.toast.erro('A nova senha precisa ter pelo menos 8 caracteres.');
      return;
    }
    this.carregando.set(true);
    this.auth.trocarSenha(this.atual, this.nova).subscribe({
      next: () => {
        this.carregando.set(false);
        this.toast.ok('Senha alterada.');
        this.router.navigate(['/faturas']);
      },
      error: () => this.carregando.set(false),
    });
  }
}

import { Component, computed, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { ToastService } from '../core/toast.service';

/** Casca do app: cabeçalho, navegação e a pilha de toasts. */
@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    @if (mostrarNav()) {
      <header class="topo">
        <a class="marca" routerLink="/faturas">
          <span aria-hidden="true">💳</span> JcardApp
        </a>
        <nav aria-label="Navegação principal">
          <a routerLink="/faturas" routerLinkActive="ativo">Faturas</a>
          <a routerLink="/meus-acertos" routerLinkActive="ativo">Meus acertos</a>
          @if (auth.isAdmin()) {
            <a routerLink="/admin/importar" routerLinkActive="ativo">Importar</a>
            <a routerLink="/admin/utilizadores" routerLinkActive="ativo">Utilizadores</a>
            <a routerLink="/admin/cartoes" routerLinkActive="ativo">Cartões</a>
          }
        </nav>
        <div class="usuario">
          <span class="nome">{{ auth.usuario()?.nome }}</span>
          <button type="button" class="btn-texto" (click)="auth.sair()">Sair</button>
        </div>
      </header>
    }

    <main [class.com-topo]="mostrarNav()">
      <router-outlet />
    </main>

    <div class="toasts" role="status" aria-live="polite">
      @for (t of toast.toasts(); track t.id) {
        <div class="toast" [class.erro]="t.tipo === 'erro'">
          <span>{{ t.texto }}</span>
          <button type="button" (click)="toast.fechar(t.id)" aria-label="Fechar aviso">×</button>
        </div>
      }
    </div>
  `,
})
export class ShellComponent {
  auth = inject(AuthService);
  toast = inject(ToastService);

  /** Login e troca de senha obrigatória rodam sem menu — não há para onde ir. */
  mostrarNav = computed(() => this.auth.autenticado() && !this.auth.precisaTrocarSenha());
}

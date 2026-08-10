import { Component, computed, effect, inject, signal } from '@angular/core';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs/operators';
import { AuthService } from '../core/auth.service';
import { ToastService } from '../core/toast.service';
import { APP_VERSION } from '../version';

/** Casca do app: menu lateral, conteúdo e a pilha de toasts. */
@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <div class="layout">
      @if (mostrarNav()) {
        <header class="barra-topo">
          <button type="button" class="hamburguer" (click)="menuAberto.set(true)"
                  aria-label="Abrir menu" [attr.aria-expanded]="menuAberto()">☰</button>
          <span class="titulo">💳 JcardApp</span>
        </header>

        @if (menuAberto()) {
          <div class="fundo-menu" (click)="menuAberto.set(false)"></div>
        }

        <aside class="barra-lateral" [class.aberta]="menuAberto()">
          <div class="marca">
            <a routerLink="/inicio">
              💳 JcardApp
              <span class="versao">v{{ versao }}</span>
            </a>
            <button type="button" class="fechar-menu" (click)="menuAberto.set(false)"
                    aria-label="Fechar menu">✕</button>
          </div>

          <nav aria-label="Navegação principal" (click)="fecharNoCelular()">
            <a routerLink="/inicio" routerLinkActive="ativo">
              <span class="ico" aria-hidden="true">📊</span> Início
            </a>
            <a routerLink="/faturas" routerLinkActive="ativo">
              <span class="ico" aria-hidden="true">🧾</span> Faturas
            </a>
            <a routerLink="/meus-acertos" routerLinkActive="ativo">
              <span class="ico" aria-hidden="true">💸</span> Meus acertos
            </a>
            @if (auth.isAdmin()) {
              <div class="grupo">Administração</div>
              <a routerLink="/admin/importar" routerLinkActive="ativo">
                <span class="ico" aria-hidden="true">📥</span> Importar fatura
              </a>
              <a routerLink="/admin/utilizadores" routerLinkActive="ativo">
                <span class="ico" aria-hidden="true">👥</span> Utilizadores
              </a>
              <a routerLink="/admin/cartoes" routerLinkActive="ativo">
                <span class="ico" aria-hidden="true">💳</span> Cartões
              </a>
            }
          </nav>

          <div class="rodape">
            <span class="nome">{{ auth.usuario()?.nome }}</span>
            <span class="papel">{{ auth.isAdmin() ? 'Administrador' : 'Utilizador' }}</span>
            <a class="link-rodape" href="assets/tutoriais/index.html"
               target="_blank" rel="noopener">🎓 Treinamentos</a>
            <a class="link-rodape" routerLink="/trocar-senha" (click)="fecharNoCelular()">
              🔒 Trocar senha
            </a>
            <button type="button" class="btn-sair" (click)="auth.sair()">Sair</button>
          </div>
        </aside>
      }

      <main [class.sem-menu]="!mostrarNav()">
        <router-outlet />
      </main>
    </div>

    <div class="toasts" role="status" aria-live="polite">
      @for (t of toast.toasts(); track t.id) {
        <div class="toast" [class.erro]="t.tipo === 'erro'">
          <span>{{ t.texto }}</span>
          @if (t.acao; as a) {
            <button type="button" class="btn-desfazer" (click)="toast.executar(t)">
              {{ a.texto }}
            </button>
          }
          <button type="button" (click)="toast.fechar(t.id)" aria-label="Fechar aviso">×</button>
        </div>
      }
    </div>
  `,
})
export class ShellComponent {
  auth = inject(AuthService);
  toast = inject(ToastService);
  private router = inject(Router);

  versao = APP_VERSION;
  menuAberto = signal(false);

  /** Login, recuperação e troca de senha obrigatória rodam sem menu — não há para onde ir. */
  mostrarNav = computed(() => this.auth.autenticado() && !this.auth.precisaTrocarSenha());

  constructor() {
    // Trava a rolagem do fundo enquanto a gaveta está aberta: sem isso o dedo
    // que tenta rolar o menu curto rola a lista de lançamentos atrás dele.
    effect(() => {
      document.body.style.overflow = this.menuAberto() ? 'hidden' : '';
    });

    // Navegar por um caminho que não passa pelo menu (link no meio da página,
    // botão "voltar" do celular) também tem de fechar a gaveta.
    this.router.events
      .pipe(filter((e) => e instanceof NavigationEnd))
      .subscribe(() => this.menuAberto.set(false));
  }

  fecharNoCelular(): void {
    this.menuAberto.set(false);
  }
}

import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../core/api.service';
import { ToastService } from '../core/toast.service';
import { Pix } from '../core/models';

/**
 * Onde o titular define para onde o dinheiro vai.
 *
 * <p>Antes isso era variável de ambiente: trocar a chave exigia entrar por ssh
 * na VM e recriar o container. Trocar a chave PIX é decisão do dono do cartão,
 * e o dono do cartão não abre terminal.
 */
@Component({
  standalone: true,
  imports: [FormsModule],
  template: `
    <h1>Chave PIX</h1>
    <p class="sub">
      É esta chave que aparece, com botão de copiar, para cada pessoa na hora de
      pagar o acerto dela. Vale para todas as faturas, inclusive as que já estão
      abertas.
    </p>

    @if (atual(); as p) {
      @if (p.origem === 'NENHUMA') {
        <div class="aviso alerta">
          Nenhuma chave configurada. Enquanto ela faltar, a tela de pagamento
          avisa as pessoas que não há para onde pagar — e não mostra nada para
          copiar.
        </div>
      } @else if (p.origem === 'AMBIENTE') {
        <div class="aviso info">
          A chave em uso vem do <code>.env</code> do servidor
          (<code>JCARD_PIX_CHAVE</code>), definida na instalação. Salvando aqui,
          o app passa a usar o que você digitar e mexer no <code>.env</code>
          deixa de ter efeito.
        </div>
      }

      <form class="cartao" (ngSubmit)="salvar()">
        <fieldset>
          <legend>Para onde as pessoas pagam</legend>

          <label for="tipo">
            Tipo da chave
            <input id="tipo" name="tipo" [(ngModel)]="tipo" maxlength="20"
                   placeholder="ex.: CPF, E-MAIL, TELEFONE" required />
          </label>

          <label for="chave">
            Chave
            <input id="chave" name="chave" [(ngModel)]="chave" maxlength="140"
                   placeholder="ex.: 000.000.000-00" required />
          </label>

          <label for="titular">
            Nome de quem recebe
            <input id="titular" name="titular" [(ngModel)]="titular" maxlength="120"
                   placeholder="como aparece no banco" required />
          </label>

          <p class="meta">
            Confira caractere por caractere: é o que as pessoas vão copiar e colar
            no banco delas. Toda troca fica na auditoria com o seu nome.
          </p>

          <div class="linha">
            <button type="submit" [disabled]="ocupado()">Salvar chave</button>
          </div>
        </fieldset>
      </form>

      @if (p.configurada) {
        <h2>Como a pessoa vê</h2>
        <div class="pix">
          <div class="meta">Chave PIX ({{ p.tipo }}) · {{ p.titular }}</div>
          <div class="linha">
            <code class="chave">{{ p.chave }}</code>
          </div>
        </div>
      }
    }
  `,
})
export class AdminPixComponent {
  private api = inject(ApiService);
  private toast = inject(ToastService);

  atual = signal<Pix | null>(null);
  ocupado = signal(false);

  tipo = '';
  chave = '';
  titular = '';

  constructor() {
    this.carregar();
  }

  private carregar(): void {
    this.api.pix().subscribe({
      next: (p) => {
        this.atual.set(p);
        // O formulário começa com o que está valendo — inclusive quando isso
        // ainda vem do .env: o caso comum é corrigir um dígito, não redigitar.
        this.tipo = p.tipo;
        this.chave = p.chave;
        this.titular = p.titular;
      },
    });
  }

  salvar(): void {
    this.ocupado.set(true);
    this.api.salvarPix(this.tipo, this.chave, this.titular).subscribe({
      next: (p) => {
        this.atual.set(p);
        this.ocupado.set(false);
        this.toast.ok('Chave PIX salva. É ela que as pessoas veem a partir de agora.');
      },
      error: () => this.ocupado.set(false),
    });
  }
}

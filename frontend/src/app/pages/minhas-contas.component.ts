import { CurrencyPipe, DatePipe, LowerCasePipe } from '@angular/common';
import { Component, Input, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { ApiService } from '../core/api.service';
import { ToastService } from '../core/toast.service';
import { Lancamento, MinhasContas, Pessoa, descricaoSemParcela } from '../core/models';

/** Uma linha do editor de divisão: pessoa marcada e quanto ela paga. */
interface LinhaDivisao {
  usuarioId: number;
  nome: string;
  marcado: boolean;
  valor: number | null;
}

/**
 * A tela principal do utilizador numa fatura, e onde o ciclo inteiro acontece:
 * assumir o que é seu, rachar o que foi dividido, ver a fatia dos encargos,
 * aceitar o total e declarar o pagamento.
 *
 * <p>Mostra só o <b>pool</b> e o que é dele. O que outra pessoa assumiu não
 * aparece — decisão de privacidade do projeto. A exceção é a conta dividida:
 * os participantes se veem, porque estavam na mesma mesa.
 */
@Component({
  standalone: true,
  imports: [FormsModule, RouterLink, CurrencyPipe, DatePipe, LowerCasePipe],
  template: `
    @if (dados(); as d) {
      <h1>Fatura de {{ d.fatura.competencia | date: 'MM/yyyy' }}</h1>
      <p class="sub">
        Marque o que foi você. O que ninguém assumir fica com o titular do cartão.
      </p>

      @if (d.fatura.status === 'EM_AVALIACAO') {
        <div class="aviso">
          Ainda em avaliação: o seu total pode mudar enquanto houver lançamento sem
          dono, porque os encargos são divididos entre quem usou o cartão.
        </div>
      } @else if (d.fatura.status !== 'CONCILIADA' && d.fatura.status !== 'FECHADA') {
        <div class="aviso">
          Esta fatura não está em avaliação — não dá para alterar as contas.
        </div>
      }

      <!-- ---------------------------------------------------- o seu total -- -->
      <article class="cartao">
        <div class="linha">
          <strong>Seu total nesta fatura</strong>
          <span class="valor" style="font-size:1.3rem">{{ d.total | currency: 'BRL' }}</span>
        </div>
        <div class="meta">
          {{ d.totalCompras | currency: 'BRL' }} em compras
          + {{ d.totalEncargos | currency: 'BRL' }} de encargos rateados
        </div>

        @if (d.acerto; as a) {
          <div class="linha" style="margin-top:0.75rem">
            <span class="tag" [class.ok]="a.status === 'CONFIRMADO'"
                  [class.alerta]="a.status === 'INFORMADO' || a.status === 'ACEITO'">
              {{ rotuloAcerto(a.status) }}
            </span>

            @if (a.status === 'ABERTO' && podePagar()) {
              <button type="button" [disabled]="ocupado()" (click)="aceitar()">
                Conferi e aceito o valor
              </button>
            }
          </div>

          @if (a.status === 'ABERTO' && !podePagar()) {
            <p class="meta">
              O aceite abre quando o administrador conciliar a fatura — só aí o
              valor para de mudar.
            </p>
          }

          <!-- ------------------------------------------ declarar pagamento -- -->
          @if (a.status === 'ACEITO') {
            <hr>
            <h3>Pagar</h3>
            <div class="pix">
              <div class="meta">Chave PIX ({{ d.pix.tipo }}) · {{ d.pix.titular }}</div>
              <div class="linha">
                <code class="chave">{{ d.pix.chave }}</code>
                <button type="button" class="btn-secundario" (click)="copiarChave(d.pix.chave)">
                  {{ copiado() ? 'Copiado!' : 'Copiar chave' }}
                </button>
              </div>
            </div>

            <form (ngSubmit)="pagar()">
              <label for="pagoEm">Data do pagamento</label>
              <input id="pagoEm" type="date" name="pagoEm" [(ngModel)]="pagoEm" required>

              <label for="comprovante">Comprovante do PIX ou da transferência</label>
              <input id="comprovante" type="file" name="comprovante" required
                     accept="image/*,application/pdf" (change)="escolherArquivo($event)">
              <p class="meta">
                Print ou PDF, até 3 MB. É obrigatório: é ele que comprova o pagamento
                na hora de o administrador confirmar.
              </p>

              <label for="obs">Observação (opcional)</label>
              <input id="obs" type="text" name="observacao" maxlength="400"
                     [(ngModel)]="observacao" placeholder="ex.: paguei junto com o mês passado">

              <button type="submit" [disabled]="ocupado() || !arquivo()">
                Declarar pagamento
              </button>
            </form>
          }

          @if (a.status === 'INFORMADO') {
            <p class="meta">
              Pagamento declarado em {{ a.pagoEm | date: 'dd/MM/yyyy' }} com comprovante
              anexado. Aguardando a confirmação do administrador.
            </p>
          }
          @if (a.status === 'CONFIRMADO') {
            <p class="meta">
              Recebimento confirmado em {{ a.confirmadoEm | date: 'dd/MM/yyyy' }}.
            </p>
          }
        }
      </article>

      <!-- --------------------------------------------------------- pool -- -->
      <h2>Sem dono ({{ d.pool.length }})</h2>
      @if (d.pool.length === 0) {
        <p class="vazio">Nada pendente. Todas as compras já têm responsável.</p>
      } @else {
        <div class="cartao">
          @for (l of d.pool; track l.id) {
            <div class="lancamento">
              <div>
                <div class="desc">{{ limpa(l) }}</div>
                <div class="meta">
                  {{ l.dataCompra | date: 'dd/MM' }}
                  @if (l.parcelaTotal) {
                    · parcela {{ l.parcelaAtual }}/{{ l.parcelaTotal }}
                  }
                  @if (l.final4) { · final {{ l.final4 }} }
                </div>
                @if (l.parcelaTotal && l.parcelaAtual === 1) {
                  <div class="meta">
                    Ao assumir, as {{ l.parcelaTotal! - 1 }} parcelas seguintes
                    já entram no seu nome.
                  </div>
                }
              </div>
              <div style="text-align:right">
                <div class="valor" [class.credito]="l.valor < 0">
                  {{ l.valor | currency: 'BRL' }}
                </div>
                <button type="button" [disabled]="ocupado() || !aberta()"
                        (click)="assumir(l)">Foi minha</button>
              </div>
            </div>
          }
        </div>
      }

      <!-- --------------------------------------------------- minhas contas -- -->
      <h2>Minhas contas ({{ d.meus.length }})</h2>
      @if (d.meus.length === 0) {
        <p class="vazio">Você ainda não assumiu nenhum lançamento nesta fatura.</p>
      } @else {
        <div class="cartao">
          @for (l of d.meus; track l.id) {
            <div class="lancamento">
              <div>
                <div class="desc">{{ limpa(l) }}</div>
                <div class="meta">
                  {{ l.dataCompra | date: 'dd/MM' }}
                  @if (l.parcelaTotal) {
                    · parcela {{ l.parcelaAtual }}/{{ l.parcelaTotal }}
                  }
                  · <span class="tag">{{ rotuloOrigem(l) }}</span>
                </div>

                @if (l.divisao.length > 0) {
                  <div class="meta">
                    Conta dividida · total {{ l.valor | currency: 'BRL' }}
                  </div>
                  <ul class="partes">
                    @for (p of l.divisao; track p.usuarioId) {
                      <li>{{ p.usuarioNome }} — {{ p.valor | currency: 'BRL' }}</li>
                    }
                  </ul>
                }
              </div>
              <div style="text-align:right">
                <div class="valor" [class.credito]="(l.minhaParte ?? l.valor) < 0">
                  {{ l.minhaParte ?? l.valor | currency: 'BRL' }}
                </div>
                <button type="button" class="btn-secundario"
                        [disabled]="ocupado() || !aberta()"
                        (click)="devolver(l)">Não foi minha</button>
                @if (souResponsavel(l)) {
                  <button type="button" class="btn-secundario"
                          [disabled]="ocupado() || !aberta()"
                          (click)="abrirDivisao(l)">
                    {{ l.divisao.length > 0 ? 'Editar divisão' : 'Dividir' }}
                  </button>
                  @if (l.divisao.length > 0) {
                    <button type="button" class="btn-secundario"
                            [disabled]="ocupado() || !aberta()"
                            (click)="juntar(l)">Desfazer divisão</button>
                  }
                }
              </div>
            </div>

            <!-- ------------------------------------------ editor da divisão -- -->
            @if (dividindo()?.id === l.id) {
              <div class="editor-divisao">
                <div class="linha">
                  <strong>Dividir {{ limpa(l) }} · {{ l.valor | currency: 'BRL' }}</strong>
                  <button type="button" class="btn-secundario" (click)="igualmente(l)">
                    Dividir igualmente
                  </button>
                </div>

                @for (linha of linhas(); track linha.usuarioId) {
                  <div class="linha-parte">
                    <label>
                      <input type="checkbox" [checked]="linha.marcado"
                             (change)="alternar(linha)">
                      {{ linha.nome }}
                    </label>
                    <input type="number" step="0.01" inputmode="decimal"
                           [disabled]="!linha.marcado"
                           [ngModel]="linha.valor" (ngModelChange)="mudarValor(linha, $event)"
                           [attr.aria-label]="'Parte de ' + linha.nome"
                           [name]="'parte' + linha.usuarioId">
                  </div>
                }

                <div class="linha somatorio"
                     [class.erro]="!fecha(l)" [class.ok-texto]="fecha(l)">
                  <span>Soma das partes</span>
                  <strong>
                    {{ somaPartes() | currency: 'BRL' }}
                    @if (!fecha(l)) {
                      · {{ diferenca(l) > 0 ? 'faltam' : 'sobram' }}
                      {{ abs(diferenca(l)) | currency: 'BRL' }}
                    }
                  </strong>
                </div>

                <div class="linha">
                  <button type="button" [disabled]="ocupado() || !fecha(l) || marcadas() < 2"
                          (click)="salvarDivisao(l)">Salvar divisão</button>
                  <button type="button" class="btn-secundario" (click)="fecharDivisao()">
                    Cancelar
                  </button>
                </div>
                @if (marcadas() < 2) {
                  <p class="meta">Marque pelo menos duas pessoas.</p>
                }
              </div>
            }
          }
        </div>
      }

      <!-- ------------------------------------------------------- encargos -- -->
      @if (d.encargos.length > 0) {
        <h2>Encargos rateados ({{ d.encargos.length }})</h2>
        <p class="sub">
          IOF, anuidade, juros e ajustes são divididos entre todos que usaram o
          cartão no mês — ninguém reivindica, e a sobra de centavos fica com o titular.
        </p>
        <div class="cartao">
          @for (l of d.encargos; track l.id) {
            <div class="lancamento">
              <div>
                <div class="desc">{{ limpa(l) }}</div>
                <div class="meta">
                  {{ l.dataCompra | date: 'dd/MM' }} · {{ l.tipo | lowercase }} ·
                  total {{ l.valor | currency: 'BRL' }}
                </div>
              </div>
              <div style="text-align:right">
                <div class="valor" [class.credito]="(l.minhaParte ?? 0) < 0">
                  {{ l.minhaParte | currency: 'BRL' }}
                </div>
                <div class="meta">sua parte</div>
              </div>
            </div>
          }
        </div>
      }

      <p style="margin-top:1.5rem"><a routerLink="/faturas">← Todas as faturas</a></p>
    } @else {
      <p class="vazio">Carregando…</p>
    }
  `,
})
export class MinhasContasComponent {
  private api = inject(ApiService);
  private toast = inject(ToastService);

  dados = signal<MinhasContas | null>(null);
  pessoas = signal<Pessoa[]>([]);
  ocupado = signal(false);
  copiado = signal(false);

  /** Lançamento com o editor de divisão aberto. */
  dividindo = signal<Lancamento | null>(null);
  linhas = signal<LinhaDivisao[]>([]);

  pagoEm = new Date().toISOString().slice(0, 10);
  observacao = '';
  arquivo = signal<File | null>(null);

  @Input() set id(valor: string) {
    this.carregar(Number(valor));
  }

  aberta(): boolean {
    return this.dados()?.fatura.status === 'EM_AVALIACAO';
  }

  /** O aceite e o pagamento só existem com o rateio congelado pela conciliação. */
  podePagar(): boolean {
    const s = this.dados()?.fatura.status;
    return s === 'CONCILIADA' || s === 'FECHADA';
  }

  souResponsavel(l: Lancamento): boolean {
    return l.meu && l.responsavelId !== null;
  }

  // ------------------------------------------------------- reivindicação --

  assumir(l: Lancamento): void {
    this.ocupado.set(true);
    this.api.reivindicar(l.id).subscribe({
      next: (atualizado) => {
        // Sem responsável na volta = outra pessoa também reivindicou; o backend
        // devolveu ao pool e chamou o admin. É importante a pessoa saber disso.
        this.toast.ok(atualizado.responsavelId
          ? 'Lançamento assumido.'
          : 'Outra pessoa também marcou essa compra. O administrador vai decidir.');
        this.recarregar();
      },
      error: () => this.ocupado.set(false),
    });
  }

  devolver(l: Lancamento): void {
    this.ocupado.set(true);
    this.api.desistir(l.id).subscribe({
      next: () => { this.toast.ok('Lançamento devolvido ao pool.'); this.recarregar(); },
      error: () => this.ocupado.set(false),
    });
  }

  // ------------------------------------------------------------- divisão --

  abrirDivisao(l: Lancamento): void {
    this.dividindo.set(l);
    const jaTem = new Map(l.divisao.map((p) => [p.usuarioId, p.valor]));
    this.linhas.set(this.pessoas().map((p) => ({
      usuarioId: p.id,
      nome: p.nome,
      // Sem divisão ainda: quem assumiu já entra marcado, que é o caso comum.
      marcado: jaTem.has(p.id) || (jaTem.size === 0 && p.id === l.responsavelId),
      valor: jaTem.get(p.id) ?? null,
    })));
  }

  fecharDivisao(): void {
    this.dividindo.set(null);
    this.linhas.set([]);
  }

  alternar(linha: LinhaDivisao): void {
    this.linhas.update((ls) => ls.map((l) => l.usuarioId === linha.usuarioId
      ? { ...l, marcado: !l.marcado, valor: l.marcado ? null : l.valor }
      : l));
  }

  mudarValor(linha: LinhaDivisao, valor: number | null): void {
    this.linhas.update((ls) => ls.map((l) => l.usuarioId === linha.usuarioId
      ? { ...l, valor } : l));
  }

  /** Partes iguais, com os centavos de sobra no primeiro — assim a soma fecha. */
  igualmente(l: Lancamento): void {
    const marcados = this.linhas().filter((x) => x.marcado);
    if (marcados.length === 0) {
      return;
    }
    const centavos = Math.round(l.valor * 100);
    const base = Math.trunc(centavos / marcados.length);
    const sobra = centavos - base * marcados.length;
    let i = 0;
    this.linhas.update((ls) => ls.map((x) => {
      if (!x.marcado) {
        return { ...x, valor: null };
      }
      const valor = (i === 0 ? base + sobra : base) / 100;
      i += 1;
      return { ...x, valor };
    }));
  }

  marcadas(): number {
    return this.linhas().filter((l) => l.marcado).length;
  }

  somaPartes(): number {
    const centavos = this.linhas()
      .filter((l) => l.marcado)
      .reduce((s, l) => s + Math.round((l.valor ?? 0) * 100), 0);
    return centavos / 100;
  }

  diferenca(l: Lancamento): number {
    return Math.round((l.valor - this.somaPartes()) * 100) / 100;
  }

  fecha(l: Lancamento): boolean {
    return this.diferenca(l) === 0;
  }

  abs(n: number): number {
    return Math.abs(n);
  }

  salvarDivisao(l: Lancamento): void {
    const partes = this.linhas()
      .filter((x) => x.marcado && x.valor !== null)
      .map((x) => ({ usuarioId: x.usuarioId, valor: x.valor as number }));
    this.ocupado.set(true);
    this.api.dividir(l.id, partes).subscribe({
      next: () => {
        this.toast.ok('Conta dividida.');
        this.fecharDivisao();
        this.recarregar();
      },
      error: () => this.ocupado.set(false),
    });
  }

  juntar(l: Lancamento): void {
    this.ocupado.set(true);
    this.api.juntarDivisao(l.id).subscribe({
      next: () => { this.toast.ok('Divisão desfeita.'); this.recarregar(); },
      error: () => this.ocupado.set(false),
    });
  }

  // ----------------------------------------------------- aceite/pagamento --

  aceitar(): void {
    const fatura = this.dados()?.fatura;
    if (!fatura) {
      return;
    }
    this.ocupado.set(true);
    this.api.aceitarValor(fatura.id).subscribe({
      next: () => {
        this.toast.ok('Valor aceito. Agora é só pagar e anexar o comprovante.');
        this.recarregar();
      },
      error: () => this.ocupado.set(false),
    });
  }

  escolherArquivo(evento: Event): void {
    const input = evento.target as HTMLInputElement;
    this.arquivo.set(input.files?.[0] ?? null);
  }

  pagar(): void {
    const fatura = this.dados()?.fatura;
    const comprovante = this.arquivo();
    if (!fatura || !comprovante) {
      return;
    }
    this.ocupado.set(true);
    this.api.informarPagamento(fatura.id, comprovante, this.pagoEm, this.observacao).subscribe({
      next: () => {
        this.toast.ok('Pagamento declarado. O administrador vai conferir o comprovante.');
        this.arquivo.set(null);
        this.observacao = '';
        this.recarregar();
      },
      error: () => this.ocupado.set(false),
    });
  }

  copiarChave(chave: string): void {
    // O clipboard assíncrono não existe em contexto inseguro (http) nem em
    // WebView antiga; o textarea escondido cobre esses casos.
    const feito = () => {
      this.copiado.set(true);
      setTimeout(() => this.copiado.set(false), 2000);
    };
    if (navigator.clipboard?.writeText) {
      navigator.clipboard.writeText(chave).then(feito, () => this.copiarNaMarra(chave, feito));
    } else {
      this.copiarNaMarra(chave, feito);
    }
  }

  private copiarNaMarra(texto: string, feito: () => void): void {
    const area = document.createElement('textarea');
    area.value = texto;
    area.setAttribute('readonly', '');
    area.style.position = 'fixed';
    area.style.opacity = '0';
    document.body.appendChild(area);
    area.select();
    try {
      document.execCommand('copy');
      feito();
    } catch {
      this.toast.erro('Não consegui copiar. Selecione a chave e copie à mão.');
    } finally {
      document.body.removeChild(area);
    }
  }

  // --------------------------------------------------------------- apoio --

  limpa(l: Lancamento): string {
    return descricaoSemParcela(l);
  }

  rotuloOrigem(l: Lancamento): string {
    return {
      MANUAL: 'você assumiu',
      HERDADA_PARCELA: 'herdada da 1ª parcela',
      REGRA_CARTAO: 'do seu cartão',
      ADMIN: 'definida pelo admin',
    }[l.origemAtribuicao ?? 'MANUAL'];
  }

  rotuloAcerto(s: string): string {
    return {
      ABERTO: 'a conferir',
      ACEITO: 'aceito · falta pagar',
      INFORMADO: 'aguardando confirmação',
      CONFIRMADO: 'pago',
    }[s] ?? s;
  }

  private recarregar(): void {
    const id = this.dados()?.fatura.id;
    if (id) {
      this.carregar(id);
    }
  }

  private carregar(id: number): void {
    forkJoin({
      contas: this.api.minhasContas(id),
      pessoas: this.api.pessoas(),
    }).subscribe({
      next: (r) => {
        this.dados.set(r.contas);
        this.pessoas.set(r.pessoas);
        this.ocupado.set(false);
      },
      error: () => this.ocupado.set(false),
    });
  }
}

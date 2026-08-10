import { CurrencyPipe, DatePipe, LowerCasePipe } from '@angular/common';
import { Component, Input, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { ApiService } from '../core/api.service';
import { ToastService } from '../core/toast.service';
import {
  Acerto, Lancamento, MinhasContas, Pagamento, Pessoa,
  descricaoSemParcela, nomeDoLancamento,
} from '../core/models';

/** Uma linha do editor de divisão: pessoa marcada e quanto ela paga. */
interface LinhaDivisao {
  usuarioId: number;
  nome: string;
  marcado: boolean;
  valor: number | null;
}

/**
 * Um bloco do pool: um dia ou um estabelecimento, com subtotal.
 *
 * <p>Numa fatura real são 514 lançamentos. Lista contínua no celular é
 * impraticável — o subtotal por bloco é o que deixa a pessoa conferir "esse dia
 * inteiro foi meu" sem somar de cabeça.
 */
interface Grupo {
  chave: string;
  /** Preenchido no agrupamento por dia; a tela formata com o pipe de data. */
  data: string | null;
  titulo: string;
  itens: Lancamento[];
  subtotal: number;
}

/**
 * A tela principal do utilizador numa fatura, e onde o ciclo inteiro acontece:
 * assumir o que é seu, rachar o que foi dividido, ver a fatia dos encargos,
 * aceitar o total e declarar o pagamento.
 *
 * <p>Mostra só o <b>pool</b> e o que é dele. O que outra pessoa assumiu não
 * aparece — decisão de privacidade do projeto. A exceção é a conta dividida:
 * os participantes se veem, porque estavam na mesma mesa.
 *
 * <p>O pool tem busca, faixa de data e agrupamento porque é onde o app é usado
 * de verdade: no celular, no fim do mês, numa lista longa de nomes parecidos.
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

            @if (precisaAceitar(a) && podePagar()) {
              <button type="button" [disabled]="ocupado()" (click)="aceitar()">
                {{ a.valorPago > 0 ? 'Conferi e aceito o novo valor' : 'Conferi e aceito o valor' }}
              </button>
            }
          </div>

          @if (a.status === 'ABERTO' && !podePagar()) {
            <p class="meta">
              O aceite abre quando o administrador conciliar a fatura — só aí o
              valor para de mudar.
            </p>
          }

          <!-- ------------------------------------ o que já foi pago e o saldo -- -->
          @if (a.pagamentos.length > 0) {
            <div class="quitacao">
              <div class="linha">
                <span>Já pago</span>
                <strong>{{ a.valorPago | currency: 'BRL' }}</strong>
              </div>
              <div class="linha" [class.pendente]="a.saldo > 0">
                <span>{{ a.saldo > 0 ? 'Ainda falta' : 'Saldo' }}</span>
                <strong>{{ a.saldo | currency: 'BRL' }}</strong>
              </div>
              <ul class="pagamentos">
                @for (p of a.pagamentos; track p.id) {
                  <li>
                    <span>{{ p.pagoEm | date: 'dd/MM/yyyy' }}</span>
                    <strong>{{ p.valor | currency: 'BRL' }}</strong>
                    @if (p.confirmadoEm) {
                      <span class="tag ok">recebido</span>
                    } @else {
                      <span class="tag alerta">a conferir</span>
                    }
                    @if (p.temComprovante) {
                      <button type="button" class="btn-texto" (click)="verComprovante(p)">
                        comprovante
                      </button>
                    }
                    @if (p.observacao) {
                      <div class="meta">{{ p.observacao }}</div>
                    }
                  </li>
                }
              </ul>
              @if (a.saldo > 0 && a.status !== 'CONFIRMADO') {
                <p class="meta">
                  O seu total mudou depois do primeiro pagamento. Confira o valor de
                  novo e mande só a diferença — o que você já pagou continua
                  registrado, com o comprovante.
                </p>
              }
            </div>
          }

          <!-- ------------------------------------------ declarar pagamento -- -->
          @if (podeDeclararPagamento(a)) {
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

            <form (ngSubmit)="pagar(a)">
              <label for="valorPago">Quanto você pagou</label>
              <input id="valorPago" type="text" inputmode="decimal" name="valorPago"
                     [(ngModel)]="valorPago"
                     [placeholder]="'em branco = tudo que falta (' + (a.saldo | currency: 'BRL') + ')'">
              <p class="meta">
                Deixe em branco se pagou o total. Preencha quando mandar só uma parte
                — o saldo continua aberto e você declara o resto depois, com o
                comprovante daquela transferência.
              </p>

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
                {{ a.valorPago > 0 ? 'Declarar pagamento complementar' : 'Declarar pagamento' }}
              </button>
            </form>
          }

          @if (a.status === 'INFORMADO' && a.saldo <= 0) {
            <p class="meta">
              Pagamento declarado com comprovante anexado. Aguardando a confirmação
              do administrador.
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
        <div class="filtros">
          <label class="campo-busca" for="busca">
            Procurar
            <input id="busca" type="search" name="busca" [ngModel]="busca()"
                   (ngModelChange)="busca.set($event)"
                   placeholder="parte do nome da loja">
          </label>
          <label for="de">
            De
            <input id="de" type="date" name="de" [ngModel]="de()" (ngModelChange)="de.set($event)">
          </label>
          <label for="ate">
            Até
            <input id="ate" type="date" name="ate" [ngModel]="ate()"
                   (ngModelChange)="ate.set($event)">
          </label>
          <label for="agrupar">
            Agrupar
            <select id="agrupar" name="agrupar" [ngModel]="agrupamento()"
                    (ngModelChange)="agrupamento.set($event)">
              <option value="dia">por dia</option>
              <option value="loja">por estabelecimento</option>
            </select>
          </label>
        </div>

        <div class="filtros-rapidos">
          <label>
            <input type="checkbox" name="soParcelas" [ngModel]="soParcelas()"
                   (ngModelChange)="soParcelas.set($event)">
            só parcelas
          </label>
          <label>
            <input type="checkbox" name="soConhecidos" [ngModel]="soConhecidos()"
                   (ngModelChange)="soConhecidos.set($event)">
            só onde já comprei
          </label>
          @if (filtrando()) {
            <button type="button" class="btn-texto" (click)="limparFiltros()">
              limpar filtros
            </button>
          }
        </div>

        @if (poolFiltrado().length === 0) {
          <p class="vazio">Nenhum lançamento sem dono bate com esses filtros.</p>
        } @else {
          <p class="sub">
            {{ poolFiltrado().length }} de {{ d.pool.length }} ·
            {{ somaFiltrada() | currency: 'BRL' }}
          </p>

          @for (g of grupos(); track g.chave) {
            <div class="cartao">
              <div class="grupo-cabecalho">
                <strong>
                  @if (g.data) {
                    {{ g.data | date: 'dd/MM/yyyy' }}
                  } @else {
                    {{ g.titulo }}
                    @if (g.itens.length > 1) {
                      <span class="tag">{{ g.itens.length }} compras</span>
                    }
                  }
                </strong>
                <span class="valor">{{ g.subtotal | currency: 'BRL' }}</span>
              </div>

              @for (l of g.itens; track l.id) {
                <div class="lancamento">
                  <div>
                    <div class="desc">
                      {{ nome(l) }}
                      @if (l.jaFoiSeu) {
                        <span class="tag info" title="Você assumiu uma compra nesta loja em outra fatura">
                          já foi sua
                        </span>
                      }
                    </div>
                    @if (l.apelido) {
                      <div class="meta original">na fatura: {{ original(l) }}</div>
                    }
                    <div class="meta">
                      {{ l.dataCompra | date: 'dd/MM' }}
                      @if (l.portadorNome) { · {{ l.portadorNome }} }
                      @if (l.final4) { · final {{ l.final4 }} }
                    </div>
                    @if (l.parcelaTotal) {
                      <div class="meta">
                        <span class="tag alerta">
                          parcela {{ l.parcelaAtual }}/{{ l.parcelaTotal }}
                        </span>
                        @if (l.parcelaAtual === 1) {
                          Ao assumir, as {{ l.parcelaTotal! - 1 }} parcelas seguintes
                          já entram no seu nome nas próximas faturas.
                        }
                      </div>
                    }
                  </div>
                  <div style="text-align:right">
                    <div class="valor" [class.credito]="l.valor < 0">
                      {{ l.valor | currency: 'BRL' }}
                    </div>
                    <button type="button" [disabled]="ocupado() || !aberta()"
                            (click)="assumir(l)">Foi minha</button>
                    <button type="button" class="btn-texto" (click)="abrirApelido(l)">
                      {{ l.apelido ? 'renomear' : 'apelidar' }}
                    </button>
                  </div>
                </div>

                @if (apelidando()?.id === l.id) {
                  <div class="editor-apelido">
                    <label [attr.for]="'apelido' + l.id">
                      Como vocês chamam esse lugar?
                      <input [id]="'apelido' + l.id" type="text" maxlength="120"
                             [name]="'apelido' + l.id" [(ngModel)]="novoApelido"
                             placeholder="ex.: Uber">
                    </label>
                    <p class="meta">
                      Na fatura vem como <strong>{{ original(l) }}</strong>. O apelido vale
                      para todo mundo e para os próximos meses — é a mesma loja.
                    </p>
                    <div class="linha">
                      <button type="button" [disabled]="ocupado() || !novoApelido.trim()"
                              (click)="salvarApelido(l)">Salvar apelido</button>
                      @if (l.apelido) {
                        <button type="button" class="btn-secundario"
                                (click)="removerApelido(l)">Voltar ao nome do banco</button>
                      }
                      <button type="button" class="btn-secundario" (click)="fecharApelido()">
                        Cancelar
                      </button>
                    </div>
                  </div>
                }
              }
            </div>
          }
        }
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
                <div class="desc">{{ nome(l) }}</div>
                @if (l.apelido) {
                  <div class="meta original">na fatura: {{ original(l) }}</div>
                }
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
                  <strong>Dividir {{ nome(l) }} · {{ l.valor | currency: 'BRL' }}</strong>
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
                <div class="desc">{{ nome(l) }}</div>
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

  /** Lançamento com o editor de apelido aberto. */
  apelidando = signal<Lancamento | null>(null);
  novoApelido = '';

  // ------------------------------------------------------ filtros do pool --

  busca = signal('');
  de = signal('');
  ate = signal('');
  agrupamento = signal<'dia' | 'loja'>('dia');
  soParcelas = signal(false);
  soConhecidos = signal(false);

  pagoEm = new Date().toISOString().slice(0, 10);
  /** Em branco vale "paguei tudo que falta" — o caso comum. */
  valorPago = '';
  observacao = '';
  arquivo = signal<File | null>(null);

  @Input() set id(valor: string) {
    this.carregar(Number(valor));
  }

  /**
   * O pool depois dos filtros.
   *
   * <p>A busca casa contra o apelido <b>e</b> contra o que o banco imprime:
   * quem apelidou procura pelo apelido, quem tem o extrato na mão procura pelo
   * nome original.
   */
  poolFiltrado = computed<Lancamento[]>(() => {
    const termo = this.busca().trim().toLowerCase();
    const de = this.de();
    const ate = this.ate();
    return (this.dados()?.pool ?? []).filter((l) => {
      if (termo && !this.textoDe(l).includes(termo)) {
        return false;
      }
      if (de && l.dataCompra < de) {
        return false;
      }
      if (ate && l.dataCompra > ate) {
        return false;
      }
      if (this.soParcelas() && !l.parcelaTotal) {
        return false;
      }
      if (this.soConhecidos() && !l.jaFoiSeu) {
        return false;
      }
      return true;
    });
  });

  somaFiltrada = computed(() => this.soma(this.poolFiltrado()));

  filtrando = computed(() =>
    !!this.busca() || !!this.de() || !!this.ate() || this.soParcelas() || this.soConhecidos());

  /**
   * O pool em blocos: por dia (a ordem natural da fatura) ou por
   * estabelecimento, que junta as compras repetidas da mesma loja no mês.
   */
  grupos = computed<Grupo[]>(() => {
    const porLoja = this.agrupamento() === 'loja';
    const mapa = new Map<string, Lancamento[]>();
    for (const l of this.poolFiltrado()) {
      const chave = porLoja ? l.descricaoNormalizada : l.dataCompra;
      const itens = mapa.get(chave);
      if (itens) {
        itens.push(l);
      } else {
        mapa.set(chave, [l]);
      }
    }

    const grupos: Grupo[] = [...mapa.entries()].map(([chave, itens]) => ({
      chave,
      data: porLoja ? null : chave,
      titulo: porLoja ? this.nome(itens[0]) : chave,
      itens,
      subtotal: this.soma(itens),
    }));

    // Por loja, primeiro quem mais se repete: é onde agrupar rende mais.
    // Por dia, a ordem da fatura, que é como a pessoa lembra das compras.
    return porLoja
      ? grupos.sort((a, b) => b.itens.length - a.itens.length
          || a.titulo.localeCompare(b.titulo))
      : grupos.sort((a, b) => a.chave.localeCompare(b.chave));
  });

  limparFiltros(): void {
    this.busca.set('');
    this.de.set('');
    this.ate.set('');
    this.soParcelas.set(false);
    this.soConhecidos.set(false);
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
        // O desfazer no próprio aviso existe porque marcar a linha errada é o
        // engano mais fácil do app — e corrigir agora custa um toque, enquanto
        // corrigir depois da conciliação vira conversa com o administrador.
        this.toast.ok(
          atualizado.responsavelId
            ? `"${this.nome(l)}" agora é sua.`
            : 'Outra pessoa também marcou essa compra. O administrador vai decidir.',
          { texto: 'Desfazer', executar: () => this.devolver(l) });
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

  // ------------------------------------------------------------- apelido --

  abrirApelido(l: Lancamento): void {
    this.apelidando.set(l);
    this.novoApelido = l.apelido ?? '';
  }

  fecharApelido(): void {
    this.apelidando.set(null);
    this.novoApelido = '';
  }

  salvarApelido(l: Lancamento): void {
    const apelido = this.novoApelido.trim();
    if (!apelido) {
      return;
    }
    this.ocupado.set(true);
    this.api.apelidar(l.id, apelido).subscribe({
      next: () => {
        this.toast.ok(`Agora essa loja aparece como "${apelido}".`);
        this.fecharApelido();
        this.recarregar();
      },
      error: () => this.ocupado.set(false),
    });
  }

  removerApelido(l: Lancamento): void {
    this.ocupado.set(true);
    this.api.removerApelido(l.id).subscribe({
      next: () => {
        this.toast.ok('Apelido removido.');
        this.fecharApelido();
        this.recarregar();
      },
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

  /**
   * O aceite vale para um valor. Se ele mudou depois de a pessoa já ter pago, o
   * backend anula o aceite e ela concorda de novo antes de mandar a diferença —
   * por isso a checagem é o `aceitoEm`, e não o status.
   */
  precisaAceitar(a: Acerto): boolean {
    return a.status !== 'CONFIRMADO' && !a.aceitoEm && a.saldo > 0;
  }

  podeDeclararPagamento(a: Acerto): boolean {
    return a.status !== 'CONFIRMADO' && !!a.aceitoEm && a.saldo > 0;
  }

  pagar(a: Acerto): void {
    const fatura = this.dados()?.fatura;
    const comprovante = this.arquivo();
    if (!fatura || !comprovante) {
      return;
    }
    this.ocupado.set(true);
    this.api.informarPagamento(
      fatura.id, comprovante, this.valorPago, this.pagoEm, this.observacao).subscribe({
      next: (atualizado) => {
        this.toast.ok(atualizado.saldo > 0
          ? `Pagamento declarado. Ainda faltam ${this.emReais(atualizado.saldo)}.`
          : 'Pagamento declarado. O administrador vai conferir o comprovante.');
        this.arquivo.set(null);
        this.observacao = '';
        this.valorPago = '';
        this.recarregar();
      },
      error: () => this.ocupado.set(false),
    });
  }

  /** Abre o comprovante de uma transferência numa aba nova (o endpoint exige JWT). */
  verComprovante(p: Pagamento): void {
    this.api.comprovante(p.id).subscribe({
      next: (blob) => {
        const url = URL.createObjectURL(blob);
        window.open(url, '_blank');
        setTimeout(() => URL.revokeObjectURL(url), 60_000);
      },
      error: () => this.toast.erro('Não consegui abrir o comprovante.'),
    });
  }

  private emReais(valor: number): string {
    return valor.toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });
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

  /** O nome que a tela mostra: o apelido, quando a loja tem um. */
  nome(l: Lancamento): string {
    return nomeDoLancamento(l);
  }

  /** O que o banco imprimiu, para conferir contra o extrato. */
  original(l: Lancamento): string {
    return descricaoSemParcela(l);
  }

  private textoDe(l: Lancamento): string {
    return `${l.apelido ?? ''} ${l.descricao}`.toLowerCase();
  }

  private soma(ls: Lancamento[]): number {
    return ls.reduce((s, l) => s + Math.round(l.valor * 100), 0) / 100;
  }

  rotuloOrigem(l: Lancamento): string {
    return {
      MANUAL: 'você assumiu',
      HERDADA_PARCELA: 'herdada da 1ª parcela',
      REGRA_CARTAO: 'do seu cartão',
      ADMIN: 'definida pelo admin',
      SOBRA_CONCILIACAO: 'ninguém assumiu — ficou com o titular',
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

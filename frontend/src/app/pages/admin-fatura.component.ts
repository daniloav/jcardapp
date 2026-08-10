import { CurrencyPipe, DatePipe } from '@angular/common';
import { Component, Input, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { forkJoin } from 'rxjs';
import { ApiService } from '../core/api.service';
import { ToastService } from '../core/toast.service';
import {
  Acerto, DetalheFatura, Lancamento, Usuario, descricaoSemParcela, nomeDoLancamento,
} from '../core/models';

/** Conciliação: conflitos para arbitrar, fechamento e confirmação de pagamentos. */
@Component({
  standalone: true,
  imports: [FormsModule, CurrencyPipe, DatePipe],
  template: `
    @if (detalhe(); as d) {
      <h1>Conciliação · {{ d.fatura.competencia | date: 'MM/yyyy' }}</h1>

      <article class="cartao">
        <div class="linha">
          <div>
            <div>Total da fatura</div>
            <strong style="font-size:1.3rem">{{ d.fatura.valorTotal | currency: 'BRL' }}</strong>
          </div>
          <div>
            <div>Soma lida do PDF</div>
            <strong>{{ d.fatura.valorLancado | currency: 'BRL' }}</strong>
          </div>
          <div>
            <div>Diferença</div>
            <strong [style.color]="d.fatura.divergencia === 0 ? 'var(--ok)' : 'var(--erro)'">
              {{ d.fatura.divergencia | currency: 'BRL' }}
            </strong>
          </div>
          <span class="tag">{{ d.fatura.status }}</span>
        </div>
      </article>

      @if (d.fatura.status === 'DIVERGENTE') {
        <div class="aviso erro">
          <strong>A leitura não fecha com o total impresso.</strong>
          O parser perdeu ou duplicou linha. Corrija as regexes
          (<code>jcard.parser.itau.*</code>) e reprocesse — a fatura não avança assim,
          de propósito: ratear em cima de leitura errada cobraria valor errado de alguém.
          <div style="margin-top:0.5rem">
            <button type="button" (click)="reprocessar()">Reprocessar do texto guardado</button>
          </div>
        </div>
      }

      @if (conflitos().length > 0) {
        <h2>Conflitos para decidir ({{ conflitos().length }})</h2>
        <div class="cartao">
          @for (l of conflitos(); track l.id) {
            <div class="lancamento">
              <div>
                <div class="desc">{{ nome(l) }}</div>
                <div class="meta">
                  {{ l.dataCompra | date: 'dd/MM' }} · disputam:
                  {{ (l.disputantes ?? []).join(', ') }}
                </div>
              </div>
              <div style="text-align:right">
                <div class="valor">{{ l.valor | currency: 'BRL' }}</div>
                <select [ngModel]="escolha[l.id] ?? null"
                        (ngModelChange)="escolha[l.id] = $event"
                        [attr.aria-label]="'Quem fica com ' + l.descricao"
                        name="vencedor{{ l.id }}">
                  <option [ngValue]="null">Escolha quem fica…</option>
                  @for (u of utilizadores(); track u.id) {
                    <option [ngValue]="u.id">{{ u.nome }}</option>
                  }
                </select>
                <button type="button" [disabled]="!escolha[l.id]" (click)="arbitrar(l)">
                  Confirmar
                </button>
              </div>
            </div>
          }
        </div>
      }

      <h2>Acertos por pessoa</h2>
      @if (d.acertos.length === 0) {
        <p class="vazio">Ainda sem acertos calculados.</p>
      } @else {
        <div class="rolavel">
          <table>
            <caption class="sub">A soma reproduz o total da fatura.</caption>
            <thead>
              <tr><th>Pessoa</th><th class="num">Valor</th><th>Situação</th><th>Ação</th></tr>
            </thead>
            <tbody>
              @for (a of d.acertos; track a.id) {
                <tr>
                  <td>{{ a.usuarioNome }}</td>
                  <td class="num">{{ a.valorDevido | currency: 'BRL' }}</td>
                  <td>
                    <span class="tag" [class.ok]="a.status === 'CONFIRMADO'"
                          [class.alerta]="a.status === 'INFORMADO' || a.status === 'ACEITO'">
                      {{ rotuloAcerto(a.status) }}
                    </span>
                    @if (a.pagoEm) {
                      <div class="meta">pago em {{ a.pagoEm | date: 'dd/MM/yyyy' }}</div>
                    }
                    @if (a.observacao) {
                      <div class="meta">{{ a.observacao }}</div>
                    }
                  </td>
                  <td>
                    @if (a.temComprovante) {
                      <button type="button" class="btn-texto" (click)="verComprovante(a)">
                        Ver comprovante
                      </button>
                    }
                    @if (a.status !== 'CONFIRMADO') {
                      <button type="button" (click)="confirmar(a)">Recebi</button>
                      @if (!a.temComprovante) {
                        <div class="meta">
                          sem comprovante — a pessoa ainda não declarou o pagamento
                        </div>
                      }
                    } @else {
                      <button type="button" class="btn-secundario" (click)="reabrir(a)">
                        Reabrir
                      </button>
                    }
                  </td>
                </tr>
              }
            </tbody>
            <tfoot>
              <tr>
                <th>Total</th>
                <th class="num">{{ somaAcertos() | currency: 'BRL' }}</th>
                <th colspan="2">
                  @if (somaAcertos() === d.fatura.valorTotal) {
                    <span class="tag ok">bate com a fatura</span>
                  } @else {
                    <span class="tag erro">não bate</span>
                  }
                </th>
              </tr>
            </tfoot>
          </table>
        </div>
      }

      <h2>Fechamento</h2>
      <div class="cartao">
        <p class="meta">
          Conciliar atribui ao titular tudo que ficou sem dono e congela o rateio.
          Fechar exige que todos os pagamentos estejam confirmados.
        </p>
        <div class="linha">
          <button type="button"
                  [disabled]="d.fatura.status !== 'EM_AVALIACAO' || conflitos().length > 0"
                  (click)="conciliar()">
            Conciliar ({{ d.fatura.noPool }} sem dono → titular)
          </button>
          <button type="button" class="btn-secundario"
                  [disabled]="d.fatura.status !== 'CONCILIADA'" (click)="fechar()">
            Fechar fatura
          </button>
        </div>
        @if (conflitos().length > 0) {
          <p class="meta" style="color:var(--erro)">
            Resolva os conflitos antes de conciliar.
          </p>
        }
      </div>

      <!-- ------------------------------------------- voltar para avaliação -- -->
      @if (d.fatura.status === 'CONCILIADA') {
        <h2>Voltar para avaliação</h2>
        <div class="cartao">
          <p class="meta">
            Para quando alguém marcou o lançamento errado e só percebeu depois da
            conciliação. Os lançamentos que ficaram com o titular por falta de dono
            voltam para a lista de quem não tem dono, os aceites são anulados e todo
            mundo que tem acerto nesta fatura recebe e-mail. O que você arbitrou
            continua onde está, e quem já declarou o pagamento segue como está.
          </p>
          <label for="motivo">
            Motivo (vai no e-mail e na auditoria)
            <input id="motivo" type="text" maxlength="400" name="motivo"
                   [(ngModel)]="motivo"
                   placeholder="ex.: a farmácia era da Maria, não do João">
          </label>
          <button type="button" class="btn-perigo" [disabled]="reabrindo()"
                  (click)="reabrirAvaliacao()">Voltar para avaliação</button>
        </div>
      }

      @if (d.fatura.status !== 'FECHADA') {
        <h2>Excluir</h2>
        <div class="cartao">
          <p class="meta">
            Para o arquivo errado ou a competência trocada: como o app rejeita
            reimportar a mesma fatura (o hash é único), a saída é apagar e subir
            de novo. Somem os lançamentos, o que as pessoas assumiram e os acertos.
          </p>
          <button type="button" class="btn-perigo" [disabled]="excluindo()"
                  (click)="excluir(d.fatura)">Excluir esta fatura</button>
        </div>
      }

      <h2>Todos os lançamentos ({{ d.lancamentos.length }})</h2>
      <p class="sub">
        Em azul, o que já tem dono — alguém assumiu ou você atribuiu. O lápis troca
        o dono; nos que estão sem, ele diz de quem é. A pessoa recebe e-mail e pode
        devolver ao pool com "não foi minha" enquanto a fatura estiver aberta —
        atribuir sem aviso seria cobrança silenciosa.
      </p>
      <label class="campo-busca" for="filtro-lancamentos">
        Procurar
        <input id="filtro-lancamentos" type="search" name="filtro"
               [ngModel]="filtro()" (ngModelChange)="filtro.set($event)"
               placeholder="parte do nome ou da pessoa">
      </label>

      <!-- "Isso tudo é da Maria": a busca por 'UBER' devolve 40 linhas, e mandar
           as 40 de uma vez é a mesma decisão que clicar 40 lápis — só sem a
           chance de errar uma delas. Só aparece com filtro: sem ele o botão
           levaria a fatura inteira para uma pessoa com um clique. -->
      @if (filtro().trim() && atribuiveis().length > 0) {
        <div class="cartao em-massa">
          <div>
            <strong>
              Atribuir os {{ atribuiveis().length }} resultados a uma pessoa
            </strong>
            <div class="meta">
              {{ somaAtribuiveis() | currency: 'BRL' }} no total.
              @if (jaTemDono() > 0) {
                {{ jaTemDono() }} já tem dono e vai trocar de mãos.
              }
              @if (encargosNoResultado() > 0) {
                {{ encargosNoResultado() }} encargo(s) do resultado ficam de fora — são
                rateados entre todos que usaram o cartão.
              }
              Todo mundo que receber é avisado por e-mail e pode devolver ao pool.
            </div>
          </div>
          <div class="atribuir">
            <select [ngModel]="donoEmMassa()" (ngModelChange)="donoEmMassa.set($event)"
                    aria-label="Atribuir o resultado da busca a" name="donoEmMassa">
              <option [ngValue]="null">Foi de quem?</option>
              @for (u of utilizadores(); track u.id) {
                <option [ngValue]="u.id">{{ u.nome }}</option>
              }
            </select>
            <button type="button" [disabled]="!donoEmMassa() || atribuindoEmMassa()"
                    (click)="atribuirEmMassa()">
              Atribuir {{ atribuiveis().length }}
            </button>
          </div>
        </div>
      }

      <div class="rolavel">
        <table>
          <thead>
            <tr><th>Data</th><th>Descrição</th><th>Cartão</th>
                <th>Responsável</th><th class="num">Valor</th></tr>
          </thead>
          <tbody>
            @for (l of lancamentosFiltrados(); track l.id) {
              <tr [class.com-dono]="temDono(l)">
                <td>{{ l.dataCompra | date: 'dd/MM' }}</td>
                <td>
                  {{ nome(l) }}
                  @if (l.parcelaTotal) {
                    <span class="tag">{{ l.parcelaAtual }}/{{ l.parcelaTotal }}</span>
                  }
                  @if (l.apelido) {
                    <div class="meta original">na fatura: {{ limpa(l) }}</div>
                  }
                </td>
                <td>{{ l.final4 ?? '—' }}</td>
                <td>
                  @if (l.divisao.length > 0) {
                    <span class="tag info">dividida</span>
                    <ul class="partes">
                      @for (p of l.divisao; track p.usuarioId) {
                        <li>{{ p.usuarioNome }} — {{ p.valor | currency: 'BRL' }}</li>
                      }
                    </ul>
                  } @else if (rateado(l)) {
                    <span class="tag">rateado entre quem usou</span>
                  } @else {
                    {{ l.responsavelNome ?? '—' }}
                    @if (l.origemAtribuicao === 'HERDADA_PARCELA') {
                      <span class="tag info">herdada</span>
                    }
                    @if (l.origemAtribuicao === 'ADMIN') {
                      <span class="tag info">você atribuiu</span>
                    }
                    @if (l.origemAtribuicao === 'SOBRA_CONCILIACAO') {
                      <span class="tag">ninguém assumiu</span>
                    }

                    <!-- O seletor fica atrás do lápis: com 514 linhas, um <select>
                         por linha vira uma parede de caixas e some com a leitura
                         de quem já tem dono. -->
                    @if (podeAtribuir(l) && editando() !== l.id) {
                      <button type="button" class="btn-lapis" (click)="editar(l)"
                              [title]="l.responsavelNome ? 'Trocar o dono' : 'Dizer de quem é'"
                              [attr.aria-label]="(l.responsavelNome ? 'Trocar o dono de ' : 'Dizer de quem é ') + nome(l)">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor"
                             stroke-width="2" stroke-linecap="round"
                             stroke-linejoin="round" aria-hidden="true">
                          <path d="M12 20h9" />
                          <path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z" />
                        </svg>
                      </button>
                    }

                    @if (podeAtribuir(l) && editando() === l.id) {
                      <div class="atribuir">
                        <select [ngModel]="escolha[l.id] ?? null"
                                (ngModelChange)="escolha[l.id] = $event"
                                [attr.aria-label]="'Atribuir ' + l.descricao + ' a'"
                                name="dono{{ l.id }}">
                          <option [ngValue]="null">
                            {{ l.responsavelNome ? 'Passar para…' : 'Foi de quem?' }}
                          </option>
                          @for (u of utilizadores(); track u.id) {
                            <option [ngValue]="u.id">{{ u.nome }}</option>
                          }
                        </select>
                        <button type="button" class="btn-secundario"
                                [disabled]="!escolha[l.id]" (click)="atribuir(l)">
                          Atribuir
                        </button>
                        <button type="button" class="btn-texto" (click)="cancelarEdicao()">
                          Cancelar
                        </button>
                      </div>
                    }
                  }
                </td>
                <td class="num">{{ l.valor | currency: 'BRL' }}</td>
              </tr>
            }
          </tbody>
        </table>
      </div>
    } @else {
      <p class="vazio">Carregando…</p>
    }
  `,
})
export class AdminFaturaComponent {
  private api = inject(ApiService);
  private toast = inject(ToastService);
  private router = inject(Router);

  detalhe = signal<DetalheFatura | null>(null);
  conflitos = signal<Lancamento[]>([]);
  utilizadores = signal<Usuario[]>([]);
  excluindo = signal(false);
  reabrindo = signal(false);
  filtro = signal('');
  /** O lançamento com o seletor de dono aberto — um de cada vez. */
  editando = signal<number | null>(null);
  escolha: Record<number, number | null> = {};
  /** Para quem vai o resultado inteiro da busca. */
  donoEmMassa = signal<number | null>(null);
  atribuindoEmMassa = signal(false);
  motivo = '';

  private faturaId = 0;

  @Input() set id(valor: string) {
    this.faturaId = Number(valor);
    this.carregar();
  }

  /** A busca é sobre a lista inteira: 514 linhas não se percorrem com o olho. */
  lancamentosFiltrados = computed<Lancamento[]>(() => {
    const termo = this.filtro().trim().toLowerCase();
    const todos = this.detalhe()?.lancamentos ?? [];
    if (!termo) {
      return todos;
    }
    return todos.filter((l) =>
      `${l.apelido ?? ''} ${l.descricao} ${l.responsavelNome ?? ''}`
        .toLowerCase().includes(termo));
  });

  /** Do resultado da busca, o que a atribuição em massa realmente leva. */
  atribuiveis = computed<Lancamento[]>(() =>
    this.lancamentosFiltrados().filter((l) => this.podeAtribuir(l)));

  somaAtribuiveis = computed<number>(() =>
    this.atribuiveis().reduce((s, l) => s + l.valor, 0));

  /** Quantos do resultado já são de alguém — vão trocar de dono, não ganhar um. */
  jaTemDono = computed<number>(() =>
    this.atribuiveis().filter((l) => this.temDono(l)).length);

  /** Encargos que caíram na busca: não se atribuem, então o lote os pula. */
  encargosNoResultado = computed<number>(() =>
    this.lancamentosFiltrados().filter((l) => this.rateado(l)).length);

  somaAcertos(): number {
    return (this.detalhe()?.acertos ?? []).reduce((s, a) => s + a.valorDevido, 0);
  }

  limpa(l: Lancamento): string {
    return descricaoSemParcela(l);
  }

  nome(l: Lancamento): string {
    return nomeDoLancamento(l);
  }

  /**
   * Encargo não se atribui (é de todo mundo que usou), e fora da avaliação o
   * backend recusa — é a mesma regra que impede mexer numa fatura conciliada.
   */
  podeAtribuir(l: Lancamento): boolean {
    return this.detalhe()?.fatura.status === 'EM_AVALIACAO' && !this.rateado(l);
  }

  /**
   * Lançamento que já tem dono de verdade — alguém assumiu, o admin atribuiu, a
   * parcela herdou ou a conta foi rachada. Fica de fora a `SOBRA_CONCILIACAO`:
   * ela está no titular justamente porque ninguém assumiu, e pintá-la igual diria
   * o contrário. Encargo também não entra, que é de todo mundo que usou o cartão.
   */
  temDono(l: Lancamento): boolean {
    if (this.rateado(l)) {
      return false;
    }
    return l.divisao.length > 0
      || (l.responsavelId !== null && l.origemAtribuicao !== 'SOBRA_CONCILIACAO');
  }

  editar(l: Lancamento): void {
    this.escolha[l.id] = null;
    this.editando.set(l.id);
  }

  cancelarEdicao(): void {
    const aberto = this.editando();
    if (aberto !== null) {
      this.escolha[aberto] = null;
    }
    this.editando.set(null);
  }

  rotuloAcerto(s: string): string {
    return {
      ABERTO: 'a conferir',
      ACEITO: 'aceitou o valor',
      INFORMADO: 'informou que pagou',
      CONFIRMADO: 'recebido',
    }[s] ?? s;
  }

  /** Encargo não tem dono: é dividido entre todos que usaram o cartão no mês. */
  rateado(l: Lancamento): boolean {
    return l.tipo !== 'COMPRA' && l.tipo !== 'ESTORNO' && l.tipo !== 'PAGAMENTO';
  }

  /**
   * Abre o comprovante numa aba nova. Vai como blob porque o endpoint exige o
   * JWT — um link direto voltaria 401.
   */
  verComprovante(a: Acerto): void {
    this.api.comprovante(a.id).subscribe({
      next: (blob) => {
        const url = URL.createObjectURL(blob);
        window.open(url, '_blank');
        // Revoga depois de a aba ter carregado; imediato cancelaria o download.
        setTimeout(() => URL.revokeObjectURL(url), 60_000);
      },
      error: () => this.toast.erro('Não consegui abrir o comprovante.'),
    });
  }

  excluir(f: DetalheFatura['fatura']): void {
    const ok = confirm(
      'Excluir esta fatura?\n\n'
      + `Somem os ${f.totalLancamentos} lançamentos, tudo que as pessoas já assumiram `
      + 'e os acertos calculados. Não dá para desfazer.');
    if (!ok) {
      return;
    }
    this.excluindo.set(true);
    this.api.excluirFatura(f.id).subscribe({
      next: () => { this.toast.ok('Fatura excluída.'); this.router.navigate(['/faturas']); },
      error: () => this.excluindo.set(false),
    });
  }

  arbitrar(l: Lancamento): void {
    const vencedor = this.escolha[l.id];
    if (!vencedor) {
      return;
    }
    this.api.arbitrar(l.id, vencedor).subscribe({
      next: () => { this.toast.ok('Conflito resolvido.'); this.carregar(); },
    });
  }

  /**
   * Atribuir um lançamento que ninguém reivindicou. Usa o mesmo `arbitrar` do
   * conflito — a diferença é só não haver disputa —, e a pessoa é avisada por
   * e-mail de que a conta entrou no nome dela.
   */
  atribuir(l: Lancamento): void {
    const dono = this.escolha[l.id];
    if (!dono) {
      return;
    }
    const nome = this.utilizadores().find((u) => u.id === dono)?.nome ?? 'a pessoa';
    this.api.arbitrar(l.id, dono).subscribe({
      next: () => {
        this.escolha[l.id] = null;
        this.editando.set(null);
        this.toast.ok(`Lançamento atribuído a ${nome} — avisamos por e-mail.`);
        this.carregar();
      },
    });
  }

  /**
   * Manda o resultado inteiro da busca para uma pessoa.
   *
   * <p>Confirma antes com o número, o total e o que a operação desfaz: é uma
   * decisão sobre dinheiro de outras pessoas multiplicada por quarenta, e o
   * filtro pode ter trazido linha que o admin não esperava. Depois de confirmar,
   * ainda dá para desfazer uma a uma — o lápis continua ali e quem recebeu pode
   * responder "não foi minha".
   */
  atribuirEmMassa(): void {
    const dono = this.donoEmMassa();
    const alvos = this.atribuiveis();
    if (!dono || alvos.length === 0) {
      return;
    }
    const nome = this.utilizadores().find((u) => u.id === dono)?.nome ?? 'a pessoa';
    const total = this.somaAtribuiveis().toLocaleString('pt-BR',
      { style: 'currency', currency: 'BRL' });
    const trocam = this.jaTemDono();
    const divididos = alvos.filter((l) => l.divisao.length > 0).length;

    const ok = confirm(
      `Atribuir ${alvos.length} lançamento(s) a ${nome}?\n\n`
      + `Somam ${total}.\n`
      + (trocam > 0 ? `${trocam} já tem dono e vai passar para ${nome}.\n` : '')
      + (divididos > 0 ? `${divididos} está(ão) com a conta dividida — a divisão é desfeita.\n` : '')
      + `${nome} recebe um e-mail com a lista e pode devolver ao pool o que não for dele(a).`);
    if (!ok) {
      return;
    }

    this.atribuindoEmMassa.set(true);
    this.api.arbitrarLote(alvos.map((l) => l.id), dono).subscribe({
      next: (r) => {
        const pulados = r.jaEram + r.encargos;
        this.toast.ok(
          `${r.atribuidos} lançamento(s) para ${r.usuarioNome} — avisamos por e-mail.`
          + (pulados > 0 ? ` ${pulados} ficaram como estavam.` : ''));
        this.donoEmMassa.set(null);
        this.atribuindoEmMassa.set(false);
        this.carregar();
      },
      error: () => this.atribuindoEmMassa.set(false),
    });
  }

  /**
   * Devolve a fatura para avaliação. Confirma antes: mexe em valores que as
   * pessoas já conferiram, e para algumas o total vai mudar.
   */
  reabrirAvaliacao(): void {
    const ok = confirm(
      'Voltar esta fatura para avaliação?\n\n'
      + 'Os aceites são anulados, o que ficou com o titular por falta de dono volta '
      + 'ao pool e todo mundo com acerto nesta fatura recebe e-mail.');
    if (!ok) {
      return;
    }
    this.reabrindo.set(true);
    this.api.reabrirAvaliacao(this.faturaId, this.motivo).subscribe({
      next: () => {
        this.toast.ok('Fatura de volta em avaliação. As pessoas foram avisadas.');
        this.motivo = '';
        this.reabrindo.set(false);
        this.carregar();
      },
      error: () => this.reabrindo.set(false),
    });
  }

  conciliar(): void {
    this.api.conciliar(this.faturaId).subscribe({
      next: () => { this.toast.ok('Fatura conciliada. As contas fecham com o total.'); this.carregar(); },
    });
  }

  fechar(): void {
    this.api.fechar(this.faturaId).subscribe({
      next: () => { this.toast.ok('Fatura fechada.'); this.carregar(); },
    });
  }

  reprocessar(): void {
    this.api.reprocessarFatura(this.faturaId).subscribe({
      next: () => { this.toast.ok('Fatura reprocessada.'); this.carregar(); },
    });
  }

  confirmar(a: Acerto): void {
    this.api.confirmarPagamento(a.id).subscribe({
      next: () => { this.toast.ok(`Pagamento de ${a.usuarioNome} confirmado.`); this.carregar(); },
    });
  }

  reabrir(a: Acerto): void {
    this.api.reabrirAcerto(a.id).subscribe({
      next: () => { this.toast.ok('Acerto reaberto.'); this.carregar(); },
    });
  }

  private carregar(): void {
    forkJoin({
      detalhe: this.api.fatura(this.faturaId),
      conflitos: this.api.conflitos(this.faturaId),
      utilizadores: this.api.utilizadores(),
    }).subscribe({
      next: (r) => {
        this.detalhe.set(r.detalhe);
        this.conflitos.set(r.conflitos);
        this.utilizadores.set(r.utilizadores);
      },
    });
  }
}

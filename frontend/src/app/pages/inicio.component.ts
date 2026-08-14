import { CurrencyPipe, DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService } from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { Fatura, PreviaDoMes } from '../core/models';

/** Em que grupo a fatura entra no gráfico. */
type Grupo = 'fechada' | 'andamento' | 'divergente';

/** Uma barra do gráfico: um mês, com a largura já resolvida em porcentagem. */
interface Barra {
  fatura: Fatura;
  grupo: Grupo;
  largura: number;
}

/** Um motivo para a fatura aparecer em "precisa de você". */
interface Pendencia {
  fatura: Fatura;
  motivos: string[];
}

/**
 * A primeira tela depois do login: quanto já fechou, quanto ainda está andando e
 * o que espera alguém.
 *
 * <p>Antes o login caía na lista de faturas, que responde "quais existem" mas não
 * "como estamos" — e é essa a pergunta de quem abre o app no meio do mês. O
 * gráfico é de barras horizontais em HTML, e não um SVG: o app é usado no
 * celular, e num SVG com <code>viewBox</code> os rótulos encolhem junto com a
 * tela até não dar para ler. Aqui o texto continua no tamanho do resto da página.
 *
 * <p>Uma chamada só, a mesma da lista de faturas — que passou a responder em três
 * consultas fixas, em vez de três por fatura.
 */
@Component({
  standalone: true,
  imports: [RouterLink, CurrencyPipe, DatePipe],
  template: `
    <h1>Olá, {{ primeiroNome() }}</h1>
    <p class="sub">Como estão as faturas do cartão.</p>

    @if (carregando()) {
      <p class="vazio">Carregando…</p>
    } @else {

      <!-- A prévia vem primeiro e fora do resto: é o mês que está acontecendo,
           e é onde o trabalho de reconhecer compra rende mais — a compra ainda
           está fresca na memória de quem a fez. -->
      @if (previa(); as p) {
        <article class="cartao">
          <div class="linha">
            <div>
              <strong>Prévia de {{ p.competencia | date: 'MM/yyyy' }}</strong>
              <span class="tag previa">mês em aberto</span>
              <div class="meta">
                {{ p.totalLancamentos }} lançamentos ·
                {{ p.valorTotal | currency: 'BRL' : 'symbol' : '1.0-0' }} até agora
                @if (p.noPool > 0) {
                  · <strong>{{ p.noPool }} sem dono</strong>
                }
              </div>
            </div>
            <div>
              <a class="btn" [routerLink]="destino(p)">
                {{ auth.isAdmin() ? 'Abrir a prévia' : 'Ver o que é meu' }}
              </a>
            </div>
          </div>
          <p class="meta">
            A fatura ainda não fechou e não há nada a pagar. Marcar o que é seu
            agora é o que deixa pouco trabalho no dia do vencimento.
          </p>

          <!-- O que o CSV ainda não trouxe mas já é certo: as parcelas dos
               parcelamentos assumidos. Elas entram no mês do mesmo jeito, e o
               total do arquivo sozinho faz o mês parecer menor do que é. -->
          @if (previstas().length > 0) {
            <div class="previstas">
              <div class="linha">
                <span>
                  + {{ totalPrevisto() | currency: 'BRL' : 'symbol' : '1.0-0' }} em
                  {{ previstas().length === 1
                      ? (todasAsPessoas() ? '1 parcela já com dono que ainda não entrou'
                                          : '1 parcela sua que ainda não entrou')
                      : previstas().length
                        + (todasAsPessoas() ? ' parcelas já com dono que ainda não entraram'
                                            : ' parcelas suas que ainda não entraram') }}
                </span>
                <!-- A soma só fecha para o admin: o utilizador vê o total do mês
                     inteiro mas só as parcelas dele, e somar os dois anunciaria
                     um mês menor do que ele é. -->
                @if (todasAsPessoas()) {
                  <strong>{{ totalDoMes() | currency: 'BRL' : 'symbol' : '1.0-0' }}</strong>
                }
              </div>
              <ul class="parcelas">
                @for (p of previstas(); track $index) {
                  <li>
                    <span>{{ p.apelido ?? p.descricaoNormalizada }}</span>
                    <span class="tag">{{ p.parcela }}/{{ p.parcelaTotal }}</span>
                    @if (todasAsPessoas()) {
                      <span class="meta">{{ p.usuarioNome }}</span>
                    }
                    <strong>{{ p.valor | currency: 'BRL' }}</strong>
                  </li>
                }
              </ul>
            </div>
          }
        </article>
      } @else if (previstas().length > 0) {

        <!-- Mês sem CSV nenhum ainda. As parcelas já assumidas são a única coisa
             que se sabe dele — e são certas, então esconder o mês inteiro por
             falta de arquivo seria começar do zero um mês que não está em zero. -->
        <article class="cartao">
          <div class="linha">
            <div>
              <strong>{{ competencia() | date: 'MM/yyyy' }}</strong>
              <span class="tag previa">mês em aberto</span>
              <div class="meta">
                Nenhum CSV subido ainda ·
                {{ previstas().length }}
                {{ previstas().length === 1 ? 'parcela' : 'parcelas' }}
                {{ todasAsPessoas() ? 'já com dono' : 'suas' }} ·
                {{ totalPrevisto() | currency: 'BRL' : 'symbol' : '1.0-0' }}
              </div>
            </div>
            @if (auth.isAdmin()) {
              <div>
                <a class="btn" routerLink="/admin/importar">Subir a prévia do mês</a>
              </div>
            }
          </div>
          <ul class="parcelas">
            @for (p of previstas(); track $index) {
              <li>
                <span>{{ p.apelido ?? p.descricaoNormalizada }}</span>
                <span class="tag">{{ p.parcela }}/{{ p.parcelaTotal }}</span>
                @if (todasAsPessoas()) {
                  <span class="meta">{{ p.usuarioNome }}</span>
                }
                <strong>{{ p.valor | currency: 'BRL' }}</strong>
              </li>
            }
          </ul>
          <p class="meta">
            Parcelamento gruda: quem assumiu a primeira parcela segue dono das
            seguintes até quitar. Estas já entram neste mês, com ou sem CSV — os
            valores são os da parcela anterior e podem variar alguns centavos.
          </p>
        </article>
      }

      @if (fechadasEEmAndamento().length === 0) {
        <div class="vazio">
          <p>Nenhuma fatura fechada ainda.</p>
          @if (auth.isAdmin()) {
            <a class="btn" routerLink="/admin/importar">
              {{ previa() ? 'Importar a fatura do mês' : 'Subir a primeira' }}
            </a>
          }
        </div>
      } @else {
        <div class="tiles">
          <div class="tile andamento">
            <span class="rotulo">Em andamento</span>
            <strong>{{ andamento().length }}</strong>
            <span class="meta">
              {{ andamento().length === 1 ? 'fatura' : 'faturas' }} ·
              {{ totalAndamento() | currency: 'BRL' : 'symbol' : '1.0-0' }}
            </span>
          </div>
          <div class="tile fechada">
            <span class="rotulo">Fechadas</span>
            <strong>{{ fechadas().length }}</strong>
            <span class="meta">
              {{ fechadas().length === 1 ? 'fatura' : 'faturas' }} ·
              {{ totalFechadas() | currency: 'BRL' : 'symbol' : '1.0-0' }}
            </span>
          </div>
        </div>

        <h2>Faturas por mês</h2>
        <ul class="barras">
          @for (b of barras(); track b.fatura.id) {
            <li>
              <a class="mes" [routerLink]="destino(b.fatura)">
                {{ b.fatura.competencia | date: 'MM/yy' }}
              </a>
              <span class="trilha" aria-hidden="true">
                <span class="preenchida" [class]="b.grupo" [style.width.%]="b.largura"></span>
              </span>
              <span class="valor">
                {{ b.fatura.valorTotal | currency: 'BRL' : 'symbol' : '1.0-0' }}
              </span>
            </li>
          }
        </ul>
        <p class="legenda">
          <span class="chave fechada" aria-hidden="true"></span> Fechada
          <span class="chave andamento" aria-hidden="true"></span> Em andamento
          @if (temDivergente()) {
            <span class="chave divergente" aria-hidden="true"></span> Divergente
          }
        </p>
        @if (fechadasEEmAndamento().length > barras().length) {
          <p class="nota">
            Mostrando os últimos {{ barras().length }} meses —
            <a routerLink="/faturas">ver todas as faturas</a>.
          </p>
        }

        @if (pendencias().length > 0) {
          <h2>Precisa de você</h2>
          @for (p of pendencias(); track p.fatura.id) {
            <article class="cartao">
              <div class="linha">
                <div>
                  <strong>{{ p.fatura.competencia | date: 'MM/yyyy' }}</strong>
                  <ul class="motivos">
                    @for (m of p.motivos; track m) {
                      <li>{{ m }}</li>
                    }
                  </ul>
                </div>
                <div>
                  <a class="btn" [routerLink]="destino(p.fatura)">{{ acao(p.fatura) }}</a>
                </div>
              </div>
            </article>
          }
        } @else {
          <p class="nota">Nada esperando por você agora.</p>
        }
      }
    }
  `,
})
export class InicioComponent {
  private api = inject(ApiService);
  auth = inject(AuthService);

  faturas = signal<Fatura[]>([]);
  /** O mês em aberto com as parcelas que ainda vão entrar; null enquanto carrega. */
  mes = signal<PreviaDoMes | null>(null);
  carregando = signal(true);

  /** Um ano de histórico é o que cabe na tela sem virar rolagem infinita. */
  private static readonly MESES_NO_GRAFICO = 12;

  primeiroNome = computed(() => this.auth.usuario()?.nome?.split(' ')[0] ?? '');

  /** A parcial do mês em curso, quando existe uma. No máximo uma por mês. */
  previa = computed(() => this.faturas().find((f) => f.status === 'PREVIA') ?? null);

  /**
   * As parcelas que os parcelamentos assumidos ainda vão trazer para este mês.
   *
   * <p>Não são lançamentos: não estão no banco e ninguém as assume aqui — elas
   * já têm dono, que é quem assumiu a primeira parcela. Aparecem porque o mês em
   * aberto sem elas mostra só metade do que vem por aí, e porque elas existem
   * antes de qualquer CSV.
   */
  previstas = computed(() => this.mes()?.parcelas ?? []);
  totalPrevisto = computed(() => this.mes()?.totalPrevisto ?? 0);
  todasAsPessoas = computed(() => this.mes()?.todasAsPessoas ?? false);
  competencia = computed(() => this.mes()?.competencia ?? null);

  /** O tamanho do mês: o que o arquivo já trouxe mais o que ainda vem. */
  totalDoMes = computed(() => (this.previa()?.valorTotal ?? 0) + this.totalPrevisto());

  /**
   * As faturas de verdade. A prévia fica de fora dos totais, do gráfico e das
   * pendências <b>de propósito</b>: ela é um número que ainda vai crescer, e
   * somá-la ao "em andamento" faria a tela anunciar uma dívida que não existe.
   * Ela tem cartão próprio, no topo, dizendo o que é.
   */
  fechadasEEmAndamento = computed(() => this.faturas().filter((f) => f.status !== 'PREVIA'));

  fechadas = computed(() =>
    this.fechadasEEmAndamento().filter((f) => f.status === 'FECHADA'));
  andamento = computed(() =>
    this.fechadasEEmAndamento().filter((f) => f.status !== 'FECHADA'));
  totalFechadas = computed(() => this.soma(this.fechadas()));
  totalAndamento = computed(() => this.soma(this.andamento()));
  temDivergente = computed(() => this.barras().some((b) => b.grupo === 'divergente'));

  /**
   * As barras, proporcionais à maior fatura do período.
   *
   * <p>A largura mínima é de 2%: uma fatura de valor pequeno ao lado de um mês
   * de viagem sairia com barra de zero pixel, e uma barra invisível parece dado
   * faltando.
   */
  barras = computed<Barra[]>(() => {
    const meses = this.fechadasEEmAndamento().slice(0, InicioComponent.MESES_NO_GRAFICO);
    const maior = Math.max(...meses.map((f) => Math.abs(f.valorTotal)), 1);
    return meses.map((f) => ({
      fatura: f,
      grupo: this.grupo(f),
      largura: Math.max(2, (Math.abs(f.valorTotal) / maior) * 100),
    }));
  });

  /** Só o que ainda depende de alguém — quem já fechou não pede nada. */
  pendencias = computed<Pendencia[]>(() =>
    this.fechadasEEmAndamento()
      .map((f) => ({ fatura: f, motivos: this.motivos(f) }))
      .filter((p) => p.motivos.length > 0));

  constructor() {
    this.api.faturas().subscribe({
      next: (f) => { this.faturas.set(f); this.carregando.set(false); },
      error: () => this.carregando.set(false),
    });
    // Em paralelo, e sem derrubar a tela se falhar: as parcelas previstas são um
    // acréscimo ao panorama, não o panorama. Chamada à parte porque responde
    // mesmo quando não existe prévia nenhuma — e é aí que ela mais importa.
    this.api.previaDoMes().subscribe({
      next: (m) => this.mes.set(m),
      error: () => this.mes.set(null),
    });
  }

  /**
   * O que essa fatura espera de quem está olhando.
   *
   * <p>Divergência e conflito são assunto do admin: só ele pode resolver, e
   * avisar o utilizador sobre eles seria dar preocupação sem saída. O pool e o
   * aceite valem para todo mundo.
   */
  private motivos(f: Fatura): string[] {
    const motivos: string[] = [];
    if (f.status === 'DIVERGENTE' && this.auth.isAdmin()) {
      motivos.push('a leitura não fecha com o total impresso');
    }
    if (f.emConflito > 0 && this.auth.isAdmin()) {
      motivos.push(`${f.emConflito} em conflito, esperando você decidir`);
    }
    if (f.status === 'EM_AVALIACAO' && f.noPool > 0) {
      motivos.push(this.auth.isAdmin()
        ? `${f.noPool} lançamentos ainda sem dono`
        : `${f.noPool} lançamentos sem dono — veja se algum é seu`);
    }
    if (f.status === 'CONCILIADA') {
      motivos.push(this.auth.isAdmin()
        ? 'conciliada: falta fechar'
        : 'confira o seu valor e aceite');
    }
    return motivos;
  }

  /** Admin vai para a conciliação; utilizador, para as contas dele. */
  destino(f: Fatura): unknown[] {
    return this.auth.isAdmin()
      ? ['/admin/faturas', f.id]
      : ['/faturas', f.id, 'minhas-contas'];
  }

  acao(f: Fatura): string {
    return this.auth.isAdmin() ? 'Abrir conciliação' : 'Minhas contas';
  }

  private grupo(f: Fatura): Grupo {
    if (f.status === 'DIVERGENTE') return 'divergente';
    return f.status === 'FECHADA' ? 'fechada' : 'andamento';
  }

  private soma(faturas: Fatura[]): number {
    return faturas.reduce((t, f) => t + f.valorTotal, 0);
  }
}

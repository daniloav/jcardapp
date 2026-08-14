import { CurrencyPipe, DecimalPipe } from '@angular/common';
import { Component, OnDestroy, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiService } from '../core/api.service';
import { OcrService } from '../core/ocr.service';
import { ToastService } from '../core/toast.service';
import { LinhaConfirmada, LinhaLida } from '../core/models';

/** Uma linha na tela de conferência, já editável. */
interface Linha {
  incluir: boolean;
  data: string;
  descricao: string;
  /** Texto, não número: é digitado à mão em pt-BR ("189,90"). */
  valor: string;
  parcelaAtual: number | null;
  parcelaTotal: number | null;
  /** De onde ela saiu no print; vazio quando foi digitada à mão. */
  original: string;
}

/**
 * Montar a prévia a print, para quem não consegue o CSV da fatura em aberto.
 *
 * <p>No celular o que dá para tirar do app do banco é foto da tela. O OCR roda
 * <b>aqui</b>, no aparelho — a e2-micro tem 1 GB dividido com o Quarkus, e a
 * imagem não precisa sair do telefone. Sobe só o texto.
 *
 * <p>O que a máquina lê é <b>proposta</b>. OCR troca dígito, e a prévia não tem
 * total impresso para denunciar a troca — foi por isso que o PDF já tinha sido
 * recusado nela. Cada linha aparece editável e marcada, e só o que for
 * confirmado vira lançamento. É a mesma pessoa que tirou o print conferindo
 * contra a mesma tela: o trabalho vira ler, não digitar.
 *
 * <p>A tela fica de pé depois de somar: prints são vários, e voltar ao menu a
 * cada um seria o mesmo mutirão de que a prévia veio fugir.
 */
@Component({
  standalone: true,
  imports: [FormsModule, RouterLink, CurrencyPipe, DecimalPipe],
  template: `
    <h1>Montar a prévia por print</h1>
    <p class="sub">
      Anexe um print de cada vez da fatura em aberto do app do banco. Cada um
      <strong>soma</strong> ao mês — nenhum apaga o anterior.
    </p>

    <div class="aviso previa">
      <strong>O que a leitura entender é proposta, não lançamento.</strong>
      Nada entra na prévia sem você confirmar linha a linha: leitura de imagem
      troca dígito, e aqui não existe total impresso para denunciar a troca.
      Confira contra o print e corrija o que estiver errado antes de somar.
    </div>

    <article class="cartao">
      <label for="competencia">Mês da prévia</label>
      <input id="competencia" type="month" [(ngModel)]="competencia" name="competencia">

      <label for="print" style="margin-top:0.75rem">Print da fatura em aberto</label>
      <input id="print" type="file" accept="image/*" (change)="escolher($event)"
             [disabled]="lendo()">

      @if (lendo()) {
        <p class="meta" role="status">
          @if (ocr.preparando()) {
            Preparando a leitura (só na primeira vez deste aparelho)…
          } @else {
            Lendo o print… {{ (ocr.progresso() * 100) | number: '1.0-0' }}%
          }
        </p>
        <div class="trilha" aria-hidden="true">
          <span class="preenchida andamento" [style.width.%]="ocr.progresso() * 100"></span>
        </div>
      }

      @if (somadasNoMes() > 0) {
        <p class="meta">
          Já somados neste mês: <strong>{{ somadasNoMes() }}</strong>
          {{ somadasNoMes() === 1 ? 'lançamento' : 'lançamentos' }} deste aparelho.
          <a routerLink="/admin/faturas" >ver a prévia</a>
        </p>
      }
    </article>

    @if (linhas().length > 0) {
      <h2>Confira antes de somar ({{ marcadas().length }} de {{ linhas().length }})</h2>
      <div class="cartao">
        @for (l of linhas(); track $index) {
          <div class="linha-print" [class.fora]="!l.incluir">
            <label class="marcar">
              <input type="checkbox" [(ngModel)]="l.incluir" [name]="'incluir' + $index"
                     [attr.aria-label]="'Incluir ' + l.descricao">
            </label>
            <div class="campos">
              <input type="date" [(ngModel)]="l.data" [name]="'data' + $index"
                     aria-label="Data da compra">
              <input type="text" [(ngModel)]="l.descricao" [name]="'descricao' + $index"
                     aria-label="Estabelecimento" placeholder="Estabelecimento">
              <input type="text" inputmode="decimal" [(ngModel)]="l.valor"
                     [name]="'valor' + $index" aria-label="Valor" placeholder="0,00"
                     class="valor">
              <span class="parcela">
                <input type="number" min="1" max="99" [(ngModel)]="l.parcelaAtual"
                       [name]="'pa' + $index" aria-label="Parcela atual" placeholder="—">
                /
                <input type="number" min="2" max="99" [(ngModel)]="l.parcelaTotal"
                       [name]="'pt' + $index" aria-label="Total de parcelas" placeholder="—">
              </span>
              <button type="button" class="btn-texto" (click)="remover($index)"
                      [attr.aria-label]="'Descartar ' + l.descricao">descartar</button>
            </div>
            @if (l.original) {
              <div class="meta original">lido: “{{ l.original }}”</div>
            }
          </div>
        }

        <div class="linha" style="margin-top:0.75rem">
          <button type="button" class="btn-secundario" (click)="adicionar()">
            + Adicionar linha à mão
          </button>
          <strong>{{ total() | currency: 'BRL' }}</strong>
        </div>

        <div class="linha" style="margin-top:0.75rem">
          <button type="button" [disabled]="marcadas().length === 0 || enviando()"
                  (click)="somar()">
            {{ enviando() ? 'Somando…' : 'Somar ' + marcadas().length + ' à prévia' }}
          </button>
          <button type="button" class="btn-secundario" (click)="limpar()">
            Descartar este print
          </button>
        </div>
      </div>
    }

    @if (naoReconhecidas().length > 0) {
      <h2>Não reconhecido ({{ naoReconhecidas().length }})</h2>
      <div class="cartao">
        <p class="meta">
          Pedaços do print que não viraram linha — quase sempre cabeçalho e
          rodapé da tela do banco. Se alguma compra sua estiver aqui, use
          <em>adicionar linha à mão</em>: o que a leitura não fecha, ela não
          inventa.
        </p>
        <ul class="cru">
          @for (t of naoReconhecidas(); track $index) {
            <li>{{ t }}</li>
          }
        </ul>
      </div>
    }
  `,
  styles: [`
    label { display: block; font-weight: 600; margin-bottom: 0.25rem; }
    .trilha { display: block; height: 6px; border-radius: 3px; background: var(--borda); }
    .trilha .preenchida { display: block; height: 100%; border-radius: 3px; }
    /* Uma linha por lançamento, com os campos quebrando no celular em vez de
       espremer — é lá que o print é tirado e conferido. */
    .linha-print { padding: 0.5rem 0; border-top: 1px solid var(--borda); }
    .linha-print:first-child { border-top: 0; }
    .linha-print.fora { opacity: 0.45; }
    .linha-print .campos { display: flex; flex-wrap: wrap; align-items: center; gap: 0.4rem; }
    .linha-print .marcar { float: left; margin: 0.35rem 0.5rem 0 0; }
    .linha-print input[type=text] { flex: 1 1 10rem; min-width: 0; }
    .linha-print input[type=date] { flex: 0 0 9.5rem; }
    .linha-print input.valor { flex: 0 0 6.5rem; text-align: right; }
    .linha-print .parcela { display: inline-flex; align-items: center; gap: 0.2rem; }
    .linha-print .parcela input { width: 3.2rem; }
    .original { font-style: italic; margin-top: 0.2rem; }
    .cru { margin: 0.4rem 0 0; padding-left: 1.1rem; font-size: 0.9rem; color: var(--texto-suave); }
  `],
})
export class AdminPreviaPrintComponent implements OnDestroy {
  private api = inject(ApiService);
  private toast = inject(ToastService);
  ocr = inject(OcrService);

  competencia = new Date().toISOString().slice(0, 7);
  linhas = signal<Linha[]>([]);
  naoReconhecidas = signal<string[]>([]);
  lendo = signal(false);
  enviando = signal(false);
  /** Quanto já entrou por esta tela, para não perder a conta entre prints. */
  somadasNoMes = signal(0);

  marcadas = computed(() => this.linhas().filter((l) => l.incluir));
  total = computed(() => this.marcadas().reduce((s, l) => s + (this.numero(l.valor) ?? 0), 0));

  constructor() {
    // O mês em aberto não é sempre o do calendário: a fatura fecha antes do fim
    // do mês. O backend já sabe qual é — perguntar evita começar no mês errado.
    this.api.previaDoMes().subscribe({
      next: (m) => { this.competencia = m.competencia.slice(0, 7); },
    });
  }

  ngOnDestroy(): void {
    void this.ocr.encerrar();
  }

  async escolher(evento: Event): Promise<void> {
    const input = evento.target as HTMLInputElement;
    const arquivo = input.files?.[0];
    input.value = '';                       // permite reescolher o mesmo arquivo
    if (!arquivo || this.lendo()) {
      return;
    }
    this.lendo.set(true);
    try {
      const texto = await this.ocr.ler(arquivo);
      if (!texto.trim()) {
        this.toast.erro('Não consegui ler nada nesse print. Tente uma foto mais nítida.');
        return;
      }
      this.api.lerPrint(texto, this.competencia).subscribe({
        next: (r) => {
          // Acumula com o que já estava na tela: dá para anexar dois prints e
          // conferir os dois de uma vez.
          this.linhas.update((atuais) => [...atuais, ...r.linhas.map((l) => this.editavel(l))]);
          this.naoReconhecidas.update((atuais) => [...atuais, ...r.naoReconhecidas]);
          if (r.linhas.length === 0) {
            this.toast.erro('Li o print, mas não achei nenhum lançamento nele.');
          }
        },
      });
    } catch {
      this.toast.erro('Falha ao ler a imagem. Se o problema continuar, use o CSV.');
    } finally {
      this.lendo.set(false);
    }
  }

  adicionar(): void {
    this.linhas.update((atuais) => [...atuais, {
      incluir: true,
      data: new Date().toISOString().slice(0, 10),
      descricao: '',
      valor: '',
      parcelaAtual: null,
      parcelaTotal: null,
      original: '',
    }]);
  }

  remover(indice: number): void {
    this.linhas.update((atuais) => atuais.filter((_, i) => i !== indice));
  }

  limpar(): void {
    this.linhas.set([]);
    this.naoReconhecidas.set([]);
  }

  somar(): void {
    const confirmadas: LinhaConfirmada[] = [];
    for (const l of this.marcadas()) {
      const valor = this.numero(l.valor);
      if (!l.data || !l.descricao.trim() || valor === null) {
        this.toast.erro('Toda linha marcada precisa de data, estabelecimento e valor.');
        return;
      }
      confirmadas.push({
        data: l.data,
        descricao: l.descricao.trim(),
        valor,
        parcelaAtual: l.parcelaTotal ? l.parcelaAtual : null,
        parcelaTotal: l.parcelaAtual ? l.parcelaTotal : null,
      });
    }

    this.enviando.set(true);
    this.api.somarAoPrevia(this.competencia, confirmadas).subscribe({
      next: (r) => {
        this.enviando.set(false);
        this.somadasNoMes.update((n) => n + r.somadas);
        const partes = [
          r.somadas === 1
            ? `1 lançamento somado — ${r.totalNaPrevia} no mês.`
            : `${r.somadas} lançamentos somados — ${r.totalNaPrevia} no mês.`,
        ];
        if (r.repetidas > 0) {
          partes.push(r.repetidas === 1
            ? '1 linha já estava lá e foi descartada.'
            : `${r.repetidas} linhas já estavam lá e foram descartadas.`);
        }
        if (r.parcelasConferidas.length > 0) {
          partes.push(r.parcelasConferidas.length === 1
            ? 'Uma parcela prevista chegou e já entrou no nome de quem assumiu.'
            : `${r.parcelasConferidas.length} parcelas previstas chegaram e já entraram `
              + 'no nome de quem assumiu.');
        }
        this.toast.ok(partes.join(' '));
        this.limpar();
      },
      error: () => this.enviando.set(false),
    });
  }

  /** "189,90" e "189.90" viram 189.9; vazio e lixo viram null. */
  private numero(valor: string): number | null {
    const limpo = (valor ?? '').replace(/[R$\s]/gi, '').replace(/\.(?=\d{3}\b)/g, '')
      .replace(',', '.');
    if (!limpo || !/^-?\d+(\.\d+)?$/.test(limpo)) {
      return null;
    }
    return Number(limpo);
  }

  private editavel(l: LinhaLida): Linha {
    return {
      incluir: true,
      data: l.data,
      descricao: l.descricao,
      valor: l.valor.toFixed(2).replace('.', ','),
      parcelaAtual: l.parcelaAtual,
      parcelaTotal: l.parcelaTotal,
      original: l.linhaOriginal,
    };
  }
}

import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { ApiService } from '../core/api.service';
import { ToastService } from '../core/toast.service';

/**
 * Subir a fatura, nos dois momentos em que ela existe.
 *
 * <p>A <b>prévia</b> é o mês em curso: o CSV da fatura em aberto, subido quantas
 * vezes o admin quiser, para as pessoas irem assumindo o que é delas com a
 * compra ainda fresca. A <b>fatura fechada</b> é o mês encerrado, com total
 * impresso — e ela nasce já com tudo que foi assumido na prévia.
 *
 * <p>Um formulário só, com um seletor no topo, porque a diferença entre os dois
 * é pequena e concreta (a prévia não pede total, a fatura pede) e vê-la lado a
 * lado é o que ensina quando usar cada um. Duas telas separadas esconderiam a
 * prévia de quem nunca ouviu falar dela.
 */
@Component({
  standalone: true,
  imports: [FormsModule],
  template: `
    <h1>Subir fatura</h1>

    <div class="modos" role="radiogroup" aria-label="O que você está subindo">
      <label class="modo" [class.ativo]="modo() === 'previa'">
        <span class="escolha">
          <input type="radio" name="modo" value="previa" [ngModel]="modo()"
                 (ngModelChange)="trocarModo('previa')">
          <strong>Prévia do mês</strong>
        </span>
        <span class="meta">
          A fatura ainda em aberto. Suba quantas vezes quiser — o que já foi
          assumido continua assumido.
        </span>
      </label>
      <label class="modo" [class.ativo]="modo() === 'fatura'">
        <span class="escolha">
          <input type="radio" name="modo" value="fatura" [ngModel]="modo()"
                 (ngModelChange)="trocarModo('fatura')">
          <strong>Fatura fechada</strong>
        </span>
        <span class="meta">
          O mês encerrado, com o total impresso. É a que gera a cobrança.
        </span>
      </label>
    </div>

    <p class="sub">
      @if (previa()) {
        O app quebra os lançamentos e devolve para o pool tudo que ninguém
        assumiu ainda. <strong>Ninguém é cobrado por uma prévia</strong> e ninguém
        recebe e-mail: ela existe para o trabalho ser feito aos poucos, ao longo
        do mês, em vez de tudo no dia do vencimento.
      } @else {
        Envie o <strong>CSV</strong> da fatura (recomendado) ou o PDF. O app quebra
        os lançamentos, aproveita o que já foi assumido na prévia do mês, aplica os
        parcelamentos e avisa todos os utilizadores por e-mail.
      }
    </p>

    <form class="cartao" (ngSubmit)="enviar()">
      <label for="competencia">
        Competência (mês da fatura)
        <input id="competencia" name="competencia" type="month"
               [(ngModel)]="competencia" required />
      </label>

      <label for="arquivo">
        {{ previa() ? 'CSV da fatura em aberto (.csv)' : 'Arquivo da fatura (.csv ou .pdf)' }}
        <input id="arquivo" name="arquivo" type="file"
               [accept]="previa() ? '.csv,text/csv' : '.csv,text/csv,application/pdf'"
               (change)="escolher($event)" required />
      </label>

      @if (!previa()) {
        <label for="total">
          Valor total (opcional)
          <input id="total" name="total" [(ngModel)]="valorTotal"
                 placeholder="ex.: 3.456,78" inputmode="decimal" />
        </label>
        <p class="meta">
          <strong>Obrigatório no CSV</strong>, que não traz o total. No PDF, só se a
          importação reclamar. O app confere contra a soma dos lançamentos e trava
          se não bater — é o que impede cobrar alguém em cima de leitura errada.
        </p>
      }

      <button type="submit" [disabled]="!arquivo || enviando()">
        {{ enviando() ? 'Processando…' : (previa() ? 'Atualizar a prévia' : 'Importar fatura') }}
      </button>
    </form>

    @if (previa()) {
      <div class="aviso info">
        <strong>Subir de novo substitui a prévia anterior</strong> — e não custa o
        trabalho de ninguém: o que cada pessoa já assumiu é reaplicado nas mesmas
        compras do arquivo novo, inclusive as contas rachadas. Uma compra que
        aparecer com outro valor ou outra data volta para o pool: valor diferente
        é outra compra, e herdar cobraria de alguém um número que ela não conferiu.
      </div>
      <div class="aviso info">
        <strong>Só CSV.</strong> O leitor de PDF não lê a fatura inteira, e numa
        prévia não existe total impresso para denunciar a falta — as pessoas
        passariam o mês assumindo contas de uma leitura incompleta.
      </div>
    } @else {
      <div class="aviso info">
        <strong>Prefira o CSV.</strong> O PDF é feito para ser lido por gente: tem
        duas colunas, descrições cortadas na largura e blocos misturados
        (internacionais, taxas, parcelas futuras). O CSV traz um lançamento por
        linha, com a parcela num campo próprio.
      </div>
      <div class="aviso info">
        <strong>A prévia do mês vira esta fatura.</strong> Ao importar, tudo que as
        pessoas assumiram na prévia de {{ competencia }} já entra aqui com dono, os
        parcelamentos passam a valer para os próximos meses e a prévia deixa de
        existir.
      </div>
      <div class="aviso info">
        <strong>Importar duas vezes não duplica.</strong> O app identifica o arquivo
        pelo conteúdo e recusa um já importado.
      </div>
    }
  `,
  styles: [`
    .modos { display: flex; flex-wrap: wrap; gap: 0.75rem; margin-bottom: 1rem; }
    .modo {
      flex: 1 1 14rem; display: grid; gap: 0.25rem; padding: 0.75rem;
      background: var(--superficie);
      border: 1px solid var(--borda); border-radius: var(--raio); cursor: pointer;
    }
    .modo.ativo { border-color: var(--acento); box-shadow: inset 0 0 0 1px var(--acento); }
    /* O rádio na mesma linha do título: solto acima dele, ele não parecia
       controlar o cartão inteiro — e é o cartão inteiro que é clicável. */
    .escolha { display: flex; align-items: center; gap: 0.4rem; }
    .escolha input { margin: 0; flex: none; }
  `],
})
export class AdminImportarComponent {
  private api = inject(ApiService);
  private toast = inject(ToastService);
  private router = inject(Router);

  modo = signal<'previa' | 'fatura'>('previa');
  previa = computed(() => this.modo() === 'previa');

  competencia = new Date().toISOString().slice(0, 7);
  valorTotal = '';
  arquivo: File | null = null;
  enviando = signal(false);

  trocarModo(modo: 'previa' | 'fatura'): void {
    this.modo.set(modo);
  }

  escolher(evento: Event): void {
    const input = evento.target as HTMLInputElement;
    this.arquivo = input.files?.[0] ?? null;
  }

  enviar(): void {
    if (!this.arquivo || this.enviando()) {
      return;
    }
    this.enviando.set(true);
    if (this.previa()) {
      this.enviarPrevia(this.arquivo);
    } else {
      this.enviarFatura(this.arquivo);
    }
  }

  /**
   * O aviso conta o que sobreviveu à substituição — e o que não sobreviveu.
   * Quem sobe a prévia toda semana precisa saber que não está apagando o
   * trabalho da família; e quando alguma compra mudou de valor no banco, quem
   * a tinha assumido vai ter de assumir de novo.
   */
  private enviarPrevia(arquivo: File): void {
    this.api.subirPrevia(arquivo, this.competencia).subscribe({
      next: (r) => {
        this.enviando.set(false);
        const partes = [`Prévia atualizada: ${r.lancamentos} lançamentos, ${r.noPool} sem dono.`];
        if (r.mantidos > 0) {
          partes.push(`${r.mantidos} já assumidos foram mantidos.`);
        }
        if (r.devolvidos > 0) {
          partes.push(`${r.devolvidos} mudaram no arquivo novo e voltaram ao pool.`);
        }
        this.toast.ok(partes.join(' '));
        this.router.navigate(['/admin/faturas', r.fatura.id]);
      },
      error: () => this.enviando.set(false),
    });
  }

  private enviarFatura(arquivo: File): void {
    this.api.importarFatura(arquivo, this.competencia, this.valorTotal || undefined)
      .subscribe({
        next: (f) => {
          this.enviando.set(false);
          if (f.status === 'DIVERGENTE') {
            this.toast.erro(
              `Lida, mas a soma não fecha: diferença de R$ ${f.divergencia}. ` +
              'Ninguém foi avisado.');
          } else {
            this.toast.ok(`Fatura importada: ${f.totalLancamentos} lançamentos, ` +
              `${f.noPool} sem dono. Utilizadores avisados.`);
          }
          this.router.navigate(['/admin/faturas', f.id]);
        },
        error: () => this.enviando.set(false),
      });
  }
}

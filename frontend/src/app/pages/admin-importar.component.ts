import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { ApiService } from '../core/api.service';
import { ToastService } from '../core/toast.service';

@Component({
  standalone: true,
  imports: [FormsModule],
  template: `
    <h1>Importar fatura</h1>
    <p class="sub">
      Envie o <strong>CSV</strong> da fatura (recomendado) ou o PDF. O app quebra
      os lançamentos, aplica os parcelamentos já assumidos e avisa todos os
      utilizadores por e-mail.
    </p>

    <form class="cartao" (ngSubmit)="enviar()">
      <label for="competencia">
        Competência (mês da fatura)
        <input id="competencia" name="competencia" type="month"
               [(ngModel)]="competencia" required />
      </label>

      <label for="arquivo">
        Arquivo da fatura (.csv ou .pdf)
        <input id="arquivo" name="arquivo" type="file" accept=".csv,text/csv,application/pdf"
               (change)="escolher($event)" required />
      </label>

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

      <button type="submit" [disabled]="!arquivo || enviando()">
        {{ enviando() ? 'Processando…' : 'Importar' }}
      </button>
    </form>

    <div class="aviso info">
      <strong>Prefira o CSV.</strong> O PDF é feito para ser lido por gente: tem
      duas colunas, descrições cortadas na largura e blocos misturados
      (internacionais, taxas, parcelas futuras). O CSV traz um lançamento por
      linha, com a parcela num campo próprio.
    </div>

    <div class="aviso info">
      <strong>Importar duas vezes não duplica.</strong> O app identifica o arquivo
      pelo conteúdo e recusa um já importado.
    </div>
  `,
})
export class AdminImportarComponent {
  private api = inject(ApiService);
  private toast = inject(ToastService);
  private router = inject(Router);

  competencia = new Date().toISOString().slice(0, 7);
  valorTotal = '';
  arquivo: File | null = null;
  enviando = signal(false);

  escolher(evento: Event): void {
    const input = evento.target as HTMLInputElement;
    this.arquivo = input.files?.[0] ?? null;
  }

  enviar(): void {
    if (!this.arquivo || this.enviando()) {
      return;
    }
    this.enviando.set(true);
    this.api.importarFatura(this.arquivo, this.competencia, this.valorTotal || undefined)
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

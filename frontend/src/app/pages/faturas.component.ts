import { CurrencyPipe, DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService } from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { ToastService } from '../core/toast.service';
import { Fatura } from '../core/models';

@Component({
  standalone: true,
  imports: [RouterLink, CurrencyPipe, DatePipe],
  template: `
    <h1>Faturas</h1>
    <p class="sub">Abra a fatura para ver o que está sem dono e marcar o que foi você.</p>

    @if (carregando()) {
      <p class="vazio">Carregando…</p>
    } @else if (faturas().length === 0) {
      <div class="vazio">
        <p>Nenhuma fatura ainda.</p>
        @if (auth.isAdmin()) {
          <a class="btn" routerLink="/admin/importar">Importar a primeira</a>
        }
      </div>
    } @else {
      @for (f of faturas(); track f.id) {
        <article class="cartao">
          <div class="linha">
            <div>
              <strong>{{ f.competencia | date: 'MM/yyyy' }}</strong>
              <span class="tag" [class]="classeStatus(f)">{{ rotulo(f.status) }}</span>
              <div class="meta">
                {{ f.status === 'PREVIA' ? 'Até agora' : 'Total' }}
                {{ f.valorTotal | currency: 'BRL' }} ·
                {{ f.totalLancamentos }} lançamentos
              </div>
            </div>
            <div>
              @if (f.noPool > 0 && (f.status === 'EM_AVALIACAO' || f.status === 'PREVIA')) {
                <span class="tag alerta">{{ f.noPool }} sem dono</span>
              }
              @if (f.emConflito > 0) {
                <span class="tag erro">{{ f.emConflito }} em conflito</span>
              }
            </div>
          </div>

          @if (f.status === 'PREVIA') {
            <div class="aviso previa">
              <strong>Ainda é só a prévia deste mês.</strong> A fatura não fechou:
              faltam compras, os valores mudam e não há nada a pagar. Assumir o que
              é seu agora é o que deixa pouco trabalho para o dia do vencimento.
            </div>
          }

          @if (f.status === 'DIVERGENTE') {
            <div class="aviso erro">
              A leitura do PDF não fecha com o total impresso — diferença de
              {{ f.divergencia | currency: 'BRL' }}. Ninguém foi avisado e a fatura
              não avança até isso ser resolvido.
            </div>
          }

          <div class="linha" style="margin-top:0.75rem">
            <a class="btn" [routerLink]="['/faturas', f.id, 'minhas-contas']">Minhas contas</a>
            @if (auth.isAdmin()) {
              <a class="btn btn-secundario" [routerLink]="['/admin/faturas', f.id]">
                Conciliação
              </a>
              @if (f.status !== 'FECHADA') {
                <button type="button" class="btn-perigo" [disabled]="excluindo() === f.id"
                        (click)="excluir(f)">Excluir</button>
              }
            }
          </div>
        </article>
      }
    }
  `,
})
export class FaturasComponent {
  private api = inject(ApiService);
  private toast = inject(ToastService);
  auth = inject(AuthService);

  faturas = signal<Fatura[]>([]);
  carregando = signal(true);
  excluindo = signal<number | null>(null);

  constructor() {
    this.carregar();
  }

  /**
   * Apagar a fatura é a saída para o arquivo errado ou a competência trocada —
   * o hash único impede simplesmente reimportar por cima.
   *
   * <p>A confirmação diz o que vai embora junto, porque some tudo: lançamentos,
   * quem assumiu o quê e os acertos calculados.
   */
  excluir(f: Fatura): void {
    const mes = new Date(f.competencia).toLocaleDateString('pt-BR',
      { month: '2-digit', year: 'numeric' });
    // A prévia some sozinha quando a fatura do mês chega; apagá-la à mão é
    // recomeçar o mês do zero, e é isso que a confirmação precisa dizer.
    const ok = confirm(f.status === 'PREVIA'
      ? `Apagar a prévia de ${mes}?\n\n`
        + `Somem os ${f.totalLancamentos} lançamentos e tudo que as pessoas já assumiram `
        + 'nela. Subir o CSV de novo recria a prévia, mas com todo mundo começando '
        + 'de novo. Não dá para desfazer.'
      : `Excluir a fatura de ${mes}?\n\n`
        + `Somem os ${f.totalLancamentos} lançamentos, tudo que as pessoas já assumiram `
        + 'e os acertos calculados. Não dá para desfazer.');
    if (!ok) {
      return;
    }
    this.excluindo.set(f.id);
    this.api.excluirFatura(f.id).subscribe({
      next: () => {
        this.toast.ok('Fatura excluída.');
        this.excluindo.set(null);
        this.carregar();
      },
      error: () => this.excluindo.set(null),
    });
  }

  private carregar(): void {
    this.api.faturas().subscribe({
      next: (f) => { this.faturas.set(f); this.carregando.set(false); },
      error: () => this.carregando.set(false),
    });
  }

  rotulo(s: Fatura['status']): string {
    return {
      PREVIA: 'prévia · mês em aberto',
      IMPORTADA: 'importada',
      DIVERGENTE: 'divergente',
      EM_AVALIACAO: 'em avaliação',
      CONCILIADA: 'conciliada',
      FECHADA: 'fechada',
    }[s];
  }

  classeStatus(f: Fatura): string {
    if (f.status === 'DIVERGENTE') return 'erro';
    if (f.status === 'FECHADA') return 'ok';
    if (f.status === 'PREVIA') return 'previa';
    if (f.status === 'EM_AVALIACAO') return 'info';
    return '';
  }
}

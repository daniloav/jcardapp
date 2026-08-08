import { CurrencyPipe, DatePipe } from '@angular/common';
import { Component, Input, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiService } from '../core/api.service';
import { ToastService } from '../core/toast.service';
import { Lancamento, MinhasContas, descricaoSemParcela } from '../core/models';

/**
 * A tela principal do utilizador numa fatura.
 *
 * <p>Mostra só o <b>pool</b> (lançamentos sem dono) e o que já é dele. O que
 * outra pessoa assumiu não aparece — decisão de privacidade combinada no projeto.
 */
@Component({
  standalone: true,
  imports: [FormsModule, RouterLink, CurrencyPipe, DatePipe],
  template: `
    @if (dados(); as d) {
      <h1>Fatura de {{ d.fatura.competencia | date: 'MM/yyyy' }}</h1>
      <p class="sub">
        Marque o que foi você. O que ninguém assumir fica com o titular do cartão.
      </p>

      @if (d.fatura.status !== 'EM_AVALIACAO') {
        <div class="aviso">
          Esta fatura não está mais em avaliação — não dá para alterar as contas.
        </div>
      }

      <article class="cartao">
        <div class="linha">
          <strong>Seu total nesta fatura</strong>
          <span class="valor" style="font-size:1.3rem">{{ d.total | currency: 'BRL' }}</span>
        </div>
        @if (d.acerto) {
          <div class="linha" style="margin-top:0.5rem">
            <span class="tag" [class.ok]="d.acerto.status === 'CONFIRMADO'"
                  [class.alerta]="d.acerto.status === 'INFORMADO'">
              {{ rotuloAcerto(d.acerto.status) }}
            </span>
            @if (d.acerto.status === 'ABERTO' && d.fatura.status !== 'EM_AVALIACAO') {
              <button type="button" (click)="informarPagamento()">Já paguei</button>
            }
          </div>
        }
      </article>

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
              </div>
              <div style="text-align:right">
                <div class="valor" [class.credito]="l.valor < 0">
                  {{ l.valor | currency: 'BRL' }}
                </div>
                <button type="button" class="btn-secundario"
                        [disabled]="ocupado() || !aberta()"
                        (click)="devolver(l)">Não foi minha</button>
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
  ocupado = signal(false);

  @Input() set id(valor: string) {
    this.carregar(Number(valor));
  }

  aberta(): boolean {
    return this.dados()?.fatura.status === 'EM_AVALIACAO';
  }

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

  informarPagamento(): void {
    const fatura = this.dados()?.fatura;
    if (!fatura) {
      return;
    }
    this.ocupado.set(true);
    this.api.informarPagamento(fatura.id).subscribe({
      next: () => {
        this.toast.ok('Pagamento informado. O administrador vai confirmar.');
        this.recarregar();
      },
      error: () => this.ocupado.set(false),
    });
  }

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
    return { ABERTO: 'a pagar', INFORMADO: 'aguardando confirmação', CONFIRMADO: 'pago' }[s] ?? s;
  }

  private recarregar(): void {
    const id = this.dados()?.fatura.id;
    if (id) {
      this.carregar(id);
    }
  }

  private carregar(id: number): void {
    this.api.minhasContas(id).subscribe({
      next: (d) => { this.dados.set(d); this.ocupado.set(false); },
      error: () => this.ocupado.set(false),
    });
  }
}

import { CurrencyPipe, DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService } from '../core/api.service';
import { Acerto } from '../core/models';

/** Histórico do utilizador: quanto deveu em cada fatura e o que já foi quitado. */
@Component({
  standalone: true,
  imports: [RouterLink, CurrencyPipe, DatePipe],
  template: `
    <h1>Meus acertos</h1>
    <p class="sub">O que você deve, por fatura.</p>

    @if (emAberto() > 0) {
      <div class="aviso">
        Você tem <strong>{{ emAberto() | currency: 'BRL' }}</strong> em aberto.
      </div>
    }

    @if (acertos().length === 0) {
      <p class="vazio">Nenhum acerto ainda.</p>
    } @else {
      <div class="rolavel">
        <table>
          <thead>
            <tr><th>Competência</th><th class="num">Valor</th><th>Situação</th><th></th></tr>
          </thead>
          <tbody>
            @for (a of acertos(); track a.id) {
              <tr>
                <td>{{ a.competencia | date: 'MM/yyyy' }}</td>
                <td class="num">{{ a.valorDevido | currency: 'BRL' }}</td>
                <td>
                  <span class="tag" [class.ok]="a.status === 'CONFIRMADO'"
                        [class.alerta]="a.status === 'INFORMADO'">
                    {{ rotulo(a.status) }}
                  </span>
                  @if (a.confirmadoEm) {
                    <span class="meta">em {{ a.confirmadoEm | date: 'dd/MM/yyyy' }}</span>
                  }
                </td>
                <td>
                  <a [routerLink]="['/faturas', a.faturaId, 'minhas-contas']">Ver detalhe</a>
                </td>
              </tr>
            }
          </tbody>
        </table>
      </div>
    }
  `,
})
export class MeusAcertosComponent {
  private api = inject(ApiService);

  acertos = signal<Acerto[]>([]);

  emAberto = computed(() =>
    this.acertos()
      .filter((a) => a.status !== 'CONFIRMADO')
      .reduce((s, a) => s + a.valorDevido, 0));

  constructor() {
    this.api.meusAcertos().subscribe({ next: (a) => this.acertos.set(a) });
  }

  rotulo(s: string): string {
    return { ABERTO: 'a pagar', INFORMADO: 'aguardando confirmação', CONFIRMADO: 'pago' }[s] ?? s;
  }
}

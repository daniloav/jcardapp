import { CurrencyPipe, DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService } from '../core/api.service';
import { AuthService } from '../core/auth.service';
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
                Total {{ f.valorTotal | currency: 'BRL' }} ·
                {{ f.totalLancamentos }} lançamentos
              </div>
            </div>
            <div>
              @if (f.noPool > 0 && f.status === 'EM_AVALIACAO') {
                <span class="tag alerta">{{ f.noPool }} sem dono</span>
              }
              @if (f.emConflito > 0) {
                <span class="tag erro">{{ f.emConflito }} em conflito</span>
              }
            </div>
          </div>

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
            }
          </div>
        </article>
      }
    }
  `,
})
export class FaturasComponent {
  private api = inject(ApiService);
  auth = inject(AuthService);

  faturas = signal<Fatura[]>([]);
  carregando = signal(true);

  constructor() {
    this.api.faturas().subscribe({
      next: (f) => { this.faturas.set(f); this.carregando.set(false); },
      error: () => this.carregando.set(false),
    });
  }

  rotulo(s: Fatura['status']): string {
    return {
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
    if (f.status === 'EM_AVALIACAO') return 'info';
    return '';
  }
}

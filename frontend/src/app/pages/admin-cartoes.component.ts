import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { forkJoin } from 'rxjs';
import { ApiService } from '../core/api.service';
import { ToastService } from '../core/toast.service';
import { Cartao, Usuario } from '../core/models';

@Component({
  standalone: true,
  imports: [FormsModule],
  template: `
    <h1>Cartões</h1>
    <p class="sub">
      Cadastre os 4 últimos dígitos de cada cartão adicional. Se o adicional é
      sempre da mesma pessoa, defina o dono padrão e os lançamentos dele já
      entram atribuídos — ninguém precisa reivindicar todo mês.
    </p>

    <div class="aviso info">
      O cartão marcado como <strong>titular</strong> é quem absorve o que ninguém
      assumir na conciliação. Só pode haver um.
    </div>

    <form class="cartao" (ngSubmit)="salvar()">
      <fieldset>
        <legend>{{ editando() ? 'Editar cartão' : 'Novo cartão' }}</legend>
        <label for="apelido">
          Apelido
          <input id="apelido" name="apelido" [(ngModel)]="form.apelido"
                 placeholder="ex.: Adicional do João" required />
        </label>
        <label for="final4">
          4 últimos dígitos
          <input id="final4" name="final4" [(ngModel)]="form.final4"
                 maxlength="4" inputmode="numeric" pattern="[0-9]{4}" required />
        </label>
        <label for="portador">
          Nome do portador como sai na fatura (opcional)
          <input id="portador" name="portador" [(ngModel)]="form.portadorNome" />
        </label>
        <label for="dono">
          Dono padrão
          <select id="dono" name="dono" [(ngModel)]="form.donoPadraoId">
            <option [ngValue]="null">Nenhum — vai para o pool</option>
            @for (u of utilizadores(); track u.id) {
              <option [ngValue]="u.id">{{ u.nome }}</option>
            }
          </select>
        </label>
        <label>
          <input type="checkbox" name="titular" [(ngModel)]="form.titular" />
          É o cartão titular (absorve a sobra)
        </label>
        <label>
          <input type="checkbox" name="ativo" [(ngModel)]="form.ativo" />
          Ativo
        </label>
        <div class="linha">
          <button type="submit">{{ editando() ? 'Salvar' : 'Cadastrar' }}</button>
          @if (editando()) {
            <button type="button" class="btn-secundario" (click)="cancelar()">Cancelar</button>
          }
        </div>
      </fieldset>
    </form>

    <div class="rolavel">
      <table>
        <thead>
          <tr><th>Apelido</th><th>Final</th><th>Dono padrão</th><th></th><th>Ações</th></tr>
        </thead>
        <tbody>
          @for (c of cartoes(); track c.id) {
            <tr [style.opacity]="c.ativo ? 1 : 0.5">
              <td>{{ c.apelido }}</td>
              <td>•••• {{ c.final4 }}</td>
              <td>{{ c.donoPadraoNome ?? '—' }}</td>
              <td>
                @if (c.titular) { <span class="tag info">titular</span> }
                @if (!c.ativo) { <span class="tag erro">inativo</span> }
              </td>
              <td>
                <button type="button" class="btn-secundario" (click)="editar(c)">Editar</button>
                <button type="button" class="btn-perigo" (click)="remover(c)">Remover</button>
              </td>
            </tr>
          }
        </tbody>
      </table>
    </div>
  `,
})
export class AdminCartoesComponent {
  private api = inject(ApiService);
  private toast = inject(ToastService);

  cartoes = signal<Cartao[]>([]);
  utilizadores = signal<Usuario[]>([]);
  editando = signal<Cartao | null>(null);
  form: Partial<Cartao> = this.vazio();

  constructor() {
    this.carregar();
  }

  salvar(): void {
    const acao = this.editando()
      ? this.api.atualizarCartao(this.editando()!.id, this.form)
      : this.api.criarCartao(this.form);

    acao.subscribe({
      next: () => { this.toast.ok('Cartão salvo.'); this.cancelar(); this.carregar(); },
    });
  }

  editar(c: Cartao): void {
    this.editando.set(c);
    this.form = { ...c };
  }

  cancelar(): void {
    this.editando.set(null);
    this.form = this.vazio();
  }

  remover(c: Cartao): void {
    if (!confirm(`Remover o cartão ${c.apelido}?`)) {
      return;
    }
    this.api.removerCartao(c.id).subscribe({
      next: () => { this.toast.ok('Cartão removido.'); this.carregar(); },
    });
  }

  private carregar(): void {
    forkJoin({ cartoes: this.api.cartoes(), utilizadores: this.api.utilizadores() })
      .subscribe({
        next: (r) => { this.cartoes.set(r.cartoes); this.utilizadores.set(r.utilizadores); },
      });
  }

  private vazio(): Partial<Cartao> {
    return {
      apelido: '', final4: '', portadorNome: null,
      donoPadraoId: null, titular: false, ativo: true,
    };
  }
}

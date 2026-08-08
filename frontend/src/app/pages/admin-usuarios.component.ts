import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../core/api.service';
import { ToastService } from '../core/toast.service';
import { Usuario } from '../core/models';

@Component({
  standalone: true,
  imports: [FormsModule],
  template: `
    <h1>Utilizadores</h1>
    <p class="sub">
      Quem pode assumir contas na fatura. O login é gerado do nome e a senha é
      provisória — a pessoa troca no primeiro acesso.
    </p>

    <form class="cartao" (ngSubmit)="salvar()">
      <fieldset>
        <legend>{{ editando() ? 'Editar utilizador' : 'Novo utilizador' }}</legend>
        <label for="nome">
          Nome
          <input id="nome" name="nome" [(ngModel)]="form.nome" required />
        </label>
        <label for="email">
          E-mail (recebe o aviso de fatura nova)
          <input id="email" name="email" type="email" [(ngModel)]="form.email" required />
        </label>
        <label>
          <input type="checkbox" name="utilizador" [(ngModel)]="form.utilizador" />
          Assume contas na fatura
        </label>
        <label>
          <input type="checkbox" name="admin" [(ngModel)]="form.admin" />
          Administrador (importa fatura, arbitra e confirma pagamento)
        </label>
        <label>
          <input type="checkbox" name="notif" [(ngModel)]="form.recebeNotificacoes" />
          Recebe e-mails
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
          <tr><th>Nome</th><th>Login</th><th>E-mail</th><th>Papéis</th><th>Ações</th></tr>
        </thead>
        <tbody>
          @for (u of usuarios(); track u.id) {
            <tr [style.opacity]="u.ativo ? 1 : 0.5">
              <td>{{ u.nome }}</td>
              <td>{{ u.login }}</td>
              <td>{{ u.email }}</td>
              <td>
                @if (u.admin) { <span class="tag info">admin</span> }
                @if (u.utilizador) { <span class="tag">utilizador</span> }
                @if (!u.ativo) { <span class="tag erro">inativo</span> }
                @if (u.precisaTrocarSenha) { <span class="tag alerta">senha provisória</span> }
              </td>
              <td>
                <button type="button" class="btn-secundario" (click)="editar(u)">Editar</button>
                <button type="button" class="btn-secundario" (click)="resetar(u)">
                  Resetar senha
                </button>
                <button type="button" class="btn-perigo" (click)="remover(u)">Remover</button>
              </td>
            </tr>
          }
        </tbody>
      </table>
    </div>
  `,
})
export class AdminUsuariosComponent {
  private api = inject(ApiService);
  private toast = inject(ToastService);

  usuarios = signal<Usuario[]>([]);
  editando = signal<Usuario | null>(null);
  form: Partial<Usuario> = this.vazio();

  constructor() {
    this.carregar();
  }

  salvar(): void {
    const acao = this.editando()
      ? this.api.atualizarUsuario(this.editando()!.id, this.form)
      : this.api.criarUsuario(this.form);

    acao.subscribe({
      next: () => {
        this.toast.ok(this.editando()
          ? 'Utilizador atualizado.'
          : 'Utilizador cadastrado. Enviei o acesso por e-mail.');
        this.cancelar();
        this.carregar();
      },
    });
  }

  editar(u: Usuario): void {
    this.editando.set(u);
    this.form = { ...u };
  }

  cancelar(): void {
    this.editando.set(null);
    this.form = this.vazio();
  }

  resetar(u: Usuario): void {
    this.api.resetarSenha(u.id).subscribe({
      next: () => { this.toast.ok(`Senha de ${u.nome} resetada e enviada por e-mail.`); this.carregar(); },
    });
  }

  remover(u: Usuario): void {
    // Quem já tem histórico é desativado pelo backend, não excluído: apagar
    // deixaria faturas antigas sem dono e quebraria a conciliação.
    if (!confirm(`Remover ${u.nome}? Se já tiver contas assumidas, será apenas desativado.`)) {
      return;
    }
    this.api.removerUsuario(u.id).subscribe({
      next: () => { this.toast.ok('Feito.'); this.carregar(); },
    });
  }

  private carregar(): void {
    this.api.usuarios().subscribe({ next: (u) => this.usuarios.set(u) });
  }

  private vazio(): Partial<Usuario> {
    return { nome: '', email: '', admin: false, utilizador: true, recebeNotificacoes: true };
  }
}

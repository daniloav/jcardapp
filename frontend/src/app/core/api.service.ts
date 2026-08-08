import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  Acerto, Cartao, DetalheFatura, Fatura, Lancamento, MinhasContas, Usuario,
} from './models';

/** Ponto único de acesso à API. Todo componente passa por aqui. */
@Injectable({ providedIn: 'root' })
export class ApiService {
  private http = inject(HttpClient);

  // ------------------------------------------------------------- faturas --

  faturas(): Observable<Fatura[]> {
    return this.http.get<Fatura[]>('/api/faturas');
  }

  fatura(id: number): Observable<DetalheFatura> {
    return this.http.get<DetalheFatura>(`/api/faturas/${id}`);
  }

  minhasContas(faturaId: number): Observable<MinhasContas> {
    return this.http.get<MinhasContas>(`/api/faturas/${faturaId}/minhas-contas`);
  }

  /**
   * @param valorTotal informe só quando o PDF esconder o total; o backend usa
   *                   para conferir contra os lançamentos lidos
   */
  importarFatura(arquivo: File, competencia: string, valorTotal?: string): Observable<Fatura> {
    const form = new FormData();
    form.append('arquivo', arquivo);
    form.append('competencia', competencia);
    if (valorTotal) {
      form.append('valorTotal', valorTotal);
    }
    return this.http.post<Fatura>('/api/faturas', form);
  }

  reprocessarFatura(id: number): Observable<Fatura> {
    return this.http.post<Fatura>(`/api/faturas/${id}/reprocessar`, {});
  }

  conciliar(id: number): Observable<Fatura> {
    return this.http.post<Fatura>(`/api/faturas/${id}/conciliar`, {});
  }

  fechar(id: number): Observable<Fatura> {
    return this.http.post<Fatura>(`/api/faturas/${id}/fechar`, {});
  }

  excluirFatura(id: number): Observable<void> {
    return this.http.delete<void>(`/api/faturas/${id}`);
  }

  conflitos(faturaId: number): Observable<Lancamento[]> {
    return this.http.get<Lancamento[]>(`/api/faturas/${faturaId}/conflitos`);
  }

  acertosDaFatura(faturaId: number): Observable<Acerto[]> {
    return this.http.get<Acerto[]>(`/api/faturas/${faturaId}/acertos`);
  }

  // --------------------------------------------------------- lançamentos --

  reivindicar(lancamentoId: number, observacao?: string): Observable<Lancamento> {
    return this.http.post<Lancamento>(
      `/api/lancamentos/${lancamentoId}/reivindicar`, { observacao: observacao ?? null });
  }

  desistir(lancamentoId: number): Observable<Lancamento> {
    return this.http.delete<Lancamento>(`/api/lancamentos/${lancamentoId}/reivindicar`);
  }

  arbitrar(lancamentoId: number, vencedorId: number): Observable<Lancamento> {
    return this.http.post<Lancamento>(
      `/api/lancamentos/${lancamentoId}/arbitrar`, { vencedorId });
  }

  // -------------------------------------------------------------- acertos --

  meusAcertos(): Observable<Acerto[]> {
    return this.http.get<Acerto[]>('/api/me/acertos');
  }

  informarPagamento(faturaId: number, observacao?: string): Observable<Acerto> {
    return this.http.post<Acerto>(
      `/api/faturas/${faturaId}/pagamento`, { observacao: observacao ?? null });
  }

  confirmarPagamento(acertoId: number): Observable<Acerto> {
    return this.http.post<Acerto>(`/api/acertos/${acertoId}/confirmar`, {});
  }

  reabrirAcerto(acertoId: number): Observable<Acerto> {
    return this.http.post<Acerto>(`/api/acertos/${acertoId}/reabrir`, {});
  }

  // ------------------------------------------------------------ cadastros --

  usuarios(): Observable<Usuario[]> {
    return this.http.get<Usuario[]>('/api/usuarios');
  }

  utilizadores(): Observable<Usuario[]> {
    return this.http.get<Usuario[]>('/api/usuarios/utilizadores');
  }

  criarUsuario(dados: Partial<Usuario>): Observable<Usuario> {
    return this.http.post<Usuario>('/api/usuarios', dados);
  }

  atualizarUsuario(id: number, dados: Partial<Usuario>): Observable<Usuario> {
    return this.http.put<Usuario>(`/api/usuarios/${id}`, dados);
  }

  removerUsuario(id: number): Observable<void> {
    return this.http.delete<void>(`/api/usuarios/${id}`);
  }

  resetarSenha(id: number): Observable<void> {
    return this.http.post<void>(`/api/usuarios/${id}/resetar-senha`, {});
  }

  cartoes(): Observable<Cartao[]> {
    return this.http.get<Cartao[]>('/api/cartoes');
  }

  criarCartao(dados: Partial<Cartao>): Observable<Cartao> {
    return this.http.post<Cartao>('/api/cartoes', dados);
  }

  atualizarCartao(id: number, dados: Partial<Cartao>): Observable<Cartao> {
    return this.http.put<Cartao>(`/api/cartoes/${id}`, dados);
  }

  removerCartao(id: number): Observable<void> {
    return this.http.delete<void>(`/api/cartoes/${id}`);
  }
}

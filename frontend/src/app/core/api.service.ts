import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  Acerto, Cartao, DetalheFatura, Fatura, Lancamento, MinhasContas, Pessoa, Pix, Usuario,
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

  /** Racha a conta. A soma das partes precisa fechar com o valor do lançamento. */
  dividir(lancamentoId: number, partes: { usuarioId: number; valor: number }[]):
    Observable<Lancamento> {
    return this.http.post<Lancamento>(`/api/lancamentos/${lancamentoId}/divisao`, { partes });
  }

  juntarDivisao(lancamentoId: number): Observable<Lancamento> {
    return this.http.delete<Lancamento>(`/api/lancamentos/${lancamentoId}/divisao`);
  }

  // -------------------------------------------------------------- acertos --

  meusAcertos(): Observable<Acerto[]> {
    return this.http.get<Acerto[]>('/api/me/acertos');
  }

  pix(): Observable<Pix> {
    return this.http.get<Pix>('/api/pix');
  }

  /** "Conferi o total e concordo." É o que libera o formulário de pagamento. */
  aceitarValor(faturaId: number): Observable<Acerto> {
    return this.http.post<Acerto>(`/api/faturas/${faturaId}/aceite`, {});
  }

  /** O comprovante é obrigatório — por isso multipart e não JSON. */
  informarPagamento(faturaId: number, comprovante: File,
                    pagoEm: string, observacao?: string): Observable<Acerto> {
    const form = new FormData();
    form.append('comprovante', comprovante);
    form.append('pagoEm', pagoEm);
    if (observacao) {
      form.append('observacao', observacao);
    }
    return this.http.post<Acerto>(`/api/faturas/${faturaId}/pagamento`, form);
  }

  /**
   * Baixa o comprovante como blob em vez de apontar um `<a href>` para a URL:
   * o endpoint exige o JWT, que só o interceptor coloca — um link direto
   * voltaria 401.
   */
  comprovante(acertoId: number): Observable<Blob> {
    return this.http.get(`/api/acertos/${acertoId}/comprovante`, { responseType: 'blob' });
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

  /** Id e nome apenas — é o que o utilizador comum precisa para dividir uma conta. */
  pessoas(): Observable<Pessoa[]> {
    return this.http.get<Pessoa[]>('/api/usuarios/pessoas');
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

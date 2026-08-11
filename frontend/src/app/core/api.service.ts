import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  Acerto, Cartao, DetalheDoUtilizador, DetalheFatura, Fatura, Lancamento, MinhasContas,
  Pessoa, Pix, ResultadoLote, Usuario,
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

  /**
   * Devolve a fatura conciliada para avaliação. O motivo vai no e-mail de quem
   * for afetado: é a operação que mais mexe em dinheiro já combinado.
   */
  reabrirAvaliacao(id: number, motivo?: string): Observable<Fatura> {
    return this.http.post<Fatura>(
      `/api/faturas/${id}/reabrir-avaliacao`, { motivo: motivo ?? null });
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

  /**
   * A conta de uma pessoa aberta linha a linha, para o admin conferir se o
   * encargo foi rateado com ela — e, quando não foi, por quê.
   */
  detalheDoUtilizador(faturaId: number, usuarioId: number): Observable<DetalheDoUtilizador> {
    return this.http.get<DetalheDoUtilizador>(
      `/api/faturas/${faturaId}/utilizadores/${usuarioId}/detalhe`);
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

  /**
   * A mesma decisão para o resultado inteiro de uma busca. Os ids vão da tela
   * para o backend — mandar o termo faria o lote pegar linha que o admin não viu.
   */
  arbitrarLote(lancamentoIds: number[], vencedorId: number): Observable<ResultadoLote> {
    return this.http.post<ResultadoLote>(
      '/api/lancamentos/arbitrar-lote', { vencedorId, lancamentoIds });
  }

  /** Racha a conta. A soma das partes precisa fechar com o valor do lançamento. */
  dividir(lancamentoId: number, partes: { usuarioId: number; valor: number }[]):
    Observable<Lancamento> {
    return this.http.post<Lancamento>(`/api/lancamentos/${lancamentoId}/divisao`, { partes });
  }

  juntarDivisao(lancamentoId: number): Observable<Lancamento> {
    return this.http.delete<Lancamento>(`/api/lancamentos/${lancamentoId}/divisao`);
  }

  /**
   * Batiza o estabelecimento deste lançamento. Vale para todo mundo e para as
   * faturas seguintes — a chave é a loja, não o lançamento.
   */
  apelidar(lancamentoId: number, apelido: string): Observable<unknown> {
    return this.http.post(`/api/lancamentos/${lancamentoId}/apelido`, { apelido });
  }

  removerApelido(lancamentoId: number): Observable<void> {
    return this.http.delete<void>(`/api/lancamentos/${lancamentoId}/apelido`);
  }

  // -------------------------------------------------------------- acertos --

  meusAcertos(): Observable<Acerto[]> {
    return this.http.get<Acerto[]>('/api/me/acertos');
  }

  pix(): Observable<Pix> {
    return this.http.get<Pix>('/api/pix');
  }

  /** Só admin: troca a chave para onde todo mundo paga. */
  salvarPix(tipo: string, chave: string, titular: string): Observable<Pix> {
    return this.http.put<Pix>('/api/pix', { tipo, chave, titular });
  }

  /** "Conferi o total e concordo." É o que libera o formulário de pagamento. */
  aceitarValor(faturaId: number): Observable<Acerto> {
    return this.http.post<Acerto>(`/api/faturas/${faturaId}/aceite`, {});
  }

  /**
   * Declara UMA transferência. O comprovante é obrigatório — por isso multipart
   * e não JSON —, e o valor em branco vale "paguei tudo que faltava".
   */
  informarPagamento(faturaId: number, comprovante: File, valor: string,
                    pagoEm: string, observacao?: string): Observable<Acerto> {
    const form = new FormData();
    form.append('comprovante', comprovante);
    form.append('pagoEm', pagoEm);
    if (valor && valor.trim()) {
      form.append('valor', valor.trim());
    }
    if (observacao) {
      form.append('observacao', observacao);
    }
    return this.http.post<Acerto>(`/api/faturas/${faturaId}/pagamento`, form);
  }

  /**
   * Baixa o comprovante de uma transferência como blob em vez de apontar um
   * `<a href>` para a URL: o endpoint exige o JWT, que só o interceptor coloca —
   * um link direto voltaria 401.
   */
  comprovante(pagamentoId: number): Observable<Blob> {
    return this.http.get(`/api/pagamentos/${pagamentoId}/comprovante`, { responseType: 'blob' });
  }

  /** O admin dá por recebida uma transferência — uma de cada vez. */
  confirmarPagamento(pagamentoId: number): Observable<Acerto> {
    return this.http.post<Acerto>(`/api/pagamentos/${pagamentoId}/confirmar`, {});
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

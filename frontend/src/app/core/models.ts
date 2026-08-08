/** Espelho dos DTOs do backend (br.com.jcard.dto). */

export type StatusFatura =
  | 'IMPORTADA' | 'DIVERGENTE' | 'EM_AVALIACAO' | 'CONCILIADA' | 'FECHADA';

export type StatusAcerto = 'ABERTO' | 'INFORMADO' | 'CONFIRMADO';

export type TipoLancamento =
  | 'COMPRA' | 'ESTORNO' | 'ENCARGO' | 'PAGAMENTO' | 'IOF' | 'ANUIDADE' | 'AJUSTE';

export type OrigemAtribuicao = 'MANUAL' | 'HERDADA_PARCELA' | 'REGRA_CARTAO' | 'ADMIN';

export interface Usuario {
  id: number;
  nome: string;
  login: string;
  email: string;
  admin: boolean;
  utilizador: boolean;
  ativo: boolean;
  recebeNotificacoes: boolean;
  precisaTrocarSenha: boolean;
}

export interface LoginResponse {
  token: string;
  usuario: Usuario;
  precisaTrocarSenha: boolean;
}

export interface Fatura {
  id: number;
  competencia: string;
  vencimento: string | null;
  valorTotal: number;
  valorLancado: number;
  /** Diferente de zero = o parser não leu a fatura inteira. */
  divergencia: number;
  status: StatusFatura;
  emissor: string;
  importadaEm: string;
  totalLancamentos: number;
  noPool: number;
  emConflito: number;
}

export interface Lancamento {
  id: number;
  dataCompra: string;
  descricao: string;
  valor: number;
  portadorNome: string | null;
  final4: string | null;
  parcelaAtual: number | null;
  parcelaTotal: number | null;
  tipo: TipoLancamento;
  responsavelId: number | null;
  responsavelNome: string | null;
  origemAtribuicao: OrigemAtribuicao | null;
  meu: boolean;
  /** Só vem preenchido na fila de conflitos do admin. */
  disputantes: string[] | null;
}

export interface Acerto {
  id: number;
  faturaId: number;
  competencia: string;
  usuarioId: number;
  usuarioNome: string;
  valorDevido: number;
  status: StatusAcerto;
  informadoEm: string | null;
  confirmadoEm: string | null;
  observacao: string | null;
}

export interface MinhasContas {
  fatura: Fatura;
  /** Lançamentos sem dono — o que dá para assumir. */
  pool: Lancamento[];
  meus: Lancamento[];
  total: number;
  acerto: Acerto | null;
}

export interface Cartao {
  id: number;
  apelido: string;
  final4: string;
  portadorNome: string | null;
  donoPadraoId: number | null;
  donoPadraoNome: string | null;
  titular: boolean;
  ativo: boolean;
}

export interface DetalheFatura {
  fatura: Fatura;
  lancamentos: Lancamento[];
  acertos: Acerto[];
}

/**
 * Descrição sem o sufixo de parcela.
 *
 * <p>A descrição vem crua do PDF ("POSTO SHELL CENTRO 03/10") e a tela já mostra
 * a parcela em campo próprio — sem isso o texto sai repetido ("... 03/10 · 3/10").
 * Guardamos a original no backend porque é ela que permite depurar o parser.
 */
export function descricaoSemParcela(l: Lancamento): string {
  if (!l.parcelaTotal) {
    return l.descricao;
  }
  return l.descricao.replace(/\s+\d{1,2}\s*\/\s*\d{1,2}\s*$/, '');
}

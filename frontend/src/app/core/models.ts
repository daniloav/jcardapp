/** Espelho dos DTOs do backend (br.com.jcard.dto). */

export type StatusFatura =
  | 'IMPORTADA' | 'DIVERGENTE' | 'EM_AVALIACAO' | 'CONCILIADA' | 'FECHADA';

export type StatusAcerto = 'ABERTO' | 'ACEITO' | 'INFORMADO' | 'CONFIRMADO';

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

/** Só id e nome: o que o seletor de divisão precisa. */
export interface Pessoa {
  id: number;
  nome: string;
}

/** A parte de uma pessoa numa conta rachada. */
export interface Parte {
  usuarioId: number;
  usuarioNome: string;
  valor: number;
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
  /**
   * Quanto DESTE lançamento é de quem está olhando: o valor cheio quando ele é
   * o único responsável, a fatia quando a conta é dividida ou é um encargo.
   */
  minhaParte: number | null;
  /** As partes, quando a conta é rachada. Vazio quando é de uma pessoa só. */
  divisao: Parte[];
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
  aceitoEm: string | null;
  pagoEm: string | null;
  informadoEm: string | null;
  confirmadoEm: string | null;
  observacao: string | null;
  temComprovante: boolean;
}

/** A chave para onde o acerto é pago. Vem da configuração do backend. */
export interface Pix {
  tipo: string;
  chave: string;
  titular: string;
}

export interface MinhasContas {
  fatura: Fatura;
  /** Lançamentos sem dono — o que dá para assumir. */
  pool: Lancamento[];
  meus: Lancamento[];
  /** IOF, anuidade e afins, já com a fatia que coube a quem está olhando. */
  encargos: Lancamento[];
  totalCompras: number;
  totalEncargos: number;
  total: number;
  acerto: Acerto | null;
  pix: Pix;
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

/** Espelho dos DTOs do backend (br.com.jcard.dto). */

/**
 * `PREVIA` está fora do ciclo das outras: é a parcial do mês que ainda não
 * fechou. Dá para assumir conta nela, mas ninguém deve nada — ela não concilia,
 * não fecha e não gera acerto. Quando a fatura de verdade chega, a prévia é
 * consumida (as atribuições passam para ela) e some.
 */
export type StatusFatura =
  | 'PREVIA' | 'IMPORTADA' | 'DIVERGENTE' | 'EM_AVALIACAO' | 'CONCILIADA' | 'FECHADA';

export type StatusAcerto = 'ABERTO' | 'ACEITO' | 'INFORMADO' | 'CONFIRMADO';

export type TipoLancamento =
  | 'COMPRA' | 'ESTORNO' | 'ENCARGO' | 'PAGAMENTO' | 'IOF' | 'ANUIDADE' | 'AJUSTE';

export type OrigemAtribuicao =
  | 'MANUAL' | 'HERDADA_PARCELA' | 'REGRA_CARTAO' | 'ADMIN' | 'SOBRA_CONCILIACAO';

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

/**
 * O que uma subida de prévia produziu.
 *
 * Os dois números do meio são os que a tela precisa dizer em voz alta: a prévia
 * é sobrescrita, e quem sobe o arquivo tem de saber que o trabalho das pessoas
 * sobreviveu — e quanto dele não sobreviveu.
 */
export interface ResultadoPrevia {
  fatura: Fatura;
  lancamentos: number;
  noPool: number;
  /** Atribuições que a leitura nova reaproveitou. */
  mantidos: number;
  /** As que não casaram com nenhuma linha do arquivo novo e voltaram ao pool. */
  devolvidos: number;
  ignoradas: number;
  /** Parcelas que o app já esperava e que este arquivo trouxe. */
  parcelasConferidas: ParcelaPrevista[];
  /** As que ele esperava e o arquivo não trouxe — continuam como previsão. */
  parcelasAusentes: ParcelaPrevista[];
}

/**
 * Uma parcela que o mês em aberto ainda vai receber.
 *
 * Sai do compromisso de parcelamento: quem assumiu a 1/10 segue dono das nove
 * seguintes, e isso é sabido antes de qualquer arquivo chegar. Não é lançamento
 * — não está no banco, não dá para assumir nem rachar. Vira lançamento no dia em
 * que o CSV a traz.
 */
export interface ParcelaPrevista {
  descricaoNormalizada: string;
  /** O nome que a família deu à loja, quando alguém já deu. */
  apelido: string | null;
  parcela: number;
  parcelaTotal: number;
  /** Estimativa: a próxima parcela pode variar centavos, ou o câmbio. */
  valor: number;
  usuarioId: number;
  usuarioNome: string;
  jaVeio: boolean;
}

/**
 * O mês que ainda não fechou, inteiro: o que o CSV já trouxe e o que os
 * parcelamentos em curso ainda vão trazer.
 *
 * Responde mesmo sem prévia subida — é aí que ele mais serve. No dia 1º não há
 * CSV, mas as parcelas de quem comprou em 10x já são certas.
 */
export interface PreviaDoMes {
  competencia: string;
  /** A prévia subida, ou null enquanto ninguém subiu CSV neste mês. */
  fatura: Fatura | null;
  /** As parcelas de quem está olhando; para o admin, as de todo mundo. */
  parcelas: ParcelaPrevista[];
  totalPrevisto: number;
  /** Se a lista é da família inteira (admin) ou só de quem perguntou. */
  todasAsPessoas: boolean;
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
  /** A chave do estabelecimento — é por ela que o apelido é gravado. */
  descricaoNormalizada: string;
  /** O nome que a família deu à loja; null enquanto ninguém apelidou. */
  apelido: string | null;
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
  /**
   * Quem está olhando já assumiu compra nesta mesma loja em outra fatura. No
   * pool vira "você comprou aqui no mês passado" — a maior parte das compras se
   * repete, e isso transforma leitura em confirmação.
   */
  jaFoiSeu: boolean;
}

/** Uma transferência declarada, com o comprovante dela. */
export interface Pagamento {
  id: number;
  valor: number;
  pagoEm: string;
  observacao: string | null;
  informadoEm: string;
  /** Preenchido quando o admin deu por recebida. */
  confirmadoEm: string | null;
  temComprovante: boolean;
  /**
   * O admin que deu baixa em nome da pessoa — quem pagou e não mandou o
   * comprovante. `null` quando foi ela mesma que declarou, e aí existe anexo.
   */
  registradoPor: string | null;
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
  /** Soma das transferências declaradas, confirmadas ou não. */
  valorPago: number;
  /** `valorDevido - valorPago`: o que ainda falta. */
  saldo: number;
  pagamentos: Pagamento[];
}

/** A chave para onde o acerto é pago. Vem da configuração do backend. */
export interface Pix {
  tipo: string;
  chave: string;
  titular: string;
  /**
   * Se existe chave para mostrar. Sendo `false`, `chave` vem vazia e a tela
   * avisa em vez de oferecer cópia — copiar um aviso de configuração mandaria a
   * pessoa pagar para lugar nenhum.
   */
  configurada: boolean;
  /**
   * De onde saiu: `APP` (o admin salvou pela tela), `AMBIENTE` (ainda vem do
   * `.env` da instalação) ou `NENHUMA`. Só a tela de admin usa.
   */
  origem: 'APP' | 'AMBIENTE' | 'NENHUMA';
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
  /** O que está lançado no nome dela hoje — é este número que o acerto copia. */
  total: number;
  /**
   * Na prévia, as parcelas dela que o mês ainda vai receber. Sempre vazio na
   * fatura de verdade, onde a parcela ou veio ou não veio.
   */
  parcelasPrevistas: ParcelaPrevista[];
  totalPrevisto: number;
  /** `total + totalPrevisto`: o tamanho real do mês dela. */
  totalComPrevisto: number;
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
 * A conta de uma pessoa aberta para o admin conferir, linha a linha.
 *
 * Vem do mesmo rateio que a tela da pessoa: conferir contra um segundo cálculo
 * só provaria que os dois concordam entre si.
 */
export interface DetalheDoUtilizador {
  usuario: Pessoa;
  /** Se ela conta como "usou o cartão" — é isso que decide o rateio do encargo. */
  participante: boolean;
  /** Entre quem os encargos estão sendo divididos. */
  participantes: Pessoa[];
  compras: Lancamento[];
  encargos: Lancamento[];
  totalCompras: number;
  totalEncargos: number;
  total: number;
  /** O acerto gravado, para comparar com o rateio recalculado agora. */
  acerto: Acerto | null;
  /** `acerto - total`: zero quando bate; null quando ainda não há acerto. */
  diferencaAcerto: number | null;
}

/**
 * O que a atribuição em massa fez. O que foi pulado importa tanto quanto o que
 * foi atribuído: é a parte que o admin não vê acontecer.
 */
export interface ResultadoLote {
  /** Quantos lançamentos mudaram de dono. */
  atribuidos: number;
  valor: number;
  /** Já eram da pessoa e ficaram como estavam. */
  jaEram: number;
  /** Caíram na busca mas são rateados entre todos que usaram o cartão. */
  encargos: number;
  usuarioNome: string;
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

/**
 * O nome que a tela mostra: o apelido, quando alguém já deu um à loja.
 *
 * <p>Reconhecer a compra é o trabalho todo do utilizador — "DL*UberRides" é o
 * nome que o banco imprime, não o que a pessoa lembra. A descrição original
 * continua acessível na linha, porque é ela que casa com o extrato.
 */
export function nomeDoLancamento(l: Lancamento): string {
  return l.apelido ?? descricaoSemParcela(l);
}

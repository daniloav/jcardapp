package br.com.jcard.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Uma fatura mensal importada de um PDF.
 *
 * <p>O PDF original <b>não</b> é persistido (dado financeiro sensível): guardamos
 * o {@link #hashPdf} — que também torna a importação idempotente — e o
 * {@link #textoExtraido}, suficiente para reprocessar sem pedir o arquivo de novo.
 */
@Entity
@Table(name = "fatura")
public class Fatura extends EntidadeBase {

    /** Mês de referência, sempre no dia 1. */
    @Column(nullable = false)
    public LocalDate competencia;

    public LocalDate vencimento;

    /** Total impresso na fatura — a verdade contra a qual tudo é conferido. */
    @Column(name = "valor_total", nullable = false, precision = 12, scale = 2)
    public BigDecimal valorTotal;

    /** Soma dos lançamentos lidos. Deve ser igual a {@link #valorTotal}. */
    @Column(name = "valor_lancado", nullable = false, precision = 12, scale = 2)
    public BigDecimal valorLancado = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    public StatusFatura status = StatusFatura.IMPORTADA;

    @Column(name = "hash_pdf", nullable = false, unique = true, length = 64)
    public String hashPdf;

    @Column(name = "nome_arquivo", length = 255)
    public String nomeArquivo;

    @Column(name = "texto_extraido", columnDefinition = "TEXT")
    public String textoExtraido;

    @Column(nullable = false, length = 30)
    public String emissor = "ITAU";

    @Column(name = "importada_em", nullable = false)
    public LocalDateTime importadaEm = LocalDateTime.now();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "importada_por")
    public Usuario importadaPor;

    @Column(name = "fechada_em")
    public LocalDateTime fechadaEm;

    public static Fatura porHash(String hash) {
        return find("hashPdf", hash).firstResult();
    }

    /** Faturas mais recentes primeiro — ordem natural das telas. */
    public static java.util.List<Fatura> recentes() {
        return list("order by competencia desc, id desc");
    }

    /** A divergência entre o total impresso e o que conseguimos ler. */
    public BigDecimal divergencia() {
        return valorTotal.subtract(valorLancado);
    }

    /** Utilizadores só podem mexer enquanto a fatura está em avaliação. */
    public boolean aberta() {
        return status == StatusFatura.EM_AVALIACAO;
    }
}

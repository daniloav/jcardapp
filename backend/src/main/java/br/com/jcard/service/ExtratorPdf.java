package br.com.jcard.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * Extrai o texto do PDF da fatura e calcula o hash do arquivo.
 *
 * <p>O PDF em si nunca é gravado — é dado financeiro de terceiros. Guardamos só
 * o texto (para reprocessar) e o SHA-256 (para impedir importar duas vezes).
 */
@ApplicationScoped
public class ExtratorPdf {

    /** Faturas de cartão têm poucas páginas; acima disso é arquivo errado. */
    private static final int MAX_PAGINAS = 40;

    public String hash(byte[] pdf) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(pdf));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível na JVM", e);
        }
    }

    /**
     * Texto do PDF preservando a ordem visual das colunas.
     *
     * <p>{@code setSortByPosition(true)} é o que faz "data · descrição · valor"
     * sair na mesma linha: sem isso o PDFBox devolve na ordem interna do arquivo
     * e as colunas se embaralham.
     */
    public String texto(byte[] pdf) {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            if (doc.isEncrypted()) {
                throw new WebApplicationException(
                        "O PDF está protegido por senha. Salve uma cópia sem senha e envie de novo.", 422);
            }
            if (doc.getNumberOfPages() > MAX_PAGINAS) {
                throw new WebApplicationException(
                        "PDF com " + doc.getNumberOfPages() + " páginas — não parece uma fatura.", 422);
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setLineSeparator("\n");
            return stripper.getText(doc);
        } catch (IOException e) {
            throw new WebApplicationException(
                    "Não consegui ler o PDF: " + e.getMessage(), 422);
        }
    }

    /**
     * O arquivo é um PDF?
     *
     * <p>Decidimos pela assinatura {@code %PDF} e não pela extensão: o nome do
     * arquivo mente com facilidade, e um PDF renomeado para .csv acabaria no
     * parser errado.
     */
    public boolean ehPdf(byte[] conteudo) {
        if (conteudo == null || conteudo.length < 5) {
            throw new WebApplicationException("Arquivo vazio.", 400);
        }
        return "%PDF".equals(new String(conteudo, 0, 4, StandardCharsets.ISO_8859_1));
    }
}

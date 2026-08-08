package br.com.jcard.parser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Chave que identifica <b>a mesma compra parcelada</b> em faturas de meses
 * diferentes — é o que faz a parcela 4/10 cair no nome de quem assumiu a 1/10.
 *
 * <p>Deliberadamente <b>não</b> inclui o valor da parcela: um parcelamento de
 * R$ 100 em 3x sai 33,34 + 33,33 + 33,33, e travar no centavo quebraria o
 * casamento. A proteção contra colisão (duas compras iguais na mesma loja, mesmo
 * número de parcelas) fica com a checagem de valor aproximado no
 * {@code AtribuicaoService}, que prefere deixar no pool a errar o dono.
 */
public final class ChaveParcelamento {

    private ChaveParcelamento() {
    }

    public static String de(String descricaoNormalizada, String final4, int parcelaTotal) {
        String base = descricaoNormalizada + "|" + (final4 == null ? "" : final4) + "|" + parcelaTotal;
        return sha256(base);
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível na JVM", e);
        }
    }
}

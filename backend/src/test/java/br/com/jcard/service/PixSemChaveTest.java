package br.com.jcard.service;

import br.com.jcard.dto.Responses;
import br.com.jcard.model.ConfiguracaoPix;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * O app tem de <b>subir</b> sem {@code JCARD_PIX_CHAVE} — é o estado normal de
 * quem acabou de clonar, e de qualquer ambiente antes de o titular informar a
 * chave. Um teste unitário não cobre isto: a falha aparecia na injeção da
 * configuração, com o Quarkus recusando iniciar.
 */
@QuarkusTest
@TestProfile(PixSemChaveTest.SemChave.class)
class PixSemChaveTest {

    public static class SemChave implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("jcard.pix.chave", "");
        }
    }

    @Inject
    PixConfig pix;

    @BeforeEach
    @Transactional
    void semNadaSalvo() {
        // A tabela vazia é a outra metade do caso: nem ambiente, nem tela.
        ConfiguracaoPix.deleteAll();
    }

    @Test
    @DisplayName("sem a chave configurada o app sobe e a resposta diz que falta configurar")
    void sobeSemChave() {
        assertFalse(pix.atual().configurada());
        assertEquals("", pix.atual().chave());
        assertEquals(Responses.OrigemPix.NENHUMA, pix.atual().origem());
    }
}

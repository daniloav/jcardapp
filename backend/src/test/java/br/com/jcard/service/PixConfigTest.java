package br.com.jcard.service;

import br.com.jcard.dto.Responses;
import br.com.jcard.dto.Responses.OrigemPix;
import br.com.jcard.model.Auditoria;
import br.com.jcard.model.ConfiguracaoPix;
import br.com.jcard.model.Usuario;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * De onde sai a chave PIX: do que o admin salvou na tela, ou da variável de
 * ambiente enquanto ele não salvar nada.
 *
 * <p>A ordem entre as duas é a regra que importa. Se o ambiente vencesse o que
 * foi salvo, o admin trocaria a chave na tela, veria a antiga continuar
 * aparecendo para todo mundo, e o dinheiro iria para a conta errada.
 */
@QuarkusTest
class PixConfigTest {

    @Inject
    PixConfig pix;

    private Usuario titular;

    @BeforeEach
    @Transactional
    void limpar() {
        ConfiguracaoPix.deleteAll();
        Auditoria.deleteAll();
        Usuario.delete("login", "jose.pix");
        titular = new Usuario();
        titular.nome = "Jose Titular";
        titular.email = "jose.pix@teste.local";
        titular.login = "jose.pix";
        titular.senhaHash = "x";
        titular.admin = true;
        titular.utilizador = true;
        titular.precisaTrocarSenha = false;
        titular.persist();
    }

    @Test
    @DisplayName("sem nada salvo vale a variável de ambiente da instalação")
    void caiNoAmbiente() {
        Responses.PixResponse r = pix.atual();
        assertTrue(r.configurada());
        // Vem do %test.jcard.pix.chave — em produção, do .env da VM.
        assertEquals("teste@exemplo.com", r.chave());
        assertEquals(OrigemPix.AMBIENTE, r.origem());
    }

    @Test
    @DisplayName("o que o admin salva na tela passa a valer sobre o ambiente")
    void oSalvoVence() {
        pix.salvar("CPF", " 000.000.000-00 ", " Jose Titular ", titular);

        Responses.PixResponse r = pix.atual();
        assertEquals("000.000.000-00", r.chave(), "salvo com os espaços aparados");
        assertEquals("Jose Titular", r.titular());
        assertEquals(OrigemPix.APP, r.origem());
    }

    @Test
    @DisplayName("salvar de novo troca a chave, não cria uma segunda")
    void salvarDuasVezesNaoDuplica() {
        pix.salvar("CPF", "000.000.000-00", "Jose Titular", titular);
        pix.salvar("E-MAIL", "jose@exemplo.com", "Jose Titular", titular);

        assertEquals(1, ConfiguracaoPix.count(), "a chave para onde o dinheiro vai é uma só");
        assertEquals("jose@exemplo.com", pix.atual().chave());
    }

    @Test
    @DisplayName("a troca vai para a auditoria com a chave velha e a nova")
    void trocaFicaNaAuditoria() {
        pix.salvar("CPF", "000.000.000-00", "Jose Titular", titular);
        pix.salvar("E-MAIL", "jose@exemplo.com", "Jose Titular", titular);

        long registros = Auditoria.count("entidade", "ConfiguracaoPix");
        assertEquals(2, registros);
        Auditoria ultima = Auditoria.find("entidade = ?1 order by id desc", "ConfiguracaoPix")
                .firstResult();
        assertTrue(ultima.detalhe.contains("000.000.000-00"), "a chave anterior");
        assertTrue(ultima.detalhe.contains("jose@exemplo.com"), "a chave nova");
        assertEquals("Jose Titular", ultima.usuarioNome);
    }

    @Test
    @DisplayName("chave em branco é recusada — sobraria uma tela dizendo que está tudo certo")
    void chaveEmBrancoNaoPassa() {
        assertThrows(WebApplicationException.class,
                () -> pix.salvar("CPF", "   ", "Jose Titular", titular));
        assertThrows(WebApplicationException.class,
                () -> pix.salvar("", "000.000.000-00", "Jose Titular", titular));
        assertThrows(WebApplicationException.class,
                () -> pix.salvar("CPF", "000.000.000-00", " ", titular));
        assertEquals(0, ConfiguracaoPix.count());
    }
}

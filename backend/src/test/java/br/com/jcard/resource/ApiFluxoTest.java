package br.com.jcard.resource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import br.com.jcard.model.Acerto;
import br.com.jcard.model.Cartao;
import br.com.jcard.model.CompromissoParcelado;
import br.com.jcard.model.Fatura;
import br.com.jcard.model.Lancamento;
import br.com.jcard.model.Reivindicacao;
import br.com.jcard.model.Usuario;
import br.com.jcard.service.AuthService;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Percorre a API por HTTP de verdade.
 *
 * <p>Existe porque os testes de serviço rodam dentro de uma transação e não
 * reproduzem o contexto real da requisição — foi assim que passou despercebido
 * um {@code detached entity passed to persist} na troca de senha, que só
 * aparecia no app rodando.
 */
@QuarkusTest
class ApiFluxoTest {

    private static final String SENHA_INICIAL = "provisoria123";

    @Inject
    AuthService auth;

    @BeforeEach
    @Transactional
    void preparar() {
        Acerto.deleteAll();
        Reivindicacao.deleteAll();
        CompromissoParcelado.deleteAll();
        Lancamento.deleteAll();
        Fatura.deleteAll();
        Cartao.deleteAll();
        Usuario.deleteAll();

        Usuario admin = new Usuario();
        admin.nome = "Titular";
        admin.login = "titular";
        admin.email = "titular@teste.local";
        admin.senhaHash = auth.hash(SENHA_INICIAL);
        admin.admin = true;
        admin.utilizador = true;
        admin.precisaTrocarSenha = true;
        admin.persist();
    }

    @Test
    @DisplayName("login devolve token e sinaliza a senha provisória")
    void login() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"login":"titular","senha":"%s"}""".formatted(SENHA_INICIAL))
            .when().post("/api/auth/login")
            .then().statusCode(200)
                .body("token", notNullValue())
                .body("precisaTrocarSenha", equalTo(true))
                .body("usuario.admin", equalTo(true));
    }

    @Test
    @DisplayName("senha errada não revela se o login existe")
    void senhaErrada() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"login":"titular","senha":"chute"}""")
            .when().post("/api/auth/login")
            .then().statusCode(401)
                .body("message", equalTo("Login ou senha inválidos."));

        given().contentType(ContentType.JSON)
                .body("""
                        {"login":"ninguem","senha":"chute"}""")
            .when().post("/api/auth/login")
            .then().statusCode(401)
                .body("message", equalTo("Login ou senha inválidos."));
    }

    /** A regressão que motivou este arquivo. */
    @Test
    @DisplayName("troca de senha funciona pela API e devolve token sem a trava")
    void trocaDeSenha() {
        String token = entrar(SENHA_INICIAL);

        String novoToken = given().contentType(ContentType.JSON).auth().oauth2(token)
                .body("""
                        {"senhaAtual":"%s","senhaNova":"outraSenha456"}""".formatted(SENHA_INICIAL))
            .when().put("/api/me/senha")
            .then().statusCode(200)
                .body("precisaTrocarSenha", equalTo(false))
                .extract().path("token");

        // O token novo já passa pelas rotas que exigem senha trocada.
        given().auth().oauth2(novoToken)
            .when().get("/api/faturas")
            .then().statusCode(200);

        // E a senha antiga não vale mais.
        given().contentType(ContentType.JSON)
                .body("""
                        {"login":"titular","senha":"%s"}""".formatted(SENHA_INICIAL))
            .when().post("/api/auth/login")
            .then().statusCode(401);
    }

    @Test
    @DisplayName("senha curta é recusada")
    void senhaCurta() {
        String token = entrar(SENHA_INICIAL);
        given().contentType(ContentType.JSON).auth().oauth2(token)
                .body("""
                        {"senhaAtual":"%s","senhaNova":"1234"}""".formatted(SENHA_INICIAL))
            .when().put("/api/me/senha")
            .then().statusCode(400);
    }

    @Test
    @DisplayName("quem está com a senha provisória não navega no app")
    void senhaProvisoriaBloqueia() {
        given().auth().oauth2(entrar(SENHA_INICIAL))
            .when().get("/api/faturas")
            .then().statusCode(403);
    }

    @Test
    @DisplayName("sem token, a API não responde")
    void semToken() {
        given().when().get("/api/faturas").then().statusCode(401);
        given().when().get("/api/usuarios").then().statusCode(401);
    }

    @Test
    @DisplayName("admin cadastra utilizador, que entra com a senha padrão e troca")
    void cicloDeCadastro() {
        String admin = trocarESeguir();

        given().contentType(ContentType.JSON).auth().oauth2(admin)
                .body("""
                        {"nome":"Joao Filho","email":"joao@teste.local",
                         "admin":false,"utilizador":true,"recebeNotificacoes":true}""")
            .when().post("/api/usuarios")
            .then().statusCode(200)
                .body("login", equalTo("joao.filho"))
                .body("precisaTrocarSenha", equalTo(true));

        // A senha padrão do sistema é a do application.properties.
        String tokenJoao = given().contentType(ContentType.JSON)
                .body("""
                        {"login":"joao.filho","senha":"12345678"}""")
            .when().post("/api/auth/login")
            .then().statusCode(200).extract().path("token");

        given().contentType(ContentType.JSON).auth().oauth2(tokenJoao)
                .body("""
                        {"senhaAtual":"12345678","senhaNova":"senhaDoJoao1"}""")
            .when().put("/api/me/senha")
            .then().statusCode(200).body("precisaTrocarSenha", equalTo(false));
    }

    @Test
    @DisplayName("utilizador comum não acessa área de admin")
    void utilizadorNaoEAdmin() {
        String admin = trocarESeguir();
        given().contentType(ContentType.JSON).auth().oauth2(admin)
                .body("""
                        {"nome":"Maria Filha","email":"maria@teste.local",
                         "admin":false,"utilizador":true,"recebeNotificacoes":true}""")
            .when().post("/api/usuarios").then().statusCode(200);

        String tokenMaria = given().contentType(ContentType.JSON)
                .body("""
                        {"login":"maria.filha","senha":"12345678"}""")
            .when().post("/api/auth/login").then().extract().path("token");
        tokenMaria = given().contentType(ContentType.JSON).auth().oauth2(tokenMaria)
                .body("""
                        {"senhaAtual":"12345678","senhaNova":"senhaDaMaria1"}""")
            .when().put("/api/me/senha").then().extract().path("token");

        given().auth().oauth2(tokenMaria).when().get("/api/usuarios").then().statusCode(403);
        given().auth().oauth2(tokenMaria).when().get("/api/cartoes").then().statusCode(403);
    }

    @Test
    @DisplayName("importar algo que não é PDF é recusado")
    void arquivoInvalido() {
        given().auth().oauth2(trocarESeguir())
                .multiPart("arquivo", "fatura.pdf", "isto nao e um pdf".getBytes())
                .multiPart("competencia", "2026-08")
            .when().post("/api/faturas")
            .then().statusCode(400)
                .body("message", equalTo("O arquivo enviado não é um PDF."));
    }

    @Test
    @DisplayName("competência mal formatada é recusada com mensagem clara")
    void competenciaInvalida() {
        given().auth().oauth2(trocarESeguir())
                .multiPart("arquivo", "fatura.pdf", "%PDF-1.4 qualquer".getBytes())
                .multiPart("competencia", "agosto")
            .when().post("/api/faturas")
            .then().statusCode(400)
                .body("message", equalTo("Competência inválida: use AAAA-MM (ex.: 2026-08)."));
    }

    /**
     * Regressão: estes endpoints montam DTO a partir de entidade e já quebraram
     * com {@code LazyInitializationException} quando a transação fechava antes
     * do mapeamento. Percorrer todos por HTTP é o que garante que continuem de pé.
     */
    @Test
    @DisplayName("endpoints de leitura respondem sem estourar em associação lazy")
    void leiturasNaoQuebram() {
        String admin = trocarESeguir();

        given().auth().oauth2(admin).when().get("/api/faturas").then().statusCode(200);
        given().auth().oauth2(admin).when().get("/api/me/acertos").then().statusCode(200);
        given().auth().oauth2(admin).when().get("/api/usuarios").then().statusCode(200);
        given().auth().oauth2(admin).when().get("/api/usuarios/utilizadores").then().statusCode(200);
        given().auth().oauth2(admin).when().get("/api/cartoes").then().statusCode(200);
    }

    @Test
    @DisplayName("cartão com dono padrão volta na listagem com o nome do dono")
    void cartaoComDonoPadrao() {
        String admin = trocarESeguir();

        int donoId = given().contentType(ContentType.JSON).auth().oauth2(admin)
                .body("""
                        {"nome":"Pedro Filho","email":"pedro@teste.local",
                         "admin":false,"utilizador":true,"recebeNotificacoes":true}""")
            .when().post("/api/usuarios").then().statusCode(200).extract().path("id");

        given().contentType(ContentType.JSON).auth().oauth2(admin)
                .body("""
                        {"apelido":"Adicional do Pedro","final4":"9012","portadorNome":"PEDRO H",
                         "donoPadraoId":%d,"titular":false,"ativo":true}""".formatted(donoId))
            .when().post("/api/cartoes").then().statusCode(200);

        // Aqui mora a associação lazy que quebrava: donoPadrao.nome.
        given().auth().oauth2(admin)
            .when().get("/api/cartoes")
            .then().statusCode(200)
                .body("[0].donoPadraoNome", equalTo("Pedro Filho"));
    }

    @Test
    @DisplayName("cartão exige 4 dígitos no final")
    void cartaoValidado() {
        given().contentType(ContentType.JSON).auth().oauth2(trocarESeguir())
                .body("""
                        {"apelido":"Adicional","final4":"12","portadorNome":null,
                         "donoPadraoId":null,"titular":false,"ativo":true}""")
            .when().post("/api/cartoes")
            .then().statusCode(400);
    }

    // ------------------------------------------------------------- apoio --

    private String entrar(String senha) {
        return given().contentType(ContentType.JSON)
                .body("""
                        {"login":"titular","senha":"%s"}""".formatted(senha))
            .when().post("/api/auth/login")
            .then().statusCode(200).extract().path("token");
    }

    /** Loga e já troca a senha provisória, devolvendo um token utilizável. */
    private String trocarESeguir() {
        return given().contentType(ContentType.JSON).auth().oauth2(entrar(SENHA_INICIAL))
                .body("""
                        {"senhaAtual":"%s","senhaNova":"senhaDefinitiva1"}""".formatted(SENHA_INICIAL))
            .when().put("/api/me/senha")
            .then().statusCode(200).extract().path("token");
    }
}

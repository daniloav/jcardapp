package br.com.jcard.resource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import br.com.jcard.model.Acerto;
import br.com.jcard.model.Cartao;
import br.com.jcard.model.ComprovantePagamento;
import br.com.jcard.model.CompromissoParcelado;
import br.com.jcard.model.ConfiguracaoPix;
import br.com.jcard.model.DivisaoLancamento;
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
        // Ordem importa: filho antes do pai, senão a FK barra o delete.
        ComprovantePagamento.deleteAll();
        Acerto.deleteAll();
        Reivindicacao.deleteAll();
        DivisaoLancamento.deleteAll();
        CompromissoParcelado.deleteAll();
        Lancamento.deleteAll();
        Fatura.deleteAll();
        Cartao.deleteAll();
        // Antes do usuário: a chave salva aponta para quem a trocou.
        ConfiguracaoPix.deleteAll();
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

    /**
     * Arquivo que não é PDF cai no leitor de CSV — e um lixo qualquer é recusado
     * com a instrução do cabeçalho esperado, não com um erro genérico.
     */
    @Test
    @DisplayName("arquivo que não é PDF nem CSV válido é recusado com instrução")
    void arquivoInvalido() {
        given().auth().oauth2(trocarESeguir())
                .multiPart("arquivo", "fatura.pdf", "isto nao e um pdf".getBytes())
                .multiPart("competencia", "2026-08")
            .when().post("/api/faturas")
            .then().statusCode(422)
                .body("message", org.hamcrest.Matchers.containsString("pagina;coluna;data"));
    }

    @Test
    @DisplayName("CSV válido é importado, com o total informado por quem importa")
    void importarCsv() {
        String csv = """
                pagina;coluna;data;estabelecimento;parcela;valor
                2;1;08/07;PADARIA DO BAIRRO;;120,00
                2;2;09/07;LOJA PARCELADA;03/10;80,00
                """;
        given().auth().oauth2(trocarESeguir())
                .multiPart("arquivo", "fatura.csv", csv.getBytes())
                .multiPart("competencia", "2026-08")
                .multiPart("valorTotal", "200,00")
            .when().post("/api/faturas")
            .then().statusCode(200)
                .body("totalLancamentos", equalTo(2))
                .body("emissor", equalTo("ITAU_CSV"))
                .body("status", equalTo("EM_AVALIACAO"));
    }

    @Test
    @DisplayName("admin exclui a fatura e ela some da listagem")
    void excluirFatura() {
        String admin = trocarESeguir();
        String csv = """
                pagina;coluna;data;estabelecimento;parcela;valor
                2;1;08/07;PADARIA DO BAIRRO;;120,00
                """;
        int id = given().auth().oauth2(admin)
                .multiPart("arquivo", "fatura.csv", csv.getBytes())
                .multiPart("competencia", "2026-08")
                .multiPart("valorTotal", "120,00")
            .when().post("/api/faturas")
            .then().statusCode(200).extract().path("id");

        // Sem @Transactional no endpoint, este DELETE devolvia 500 — por isso ele
        // é exercido por HTTP e não só no serviço.
        given().auth().oauth2(admin).when().delete("/api/faturas/" + id)
            .then().statusCode(204);

        given().auth().oauth2(admin).when().get("/api/faturas")
            .then().statusCode(200).body("size()", equalTo(0));
    }

    @Test
    @DisplayName("utilizador comum não exclui fatura")
    void utilizadorNaoExcluiFatura() {
        String admin = trocarESeguir();
        given().contentType(ContentType.JSON).auth().oauth2(admin)
                .body("""
                        {"nome":"Pedro Filho","email":"pedro@teste.local",
                         "admin":false,"utilizador":true,"recebeNotificacoes":true}""")
            .when().post("/api/usuarios").then().statusCode(200);

        String tokenPedro = given().contentType(ContentType.JSON)
                .body("""
                        {"login":"pedro.filho","senha":"12345678"}""")
            .when().post("/api/auth/login").then().extract().path("token");
        tokenPedro = given().contentType(ContentType.JSON).auth().oauth2(tokenPedro)
                .body("""
                        {"senhaAtual":"12345678","senhaNova":"senhaDoPedro1"}""")
            .when().put("/api/me/senha").then().extract().path("token");

        given().auth().oauth2(tokenPedro).when().delete("/api/faturas/1")
            .then().statusCode(403);
    }

    @Test
    @DisplayName("a chave PIX vem da configuração, para a tela mostrar e copiar")
    void chavePix() {
        given().auth().oauth2(trocarESeguir()).when().get("/api/pix")
            .then().statusCode(200)
                .body("tipo", equalTo("E-MAIL"))
                .body("chave", equalTo("teste@exemplo.com"))
                .body("titular", notNullValue())
                // É esta flag que autoriza a tela a mostrar o botão de copiar.
                .body("configurada", equalTo(true))
                // Nada salvo ainda: quem responde é o .env da instalação.
                .body("origem", equalTo("AMBIENTE"));
    }

    @Test
    @DisplayName("o admin troca a chave PIX pela tela, sem entrar na VM")
    void adminTrocaAChavePix() {
        String admin = trocarESeguir();
        given().contentType(ContentType.JSON).auth().oauth2(admin)
                .body("""
                        {"tipo":"E-MAIL","chave":"jose@exemplo.com","titular":"Jose Titular"}""")
            .when().put("/api/pix")
            .then().statusCode(200)
                .body("chave", equalTo("jose@exemplo.com"))
                .body("origem", equalTo("APP"));

        // E é a nova que a tela de pagamento passa a mostrar.
        given().auth().oauth2(admin).when().get("/api/pix")
            .then().statusCode(200)
                .body("chave", equalTo("jose@exemplo.com"))
                .body("titular", equalTo("Jose Titular"))
                .body("origem", equalTo("APP"));
    }

    @Test
    @DisplayName("chave em branco é recusada com 400, e a anterior continua valendo")
    void chavePixEmBrancoNaoPassa() {
        given().contentType(ContentType.JSON).auth().oauth2(trocarESeguir())
                .body("""
                        {"tipo":"CPF","chave":"   ","titular":"Jose Titular"}""")
            .when().put("/api/pix")
            .then().statusCode(400);
    }

    @Test
    @DisplayName("utilizador não troca a chave PIX — é para onde o dinheiro de todos vai")
    void utilizadorNaoTrocaAChavePix() {
        String admin = trocarESeguir();
        criarUtilizador(admin, "Pedro Filho", "pedro@teste.local");
        String pedro = entrarComoUtilizador("pedro.filho");

        given().contentType(ContentType.JSON).auth().oauth2(pedro)
                .body("""
                        {"tipo":"CPF","chave":"111.111.111-11","titular":"Pedro"}""")
            .when().put("/api/pix")
            .then().statusCode(403);
    }

    @Test
    @DisplayName("minhas-contas devolve os blocos de encargo e a chave PIX")
    void minhasContasTemEncargosEPix() {
        String admin = trocarESeguir();
        String csv = """
                pagina;coluna;data;estabelecimento;parcela;valor
                2;1;08/07;PADARIA DO BAIRRO;;120,00
                2;1;10/07;ANUIDADE DIFERENCIADA;;30,00
                """;
        int id = given().auth().oauth2(admin)
                .multiPart("arquivo", "fatura.csv", csv.getBytes())
                .multiPart("competencia", "2026-08")
                .multiPart("valorTotal", "150,00")
            .when().post("/api/faturas")
            .then().statusCode(200).extract().path("id");

        given().auth().oauth2(admin).when().get("/api/faturas/" + id + "/minhas-contas")
            .then().statusCode(200)
                .body("pix.chave", equalTo("teste@exemplo.com"))
                .body("pix.configurada", equalTo(true))
                .body("total", notNullValue())
                // A anuidade não entra no pool: ela é rateada, não reivindicada.
                .body("pool.size()", equalTo(1));
    }

    /**
     * A conferência do admin: a conta de cada pessoa aberta linha a linha.
     *
     * <p>É a tela que responde "esse encargo foi rateado com ela ou não?" sem
     * abrir o banco. O teste percorre o caso que gera a dúvida: duas pessoas na
     * fatura, uma que assumiu e outra que não.
     */
    @Test
    @DisplayName("o detalhe da conta abre a fatia do encargo e bate com o acerto gravado")
    void detalheDaContaMostraOEncargoRateado() {
        String admin = trocarESeguir();
        int joaoId = criarUtilizador(admin, "Joao Filho", "joao@teste.local");
        criarUtilizador(admin, "Maria Filha", "maria@teste.local");
        String tokenJoao = entrarComoUtilizador("joao.filho");

        String csv = """
                pagina;coluna;data;estabelecimento;parcela;valor
                2;1;08/07;PADARIA DO BAIRRO;;120,00
                2;1;10/07;ANUIDADE DIFERENCIADA;;30,00
                """;
        int faturaId = given().auth().oauth2(admin)
                .multiPart("arquivo", "fatura.csv", csv.getBytes())
                .multiPart("competencia", "2026-08")
                .multiPart("valorTotal", "150,00")
            .when().post("/api/faturas")
            .then().statusCode(200).extract().path("id");

        int padariaId = given().auth().oauth2(tokenJoao)
                .when().get("/api/faturas/" + faturaId + "/minhas-contas")
            .then().statusCode(200).extract().path("pool[0].id");
        given().contentType(ContentType.JSON).auth().oauth2(tokenJoao).body("{}")
            .when().post("/api/lancamentos/" + padariaId + "/reivindicar")
            .then().statusCode(200);

        // O João é o único que usou o cartão: a anuidade inteira é dele, e o
        // detalhe tem de mostrar de onde vêm os R$ 150,00 — não só o total.
        given().auth().oauth2(admin)
                .when().get("/api/faturas/" + faturaId + "/utilizadores/" + joaoId + "/detalhe")
            .then().statusCode(200)
                .body("participante", equalTo(true))
                .body("participantes.size()", equalTo(1))
                .body("compras.size()", equalTo(1))
                .body("compras[0].minhaParte", equalTo(120.00f))
                .body("encargos.size()", equalTo(1))
                .body("encargos[0].minhaParte", equalTo(30.00f))
                .body("totalCompras", equalTo(120.00f))
                .body("totalEncargos", equalTo(30.00f))
                .body("total", equalTo(150.00f))
                // O acerto gravado tem de reproduzir o rateio recalculado agora:
                // é essa comparação que denuncia acerto congelado.
                .body("acerto.valorDevido", equalTo(150.00f))
                .body("diferencaAcerto", equalTo(0.00f));
    }

    @Test
    @DisplayName("quem não assumiu nada aparece fora do rateio, com quem divide os encargos")
    void detalheDeQuemNaoAssumiuNadaExplicaOPorque() {
        String admin = trocarESeguir();
        int joaoId = criarUtilizador(admin, "Joao Filho", "joao@teste.local");
        int mariaId = criarUtilizador(admin, "Maria Filha", "maria@teste.local");
        String tokenJoao = entrarComoUtilizador("joao.filho");

        String csv = """
                pagina;coluna;data;estabelecimento;parcela;valor
                2;1;08/07;PADARIA DO BAIRRO;;120,00
                2;1;10/07;ANUIDADE DIFERENCIADA;;30,00
                """;
        int faturaId = given().auth().oauth2(admin)
                .multiPart("arquivo", "fatura.csv", csv.getBytes())
                .multiPart("competencia", "2026-08")
                .multiPart("valorTotal", "150,00")
            .when().post("/api/faturas")
            .then().statusCode(200).extract().path("id");

        int padariaId = given().auth().oauth2(tokenJoao)
                .when().get("/api/faturas/" + faturaId + "/minhas-contas")
            .then().statusCode(200).extract().path("pool[0].id");
        given().contentType(ContentType.JSON).auth().oauth2(tokenJoao).body("{}")
            .when().post("/api/lancamentos/" + padariaId + "/reivindicar")
            .then().statusCode(200);

        // A Maria não assumiu nada: não usou o cartão, então nenhum encargo é
        // dividido com ela — e a tela diz entre quem ele está sendo dividido.
        given().auth().oauth2(admin)
                .when().get("/api/faturas/" + faturaId + "/utilizadores/" + mariaId + "/detalhe")
            .then().statusCode(200)
                .body("participante", equalTo(false))
                .body("compras.size()", equalTo(0))
                .body("encargos.size()", equalTo(0))
                .body("total", equalTo(0.00f))
                .body("participantes.size()", equalTo(1))
                .body("participantes[0].id", equalTo(joaoId))
                .body("acerto", org.hamcrest.Matchers.nullValue());
    }

    @Test
    @DisplayName("utilizador comum não abre a conta de outra pessoa")
    void detalheDaContaESoDoAdmin() {
        String admin = trocarESeguir();
        int joaoId = criarUtilizador(admin, "Joao Filho", "joao@teste.local");
        String tokenJoao = entrarComoUtilizador("joao.filho");

        given().auth().oauth2(tokenJoao)
                .when().get("/api/faturas/1/utilizadores/" + joaoId + "/detalhe")
            .then().statusCode(403);
    }

    @Test
    @DisplayName("CSV sem o total informado explica o que falta")
    void csvSemTotal() {
        String csv = """
                pagina;coluna;data;estabelecimento;parcela;valor
                2;1;08/07;PADARIA DO BAIRRO;;120,00
                """;
        given().auth().oauth2(trocarESeguir())
                .multiPart("arquivo", "fatura.csv", csv.getBytes())
                .multiPart("competencia", "2026-08")
            .when().post("/api/faturas")
            .then().statusCode(422)
                .body("message", org.hamcrest.Matchers.containsString("não traz o total"));
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

    private int criarUtilizador(String tokenAdmin, String nome, String email) {
        return given().contentType(ContentType.JSON).auth().oauth2(tokenAdmin)
                .body("""
                        {"nome":"%s","email":"%s",
                         "admin":false,"utilizador":true,"recebeNotificacoes":true}"""
                        .formatted(nome, email))
            .when().post("/api/usuarios")
            .then().statusCode(200).extract().path("id");
    }

    /** Entra com a senha padrão do cadastro e já troca: o token sai utilizável. */
    private String entrarComoUtilizador(String login) {
        String provisorio = given().contentType(ContentType.JSON)
                .body("""
                        {"login":"%s","senha":"12345678"}""".formatted(login))
            .when().post("/api/auth/login")
            .then().statusCode(200).extract().path("token");
        return given().contentType(ContentType.JSON).auth().oauth2(provisorio)
                .body("""
                        {"senhaAtual":"12345678","senhaNova":"senhaDele12345"}""")
            .when().put("/api/me/senha")
            .then().statusCode(200).extract().path("token");
    }
}

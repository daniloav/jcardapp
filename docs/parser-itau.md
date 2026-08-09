# Calibrar o parser da fatura do Itaú

O parser é a peça mais frágil do app, porque depende do layout de um PDF que o
banco pode mudar sem avisar. Por isso ele foi feito para ser **ajustado sem
recompilar**: as expressões regulares vêm da configuração.

## Como funciona

`ExtratorPdf` usa o PDFBox com `setSortByPosition(true)` — sem isso as colunas
"data · descrição · valor" saem embaralhadas, na ordem interna do arquivo.

`ItauFaturaParser` então percorre o texto de cima para baixo mantendo o
**portador corrente**: a fatura vem seccionada por cartão adicional, e cada bloco
abre com o nome e os 4 últimos dígitos. Toda linha de lançamento depois disso
pertence àquele cartão.

O que não casa nenhuma regra vai para `linhasIgnoradas` em vez de sumir. E como a
conciliação exige que a soma bata com o total impresso, **uma linha perdida vira
fatura `DIVERGENTE`** — nunca um rateio errado em silêncio.

## As quatro regexes

Em `application.properties` (ou como variável de ambiente no `.env`):

| Propriedade | Grupos esperados |
|---|---|
| `jcard.parser.itau.portador` | 1 = nome, 2 = 4 dígitos do cartão |
| `jcard.parser.itau.lancamento` | 1 = data `dd/MM`, 2 = descrição, 3 = valor pt-BR |
| `jcard.parser.itau.total` | 1 = valor total da fatura |
| `jcard.parser.itau.vencimento` | 1 = data `dd/MM/yyyy` |

Valores padrão, escritos para o layout típico do Itaú:

```properties
jcard.parser.itau.portador=^\\s*([A-ZÀ-Ú][A-ZÀ-Ú .'\\-]{2,})\\s*[\\(\\-]?\\s*(?:final|FINAL)\\s*[:\\s]?\\s*(\\d{4})\\s*\\)?\\s*$
jcard.parser.itau.lancamento=^\\s*(\\d{2}/\\d{2})\\s+(.{3,}?)\\s+(-?\\s*[\\d.]{1,12},\\d{2})\\s*$
jcard.parser.itau.total=(?i)total\\s+(?:desta\\s+fatura|da\\s+fatura|a\\s+pagar)\\D{0,20}?(-?[\\d.]{1,12},\\d{2})
jcard.parser.itau.vencimento=(?i)vencimento\\D{0,20}?(\\d{2}/\\d{2}/\\d{4})
```

## O que uma fatura real revelou (07/2026)

Calibrar contra uma fatura de verdade mostrou que o layout é **muito** mais
complexo que o modelo típico. Registro aqui para a próxima sessão não redescobrir.

### 1. Duas colunas, não uma

As transações vêm lado a lado. O `PDFTextStripper`, com ou sem `sortByPosition`,
funde as duas numa linha só — **98% das linhas continham 2 transações**, muitas
vezes de cartões diferentes, o que faria o rateio atribuir a pessoa errada.

Medindo o X de cada data `dd/MM`, as colunas começam em **x≈140** e **x≈360**
(página de 595 pt), com a parcela em x≈220 e x≈440. Extraindo por região com
divisa em **x=350**, saem 579 linhas com uma transação cada.

Cortar em metades (297) ou terços não funciona: trunca as descrições.

### 2. O total aparece 7 vezes

O texto cita "Total desta fatura", "Total da fatura **anterior**", vários
"Total a pagar" de simulações de financiamento e "Valor total financiado".
A regex antiga pegava o **último** e usaria R$ 37.608,36 no lugar de
R$ 34.455,72. Corrigido: âncora estrita em `Total desta fatura`, primeiro
casamento. Coberto por `ItauFaturaParserTest.totalNaoConfundeComOutros`.

### 3. A fatura declara subtotais por cartão

`Lançamentos no cartão (final NNNN)  15.876,08` — um por cartão. **É a melhor
ferramenta de validação que existe**: dá para conferir a leitura contra o número
do próprio banco, cartão a cartão, em vez de só olhar o total.

### 4. Seções de cartão têm abertura e fechamento

`NOME (final NNNN)` abre; `Lançamentos no cartão (final NNNN) VALOR` fecha.
Só conta o que está entre os dois.

**Cuidado**: depois do último fechamento vem "Compras parceladas - próximas
faturas", que **reabre** os mesmos cartões listando parcelas de meses futuros
(R$ 19.752,17 nesta fatura). Regra que funciona: cartão já fechado não reabre —
se reabrir, acabou a parte atual.

### 5. A parcela é coluna própria e opcional

Formato: `DD/MM  DESCRIÇÃO  [PARCELA]  VALOR`, onde PARCELA é `03/10`, `-` ou
**vazio**. E ela pode vir colada na descrição (`ODONTOCO10/12`), porque a coluna
tem largura fixa e trunca.

### 6. Um cartão usava layout diferente

O `final 0020` vinha com cabeçalho próprio (`DATA ESTABELECIMENTO VALOR EM R$`)
e **duas linhas por transação** — estabelecimento numa, `CATEGORIA .CIDADE` na
outra. Precisa de tratamento à parte.

### 7. O problema de verdade é a ASSOCIAÇÃO, não a leitura

Isolado, cada formato de linha é lido corretamente — datas, descrições, parcelas
e valores saem certos. O que não fecha é **de quem é cada lançamento**.

Concatenar as colunas num texto linear perde a geometria. Uma transação que
aparece "depois" do cabeçalho `DANIELA (final 0020)` no meu texto pode estar,
na página impressa, na coluna da direita, sob outro cabeçalho. Foi isso que
inflou o 0020 em R$ 3.827: 146 compras à vista legítimas, mas de outro cartão.

**A correção é um parser posicional**: guardar `(página, coluna, y)` de cada
linha e ligar cada transação ao cabeçalho mais próximo **acima dela, na mesma
coluna e página** — em vez de "o último cabeçalho que apareceu no texto".

Não é ajuste de regex: é trocar a leitura linha-a-linha por leitura por
coordenada. O `PDFTextStripper` já expõe `TextPosition` com x/y por caractere,
então dá para fazer; é trabalho de verdade, não de meia hora.

### 8. Blocos que a fatura tem além dos lançamentos por cartão

Mapeados por posição, existem pelo menos cinco tipos de bloco:

| bloco | onde | observação |
|---|---|---|
| lançamentos por cartão | p2–p8 | tem subtotal declarado |
| **lançamentos internacionais** | p8c1 | cabeçalho `DATA ESTABELECIMENTO US$ R$` — **duas** colunas de valor |
| anuidade, seguro, IOF, estorno | p9 | não entram nos subtotais por cartão |
| encargos e simulações | p10–p11 | juros, CET, "e se você parcelar" |
| compras parceladas futuras | p8c1 em diante | parcelas de meses seguintes |

O `Total desta fatura` (34.455,72) menos a soma dos subtotais por cartão
(34.353,99) dá 101,73 — a diferença são essas taxas do p9.

### 9. O que ainda não fecha

A extração dos lançamentos está **provada correta**: as 260 transações do cartão
0020 têm valores plausíveis, descrições íntegras e nenhum erro de parsing.

O que não fecha é a **delimitação da seção**. Geometricamente o 0020 vai de
`p5c0 y434` até `p8c1 y592`, e tudo nesse intervalo soma R$ 19.575,64 contra os
R$ 15.747,96 declarados. Ou o intervalo engloba lançamentos de outro cartão, ou
o subtotal do banco não cobre tudo que está impresso ali.

Hipóteses já **descartadas** com dados:
- créditos entrando como positivos (só R$ 209 de candidatos);
- duplicação nas bordas das colunas (2 casos, R$ 505);
- parcelas futuras (já excluídas pela regra do "cartão não reabre");
- erro de leitura de valor (os 15 maiores foram conferidos um a um);
- ordem de leitura errada (o fluxo página→coluna foi confirmado por coordenada).

Próximo passo sugerido: dumpar as transações do 0020 agrupadas por página e
coluna e conferir contra a fatura impressa, página a página, para achar onde o
intervalo diverge do que o banco considera "do cartão 0020".

### Estado da calibração

Com extração por coluna + seções delimitadas + parcela opcional:

| cartão | extraído | declarado | |
|---|---|---|---|
| 1064 | 21,86 | 21,86 | ✅ |
| 5037 | 900,00 | 900,00 | ✅ |
| 7266 | 1.808,41 | 1.808,09 | R$ 0,32 |
| 8348 | 15.992,52 | 15.876,08 | R$ 116,44 |
| 0020 | 19.575,64 | 15.747,96 | layout diferente |

**Enquanto não fechar exato, a fatura trava em `DIVERGENTE` e ninguém é cobrado
errado** — o sistema falha do lado seguro, por construção.

> **Vale considerar CSV/OFX.** O PDF é formato de *apresentação*, com pelo menos
> três layouts diferentes na mesma fatura. O Itaú permite baixar a fatura em
> CSV/OFX pelo app, que é formato de *dado* — parsing confiável, sem geometria.
> Para a invariante "as contas sempre batem", é um caminho bem mais sólido.

## Roteiro de calibração

1. **Veja o que o PDFBox realmente extraiu.** É o passo que evita adivinhação:

   ```bash
   cd backend && mvn -q exec:java \
     -Dexec.mainClass=org.apache.pdfbox.tools.PDFBox \
     -Dexec.args="ExtractText -sort fatura.pdf saida.txt"
   ```

   Ou importe a fatura pelo app: mesmo falhando, o texto fica guardado em
   `fatura.texto_extraido` e o botão **Reprocessar** relê dali, sem novo upload.

2. **Compare com o fixture.** `backend/src/test/resources/fatura-itau-exemplo.txt`
   mostra o formato que o parser espera. Ajuste o fixture para o layout real
   (com valores mascarados) e rode:

   ```bash
   cd backend && mvn -B test -Dtest=ItauFaturaParserTest
   ```

   O teste `somaFecha` é o mais importante: garante que a leitura reproduz o total.

3. **Ajuste as regexes** no `.env` de produção e use **Reprocessar** na tela de
   conciliação. Não precisa de deploy nem de reimportar o PDF.

4. **Se o total não aparece no PDF**, informe o valor no campo opcional da tela de
   importação. O app confere contra a soma dos lançamentos do mesmo jeito.

## Classificação dos lançamentos

Feita por palavra-chave na descrição normalizada:

| Contém | Tipo | Reivindicável? |
|---|---|---|
| `PAGAMENTO ... EFETUADO/FATURA`, `PGTO*` | `PAGAMENTO` | não |
| `ESTORNO`, `DEVOLUCAO`, `CANCELAMENTO` | `ESTORNO` | sim |
| `ANUIDADE` | `ANUIDADE` | não |
| `IOF` | `IOF` | não |
| `JUROS`, `MULTA`, `ENCARGO`, `MORA`, `TARIFA`, `SEGURO` | `ENCARGO` | não |
| resto | `COMPRA` | sim |

Créditos entram **negativos** mesmo quando o PDF imprime sem sinal — senão o
total não fecha. O que não é reivindicável fica com o titular na conciliação.

## Normalização e a chave de parcelamento

`TextoFatura.normalizar` tira acento, sobe para caixa alta, remove o sufixo de
parcela e apaga ruído que muda a cada mês (asteriscos, números de autorização com
6+ dígitos). Sem isso, "IFOOD *RESTAURANTE 998877" de julho não casaria com o de
agosto.

A chave é `sha256(descricaoNormalizada | final4 | parcelaTotal)` — **sem o valor**,
de propósito: R$ 100 em 3x sai 33,34 + 33,33 + 33,33, e travar no centavo
quebraria o casamento entre faturas. A proteção contra colisão (duas compras
iguais na mesma loja) é a checagem de valor aproximado no `AtribuicaoService`:
diferença acima de R$ 1,00 ou 5% deixa o lançamento no pool para conferência
humana, em vez de atribuir à pessoa errada.

## Outros emissores

`FaturaParser` é uma interface. Para somar Nubank ou BB, implemente-a, anote com
`@ApplicationScoped` e resolva a injeção por qualificador — o
`FaturaImportService` não muda.

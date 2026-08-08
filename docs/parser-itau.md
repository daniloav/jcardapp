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

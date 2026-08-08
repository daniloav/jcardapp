# JcardApp

Separa a fatura de um cartão de crédito emprestado entre quem realmente usou.

O titular sobe o PDF da fatura; o app quebra em lançamentos e cada pessoa assume
o que reconhece como seu. No fim, todo mundo sabe quanto deve — e as contas
sempre fecham com o total da fatura.

## O problema

Um cartão, várias pessoas usando. Toda virada de fatura vira uma conversa
tentando lembrar quem comprou o quê, e alguém sempre acaba pagando a conta de
outro — ou o titular absorve o que ninguém reclamou.

## Como funciona

1. **Importa.** O admin envia o PDF da fatura do Itaú. O app extrai os
   lançamentos, identifica parcelamentos e a seção de cada cartão adicional.
2. **Avalia.** Todos os utilizadores recebem um e-mail. Cada um abre o app, vê o
   que está **sem dono** e marca o que foi seu.
3. **Concilia.** O que ninguém assumiu fica com o titular. A soma dos acertos
   sempre reproduz o total da fatura.
4. **Quita.** Cada um informa que pagou e o admin confirma o recebimento.

### Duas garantias

**As contas sempre batem.** Se a soma dos lançamentos lidos não fecha com o total
impresso, a fatura trava como `DIVERGENTE` e ninguém é avisado. É deliberado:
ratear em cima de uma leitura errada cobraria valor errado de alguém.

**Parcelamento gruda.** Quem assume a parcela 1/10 recebe as outras nove
automaticamente nas faturas seguintes, sem precisar marcar de novo.

**Conflito não é resolvido por velocidade.** Se duas pessoas marcam a mesma
compra, o lançamento volta para o pool e o admin decide.

**Privacidade.** Cada utilizador vê o pool e as próprias contas — nunca o que
outra pessoa assumiu.

## Rodando localmente

```bash
brew services start postgresql@16
psql -d postgres -c "CREATE ROLE jcard LOGIN PASSWORD 'jcard';"
psql -d postgres -c "CREATE DATABASE jcard OWNER jcard;"

./scripts/gen-jwt-keys.sh
cd backend && mvn quarkus:dev      # http://localhost:8080/q/swagger-ui
```

```bash
cd frontend && npm install && npm start   # http://localhost:4200
```

Primeiro acesso: `admin` / `admin123` (troca de senha obrigatória).

## Testes

```bash
cd backend && mvn -B verify
```

Cobrem as invariantes de conciliação, a herança de parcela, a resolução de
conflito e o parser do Itaú contra um fixture anonimizado.

## Custo

US$ 0. Roda inteiro no Always Free da Oracle Cloud, com o CI/CD dentro das cotas
gratuitas do GitHub. `scripts/verificar-custo-zero.sh` confere se continua assim.

## Stack

Quarkus 3.15 (Java 17) · Angular 17 (PWA) · PostgreSQL 16 · Docker · Caddy + nginx
· 2 VMs Oracle Cloud Ampere A1 (Always Free) · imagens no GHCR.

Documentação em [`docs/`](docs/) e contexto de desenvolvimento em
[`CLAUDE.md`](CLAUDE.md).

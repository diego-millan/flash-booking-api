# Roteiro de apresentação — Flash Booking

> Passo a passo para conduzir a demonstração: linha do tempo, os comandos com as saídas
> reais obtidas contra a pilha do Compose (Postgres + 2 réplicas + load balancer) e, junto de
> cada etapa, a explicação do que ela mostra e por que as decisões foram tomadas.
> Fundamento teórico: [`PLANEJAMENTO.md`](./PLANEJAMENTO.md). Detalhes de código e resumo das
> decisões: [`CODE_REVIEW.md`](./CODE_REVIEW.md). Comandos isolados: [`MANUAL.md`](./MANUAL.md).

---

## 1. Linha do tempo (~13 min)

| # | Etapa | Tempo | Seção |
|---|-------|-------|-------|
| 1 | Contexto e requisitos | 1 min | 3 |
| 2 | Arquitetura | 1 min | 4 |
| 3 | Decisão central: nunca oversell | 1,5 min | 5 |
| 4 | **Demo A** — fluxo feliz + idempotência | 2 min | 6 |
| 5 | **Demo B** — 20 requisições × 5 lugares | 2 min | 7 |
| 6 | **Demo C** — contrato de erros + prova no banco | 2 min | 8 |
| 7 | **Demo D** — expiração automática | 1 min | 9 |
| 8 | Testes, contrato e logs | 2 min | 10–11 |
| 9 | Evoluções e fechamento | 0,5 min | 12 |

A justificativa de cada decisão está escrita **dentro da etapa que a demonstra** — a
conversa técnica acompanha o fluxo do sistema em vez de ficar reservada para um bloco
separado no fim. Se a discussão puxar um assunto antes da hora, o comando da seção
correspondente já está pronto para colar.

---

## 2. Preparação (5 minutos antes)

```bash
./gradlew bootJar
docker compose up --build -d
docker compose ps          # postgres, api-1, api-2 healthy + lb
curl -s localhost:8080/actuator/health     # {"status":"UP"}
./docker/smoke.sh                           # PASS=26 FAIL=0 → tudo pronto
```

Deixe dois terminais abertos: um com `docker compose logs -f lb` (balanceamento) e outro
para os `curl`.

---

## 3. Contexto e requisitos (1 min)

O sistema é uma API de reserva de ingressos em *flash sale*: janelas de venda com pico de
concorrência e capacidade limitada por evento. A exigência que estrutura todo o projeto é
**nunca vender mais ingressos que existem** — e isso vale com N instâncias da API atendendo
ao mesmo tempo, não com uma única cópia isolada.

São cinco endpoints (`POST /events`, `GET /events/:id`, `POST /events/:id/reservations`,
`GET /reservations/:id`, `DELETE /reservations/:id`) e seis requisitos não funcionais. Os
dois primeiros — múltiplas instâncias e a garantia de não oversell — são os que sustentam
toda a discussão técnica, porque é neles que a separação entre "lógica de aplicação" e
"integridade transacional" aparece.

---

## 4. Arquitetura (1 min)

A API é **stateless** com N instâncias atrás de um load balancer (nginx, round-robin), o
**Postgres é a fonte de verdade** e um worker de expiração roda embutido em cada instância.
O ponto de partida da conversa é este: oversell é problema de *integridade transacional* —
qualquer regra de negócio escrita só na aplicação deixa de valer assim que existe mais de
uma cópia rodando, porque duas instâncias não enxergam a memória uma da outra.

Vale também explicar o modelo de leitura: não há cache intermediário, toda leitura vai
direto ao Postgres — e como qualquer réplica atende leitura e escrita pelo mesmo banco, a
consistência é **forte** de qualquer instância, acima do mínimo pedido pelo requisito de
consistência eventual. Cache ou CDN no pico é uma evolução consciente (seção 12), não uma
necessidade escondida.

---

## 5. Decisão central: nunca vender mais do que existe (1,5 min)

Esta é a etapa mais importante da apresentação: mostrar que as alternativas foram
comparadas e descartadas por motivo técnico, não por preferência. A tabela de alternativas,
com o descarte de cada uma, está no `PLANEJAMENTO` §3:

| # | Abordagem | Por que não |
|---|-----------|-------------|
| A | **`UPDATE condicional`** ✔ | — |
| B | `SELECT ... FOR UPDATE` + checagem | lock desde a leitura → contenção no pico |
| C | Checagem só na aplicação + `INSERT` | **falha com concorrência** (oversell garantido) |
| D | Fila/lock distribuído | mais um ponto de falha para o modelo atual |

A opção escolhida e a segunda camada:

```sql
UPDATE events SET reserved = reserved + :q
 WHERE id = :id AND reserved + :q <= capacity;   -- 0 linhas => 409
-- 2a camada: CHECK (reserved <= capacity)
```

O que convém detalhar aqui: o `WHERE` é reavaliado **dentro do lock da linha**. Quando duas
requisições disputam o mesmo evento, uma atualiza e a outra espera; ao conseguir o lock, ela
reavalia a condição contra o valor **já atualizado** e não encaixa mais — daí as 0 linhas e
o `409`. Já a opção B seguraria um lock desde a leitura, e numa janela de venda o pico de
requisições transformaria isso em fila. Mesmo que a aplicação venha a ter um bug, a segunda
camada (`CHECK (reserved <= capacity)`) impede a violação no banco — é o que a Demo C
mostra ao vivo.

Sobre a fronteira de transação: a escrita fica encapsulada em `ReservationWriter`, um bean
com `@Transactional` próprio. Isso não é estética — se o serviço simplesmente chamasse um
método `@Transactional` dele mesmo, a transação não abriria (auto-invocação não passa pelo
proxy do Spring), e um `409` de capacidade no meio do caminho deixaria a reserva sem
devolver a vaga. Com o bean separado, `UPDATE` da capacidade e `INSERT` da reserva
acontecem na mesma transação: ou as duas coisas acontecem, ou nenhuma. `ReservationWriterTest`
cobre exatamente esse caso.

---

## 6. Demo A — fluxo feliz e idempotência (2 min)

```bash
# 1. criar evento
curl -s -X POST localhost:8080/events \
  -H 'Content-Type: application/json' -d '{"name":"Rock Show","capacity":100}'
# 201 {"id":38,...,"reserved":0,"available":100,...}

# 2. reservar (Idempotency-Key é obrigatória)
curl -s -i -X POST localhost:8080/events/38/reservations \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: pedido-42' -d '{"quantity":2}'
# 201 {"id":34,"eventId":38,"quantity":2,"status":"PENDING","expiresAt":"..."}

# 3. repetir o MESMO comando (rede instável / duplo clique do cliente)
# 200 {"id":34,...}   ← mesma reserva, capacity intocada

# 4. conferir
curl -s localhost:8080/events/38
# {"capacity":100,"reserved":2,"available":98}

# 5. cancelar duas vezes
curl -s -X DELETE localhost:8080/reservations/34    # 200 CANCELLED
curl -s -X DELETE localhost:8080/reservations/34    # 200 CANCELLED (idempotente)
curl -s localhost:8080/events/38                    # reserved=0 — devolvido 1× só
```

O que esta etapa demonstra é que a idempotência existe em **duas camadas**. A primeira é o
cliente: a `Idempotency-Key` é obrigatória (`400` se faltar), então um retry nunca passa
despercebido. A segunda é o servidor: a repetição da mesma chave devolve `200` com a
reserva original em vez de criar outra, e o `UNIQUE` no banco é o guarda final — quando dois
requests com a mesma chave chegam ao mesmo tempo, o perdedor sofre rollback (a capacity é
desfeita junto) e re-lê a chave, devolvendo a vencedora. É por isso que o caminho captura
`DataIntegrityViolationException` e não `DuplicateKeyException`: nesse fluxo o Hibernate
traduz o erro para a classe-pai, e capturar a classe errada faria o `catch` simplesmente
nunca disparar.

O cancelamento também é idempotente: o `UPDATE reservations SET status = ... WHERE status
IN ('PENDING','CANCELLED')` é o ponto de serialização, e quando ele afeta **0 linhas** a
capacity não é devolvida. Por isso o `DELETE` duplicado responde `200` de novo, mas o
`reserved` do evento cai para 0 uma única vez — o passo 4 acima mostra justamente isso.

---

## 7. Demo B — 20 requisições × 5 lugares (2 min) ⭐

```bash
# criar evento com 5 lugares
EV=$(curl -s -X POST localhost:8080/events \
  -H 'Content-Type: application/json' -d '{"name":"Flash Sale","capacity":5}' \
  | sed -n 's/.*"id":\([0-9]*\).*/\1/p')

# 20 requisições simultâneas (uma chave por request)
seq 1 20 | xargs -P 20 -I{} curl -s -o /dev/null -w '%{http_code}\n' \
  -X POST localhost:8080/events/$EV/reservations \
  -H 'Content-Type: application/json' -H "Idempotency-Key: flash-{}" -d '{"quantity":1}' \
  | sort | uniq -c
```

Saída real:

```
      5 201
     15 409
```

Estado (API e banco):

```bash
curl -s localhost:8080/events/$EV
# {"capacity":5,"reserved":5,"available":0,...}
```

```
 id | capacity | reserved | available
 39 |        5 |        5 |         0
```

Prova de que o tráfego foi dividido pelas **duas réplicas** (outro terminal,
`docker compose logs -f lb`):

```
"POST /events/39/reservations" 201 via 172.18.0.3:8080   ← cielo-api-1
"POST /events/39/reservations" 409 via 172.18.0.4:8080   ← cielo-api-2
"POST /events/39/reservations" 409 via 172.18.0.3:8080
"POST /events/39/reservations" 201 via 172.18.0.4:8080
```

Este é o momento de conectar o resultado com a decisão da seção 5: saíram exatamente 5
`201`, 15 `409` e `reserved == capacity` — **nunca maior**. E as duas réplicas emitiram
tanto `201` quanto `409`, o que mostra que a escrita está mesmo dividida entre instâncias:
a garantia não está no código de uma delas, está no `WHERE` reavaliado dentro do lock da
linha e na constraint do banco, que as duas instâncias obedecem.

---

## 8. Demo C — contrato de erros + prova no banco (2 min)

### Erros (envelope único)

```bash
curl -s localhost:8080/events/999999
# 404 {"error":{"code":"NOT_FOUND","details":{"eventId":999999}}}

curl -s -X POST localhost:8080/events/38/reservations \
  -H 'Content-Type: application/json' -d '{"quantity":1}'
# 400 {"error":{"code":"VALIDATION_ERROR","details":{"Idempotency-Key":"header is required"}}}

curl -s -X POST localhost:8080/events -H 'Content-Type: application/json' -d '{"name":"x"}'
# 400 VALIDATION_ERROR   ← payload estrito: capacity ausente não vira 0

curl -s -X DELETE localhost:8080/events/38
# 405 METHOD_NOT_ALLOWED
```

Todos os erros usam o mesmo envelope `{"error": {code, message, details}}`. O contrato
prevê 8 códigos em 7 status (`400`, `404`, `405`, `409`, `415`, `422`, `500`) e o sistema
entrega ainda o `IDEMPOTENCY_CONFLICT` como terceiro `409`, cobrindo a regra de idempotência
em outro evento — a tabela completa com cada variação verificada está no
[`MANUAL.md`](./MANUAL.md) §4.

Dois detalhes desta etapa merecem explicação. O primeiro é o `400` de payload ausente: o
Jackson está em modo estrito (`fail-on-missing-creator-properties`), então um `capacity`
faltando não é silenciosamente aceito como `0` — vira erro de validação, que é o comportamento
seguro para uma capacidade de evento. O segundo é o `405`/`404`: rotas e métodos errados
nunca chegam ao tratador genérico, porque existe um tratamento explícito para cada caso e o
`catch-all` de `500` é o **último** a falar (coberto por `ApiExceptionHandlerTest`).

### Prova no banco (a segunda camada)

```bash
docker compose exec postgres psql -U flash -d flash_booking \
  -c "SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint
      WHERE conrelid IN ('events'::regclass,'reservations'::regclass);"
```

```
 events_check        | CHECK (((reserved >= 0) AND (reserved <= capacity)))
 events_capacity_check | CHECK ((capacity > 0))
 uq_reservations_idempotency_key | UNIQUE (idempotency_key)
 reservations_quantity_check | CHECK ((quantity > 0))
```

Tentativa direta de violar a garantia, por fora da aplicação:

```bash
docker compose exec postgres psql -U flash -d flash_booking \
  -c "UPDATE events SET reserved = capacity + 1 WHERE id = 40;"
```

```
ERROR:  new row for relation "events" violates check constraint "events_check"
DETAIL:  Failing row contains (40, Esgotado, 3, 4, ...)
```

As migrations de verdade, executadas pelo Flyway no startup:

```bash
docker compose exec postgres psql -U flash -d flash_booking \
  -c "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;"
```

```
 1 | create events        | t
 2 | add status to events | t
 3 | create reservations  | t
```

Essa prova também motiva a escolha da suíte de testes: o H2 foi removido do
projeto porque ele apenas *simula* essas garantias. Aqui elas existem de verdade no Postgres
e foram testadas inclusive tentando violá-las de propósito — os testes de integração rodam
as migrations com Flyway e validam o schema com `ddl-auto: validate`.

### Contrato interativo — Swagger (30 s, opcional)

```bash
xdg-open http://localhost:8080/swagger-ui/index.html

# o JSON do contrato, direto da aplicação
curl -s localhost:8080/v3/api-docs \
  | python3 -c "import json,sys; s=json.load(sys.stdin); print([f'{m.upper()} {p}' for p,o in s['paths'].items() for m in o])"
```

```
['POST /events', 'POST /events/{eventId}/reservations', 'GET /reservations/{id}', 'DELETE /reservations/{id}', 'GET /events/{id}']
```

O contrato não é um documento escrito à mão: o código gera (springdoc) e
`OpenApiContractIntegrationTest` lê esse JSON e exige que rotas, status (inclusive o `200`
do replay), a header `Idempotency-Key` obrigatória e o schema do envelope baterem com a
implementação. Mudou o endpoint e não a documentação, o teste falha — é a defesa contra
contrato desatualizado.

---

## 9. Demo D — expiração automática (1 min)

O TTL é de 10 minutos — maior que a duração da apresentação. Em vez de esperar, **simule o
tempo passando** direto no banco (não há como a API acelerar o relógio):

```bash
# reserva nova (TTL 10 min) — no terminal acima, anote os ids
docker compose exec postgres psql -U flash -d flash_booking \
  -c "UPDATE reservations SET expires_at = now() - interval '1 minute' WHERE id = 49;"

sleep 7     # a varredura roda a cada 5s — NENHUMA requisição HTTP nesse meio

docker compose exec postgres psql -U flash -d flash_booking \
  -c "SELECT id, status FROM reservations WHERE id = 49;
      SELECT id, reserved, capacity FROM events WHERE id = 46;"
```

```
 id | status           id | reserved | capacity
 49 | EXPIRED          46 |        0 |        4
```

Linha correspondente no log (`docker compose logs api`):

```
INFO reservation expired reservationId=49 eventId=46 quantity=4 source=worker
```

A capacity foi devolvida **sem nenhuma leitura HTTP** — quem agiu foi o worker, que roda em
cada réplica a cada 5 segundos. Como duas instâncias executam a varredura ao mesmo tempo, a
devolução continua única: ambas passam pelo mesmo `UPDATE reservations SET status = 'EXPIRED'
WHERE id = ... AND status = 'PENDING'`, só uma atualiza a linha, e a outra vê 0 linhas e não
toca na capacity. É o mesmo ponto de serialização do cancelamento (seção 6), aplicado à
expiração.

Para fechar a etapa, cancele a reserva que acabou de expirar:

```bash
curl -s -X DELETE localhost:8080/reservations/49
# 409 {"error":{"code":"RESERVATION_EXPIRED","details":{"reservationId":49}}}
```

A resposta `409` é proposital: a capacity já saiu pelo worker, e devolvê-la de novo seria
um *double release* (vender o mesmo lugar duas vezes com a fila de expiração).

---

## 10. Testes e documentação (1 min)

```bash
docker compose up -d postgres
./gradlew test          # 126 testes, 0 falhas (~16 s)
xdg-open build/reports/tests/test/index.html
```

| Tipo | Exemplos | Nº |
|------|----------|----|
| Unitário (Mockito/MockMvc) | `EventServiceTest`, `ReservationWriterTest`, `ReservationControllerTest` | 69 |
| Integração (Postgres real + Flyway) | `*RepositoryTest`, `*ApiIntegrationTest`, `OpenApiContractIntegrationTest`, `RequestLoggingIntegrationTest` | 55 |
| Concorrência (HTTP real, porta aleatória) | `ReservationConcurrencyIntegrationTest` | 2 |

Os 126 testes rodam em cerca de 16 segundos, então dá para executar ao vivo. O ponto que
costuma ser questionado é a escolha do banco: os testes de integração usam o Postgres do
Docker (banco `flash_booking_test`, migrations pelo Flyway) porque uma constraint como
`reserved <= capacity` não significa nada se quem a impõe é um banco de simulação. Dentro
desse grupo estão também o teste do contrato OpenAPI e o teste do log de acesso.

O teste de concorrência merece destaque: ele dispara **20 requisições simultâneas**
(liberadas por um `CountDownLatch`) contra um evento de 5 lugares e exige exatamente 5 ×
`201` e 15 × `409`, com `reserved == capacity` — é a versão automatizada da Demo B.

Para rodar um único teste, o nome completo entre aspas:

```bash
./gradlew test --tests "com.cielo.flashbooking.reservation.ReservationConcurrencyIntegrationTest"
```

---

## 11. Logs (1 min)

```bash
docker compose logs api | grep -E "reservation|api error|request method" | tail -10
```

```
INFO event created eventId=45 capacity=10 name=Log Demo
INFO request method=POST path=/events status=201 durationMs=175
INFO reservation created reservationId=48 eventId=45 quantity=3 expiresAt=2026-09-30T14:23:59Z
WARN api error status=404 code=NOT_FOUND path=/events/999999 details={eventId=999999}
INFO reservation cancelled reservationId=48 eventId=45 quantity=3
INFO reservation expired reservationId=49 eventId=46 quantity=4 source=worker
```

A observabilidade está em três camadas: uma linha de acesso no fim de **cada** requisição
(método, caminho com os IDs, status e duração), os eventos de negócio com os IDs dos objetos
envolvidos — registrados **depois** da transação confirmar, para não logar estado que pode
ser revertido — e os erros com `code`, `path` e `details`, em `WARN` para 4xx e em `ERROR`
com stack trace para 5xx. O que ficou de fora do log de acesso, de propósito: `/actuator/health`
(consultado pelo healthcheck a cada 5 s em cada réplica) e os endpoints de documentação
(`/v3/api-docs`, `/swagger-ui`), que são artefato estático e não tráfego de negócio.

---

## 12. Fechamento — evoluções futuras (30 s)

1. Leitura via **réplica/CDN** no pico (consistência eventual de verdade)
2. **Sharding por evento** quando um único Postgres não bastar
3. **Outbox + Kafka** para confirmação da reserva
4. **Rate limiting / fila de espera** na janela de venda
5. Worker desacoplado da API (hoje roda embutido em cada réplica)

Fecha-se com o registro de que essas são evoluções conscientes: os requisitos atuais são
atendidos com leitura forte e garantia no banco, e cada item da lista acima só entra quando
houver uma medição que justifique o custo. A tabela `CODE_REVIEW` §9 resume as nove decisões
e o que teria acontecido no lugar de cada uma delas caso a escolha tivesse sido a opção
rejeitada.

---

## 13. Plano B — se algo travar na apresentação

| Situação | Reação |
|----------|--------|
| Sem tempo para a demo manual | rode `./docker/smoke.sh` (26 verificações em ~1 s) e mostre o relatório |
| `502` no LB | `docker compose ps` — provavelmente réplica reiniciando; `docker compose logs api --tail 20` |
| Porta 8080 ocupada | `docker compose down` e suba de novo |
| A pilha não subiu o jar | `./gradlew bootJar` e `docker compose up --build -d` |
| Quer repetir a Demo B | crie **outro** evento (as chaves `Idempotency-Key` são únicas no banco; reusar em outro evento dá `409 IDEMPOTENCY_CONFLICT`) |
| Expiração não aconteceu | o TTL é 10 min: use o `UPDATE expires_at` da seção 9 |

Encerre com `docker compose down` (ou `down -v` para zerar os dados).

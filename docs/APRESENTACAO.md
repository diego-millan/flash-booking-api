# Roteiro de apresentação — Flash Booking

> Como conduzir a demonstração e o code review **sem depender de ferramentas auxiliares**:
> comandos prontos para colar e as saídas reais obtidas contra a pilha do Compose
> (Postgres + 2 réplicas + load balancer).
> Fundamento teórico: [`PLANEJAMENTO.md`](./PLANEJAMENTO.md). Detalhes de código:
> [`CODE_REVIEW.md`](./CODE_REVIEW.md). Comandos isolados: [`MANUAL.md`](./MANUAL.md).

---

## 1. Linha do tempo (~12 min)

| # | Bloco | Tempo | Material de apoio |
|---|-------|-------|-------------------|
| 1 | Contexto e requisitos | 1 min | `PLANEJAMENTO` §1 |
| 2 | Arquitetura | 1 min | `PLANEJAMENTO` §2 |
| 3 | Decisão central: nunca oversell | 1,5 min | `PLANEJAMENTO` §3 |
| 4 | **Demo A** — fluxo feliz + idempotência | 2 min | seção 4 |
| 5 | **Demo B** — 20 requisições × 5 lugares | 2 min | seção 5 |
| 6 | **Demo C** — contrato de erros + prova no banco | 2 min | seção 6 |
| 7 | **Demo D** — expiração automática | 1 min | seção 7 |
| 8 | Testes (120) | 1 min | seção 8 |
| 9 | Code review: perguntas prováveis | 3 min | seção 9 |
| 10 | Evoluções e fechamento | 0,5 min | `PLANEJAMENTO` §9 |

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

## 3. Narrativa bloco a bloco

### Bloco 1 — Contexto (1 min)

> "Sistema de reserva de ingressos em *flash sale*: janelas de venda com pico de
> concorrência e capacidade limitada. A exigência que estrutura tudo é
> **nunca vender mais ingressos que existem** — com N instâncias da API rodando ao mesmo
> tempo."

Cinco endpoints, seis requisitos não funcionais (os dois primeiros, múltiplas instâncias e
nunca oversell, são os que sustentam a discussão).

### Bloco 2 — Arquitetura (1 min)

> "API **stateless** com N instâncias atrás de um load balancer, **Postgres como fonte de
> verdade** e um worker de expiração que roda em cada instância. Oversell é problema de
> *integridade transacional*, não de lógica de aplicação — lógica na app falha com N
> instâncias."

### Bloco 3 — Decisão central: nunca oversell (1,5 min)

Apresente a tabela de alternativas e descarte uma a uma:

| # | Abordagem | Por que não |
|---|-----------|-------------|
| A | **`UPDATE condicional`** ✔ | — |
| B | `SELECT ... FOR UPDATE` + checagem | lock desde a leitura → contenção no pico |
| C | Checagem só na aplicação + `INSERT` | **falha com concorrência** (oversell garantido) |
| D | Fila/lock distribuído | mais um ponto de falha para o modelo atual |

A e a segunda camada:

```sql
UPDATE events SET reserved = reserved + :q
 WHERE id = :id AND reserved + :q <= capacity;   -- 0 linhas => 409
-- 2a camada: CHECK (reserved <= capacity)
```

> "O `WHERE` é reavaliado **dentro do lock da linha**: quem espera, reavalia contra o valor
> já atualizado e não encaixa. E mesmo que a aplicação tenha bug, o banco não deixa passar."

---

## 4. Demo A — fluxo feliz e idempotência (2 min)

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

**Fala:** "idempotência em duas camadas: a chave é obrigatória (o cliente nunca perde a
replay sem saber), a repetição devolve `200` com a reserva anterior, e o `UNIQUE` no banco é
o guarda final — no caso de dois requests simultâneos, o perdedor sofre rollback da capacity
e re-lê a chave."

---

## 5. Demo B — 20 requisições × 5 lugares (2 min) ⭐

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

Prova de que passou pelas **duas réplicas** (outro terminal, `docker compose logs -f lb`):

```
"POST /events/39/reservations" 201 via 172.18.0.3:8080   ← cielo-api-1
"POST /events/39/reservations" 409 via 172.18.0.4:8080   ← cielo-api-2
"POST /events/39/reservations" 409 via 172.18.0.3:8080
"POST /events/39/reservations" 201 via 172.18.0.4:8080
```

**Fala:** "exatamente 5 `201`, 15 `409` e `reserved == capacity` — nunca maior. E as duas
réplicas emitiram tanto `201` quanto `409`: a escrita está dividida entre instâncias e a
garantia continua valendo, porque ela vive no banco."

---

## 6. Demo C — contrato de erros + prova no banco (2 min)

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

Os 8 códigos exigidos (400/404/405/409/415/422/500) e as variações estão no
[`MANUAL.md`](./MANUAL.md) §4, todos verificados.

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

E as migrations de verdade:

```bash
docker compose exec postgres psql -U flash -d flash_booking \
  -c "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;"
```

```
 1 | create events        | t
 2 | add status to events | t
 3 | create reservations  | t
```

**Fala:** "o H2 foi removido porque ele *simula* essas garantias. Aqui elas existem de
verdade no Postgres e foram provadas — inclusive tentando violá-las de propósito."

---

## 7. Demo D — expiração automática (1 min)

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

**Fala:** "a capacity foi devolvida **sem nenhuma leitura HTTP** — quem agiu foi o worker.
Como 2 instâncias rodam o worker ao mesmo tempo, a devolução é única porque as duas passam
pelo mesmo `UPDATE ... WHERE status = 'PENDING'`: só uma delas atualiza a linha; a outra vê
0 linhas e não mexe na capacity. O mesmo vale para o `DELETE`."

Depois mostre que um cancelamento de reserva já vencida responde
`409 RESERVATION_EXPIRED` (a capacity já saiu; devolver de novo seria *double release*).

---

## 8. Testes (1 min)

```bash
docker compose up -d postgres
./gradlew test          # 120 testes, 0 falhas (~16 s)
xdg-open build/reports/tests/test/index.html
```

| Tipo | Exemplos | Nº |
|------|----------|----|
| Unitário (Mockito/MockMvc) | `EventServiceTest`, `ReservationWriterTest`, `ReservationControllerTest` | 69 |
| Integração (Postgres real + Flyway) | `*RepositoryTest`, `*ApiIntegrationTest` | 49 |
| Concorrência (HTTP real, porta aleatória) | `ReservationConcurrencyIntegrationTest` | 2 |

Destaque: o teste de concorrência dispara **20 requisições simultâneas** (portão de partida
com `CountDownLatch`) contra 5 lugares e exige exatamente 5 × `201`, 15 × `409`, com
`reserved == capacity`.

Comando para rodar só um teste (nome exato, entre aspas):

```bash
./gradlew test --tests "com.cielo.flashbooking.reservation.ReservationConcurrencyIntegrationTest"
```

---

## 9. Code review — perguntas prováveis e respostas

| Pergunta | Resposta em uma frase | Onde está a prova |
|----------|----------------------|-------------------|
| Por que não `FOR UPDATE`? | lock desde a leitura contenha no pico; o `WHERE` reavaliado já resolve | `PLANEJAMENTO` §3, teste 20×5 |
| E se a app falhar entre o `UPDATE` e o `INSERT`? | `ReservationWriter` é um bean transacional separado — auto-invocação não gera proxy e a transação não abriria | `CODE_REVIEW` §2, `ReservationWriterTest` |
| Duas requisições simultâneas com a mesma chave? | `UNIQUE` → rollback (capacity devolvida) → re-leitura → `200` com a vencedora | `CODE_REVIEW` §3 |
| Por que capturar `DataIntegrityViolationException` e não `DuplicateKeyException`? | nesse caminho o Hibernate traduz para a classe-pai; capturar a errada faria o `catch` nunca disparar | `CODE_REVIEW` §7.3 |
| `capacity` ausente não vira 0? | não: Jackson estrito (`fail-on-missing-creator-properties`) → `400` | Demo C |
| Rota/método errado responde 500? | não: 404/405/415/400 explícitos (o `catch-all` é o último a falar) | Demo C, `ApiExceptionHandlerTest` |
| Cancelar duas vezes devolve vaga em dobro? | não: `UPDATE ... WHERE status IN (...)` é o ponto de serialização; 0 linhas ⇒ não devolve | Demo A, teste "cancelled twice" |
| 2 workers não liberam a mesma vaga? | mesmo `UPDATE ... WHERE status='PENDING'` | Demo D, teste "sweep runs twice" |
| As constraints existem mesmo? | `pg_constraint` mostra `events_check` e o `UNIQUE` | Demo C |
| Testes usam banco de verdade? | Sim — H2 removido, Flyway 3/3 `success=t` | Demo C |
| Por que a API não loga nada útil? | loga: acesso com status+duração, criações com IDs e erros com `code`/`path` (4xx em `WARN`, 5xx em `ERROR`, healthcheck ignorado) | seção 10 |
| E a consistência eventual da leitura? | leitura vai direto ao Postgres em qualquer réplica — é **forte**, acima do mínimo pedido; cache/CDN é evolução | `PROGRESSO` §2, NFR 5 |

Resumo visual (útil para a última tela) — `CODE_REVIEW` §9: as 8 decisões e "se tivesse
feito ao contrário".

---

## 10. Logs (mostrar se perguntarem sobre observabilidade)

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

Regras adotadas: acesso no fim de **cada** requisição (método, caminho com IDs, status,
duração); eventos de negócio com os IDs **depois** da transação confirmar; 4xx em `WARN`
(não `ERROR`, para não mascarar incidentes reais); 5xx em `ERROR` com stack trace;
`/actuator/health` ignorado (o healthcheck consulta a cada 5 s em cada réplica).

---

## 11. Fechamento — evoluções futuras (30 s)

1. Leitura via **réplica/CDN** no pico (consistência eventual de verdade)
2. **Sharding por evento** quando um único Postgres não bastar
3. **Outbox + Kafka** para confirmação da reserva
4. **Rate limiting / fila de espera** na janela de venda
5. Worker desacoplado da API (hoje roda embutido em cada réplica)

---

## 12. Plano B — se algo travar na apresentação

| Situação | Reação |
|----------|--------|
| Sem tempo para a demo manual | rode `./docker/smoke.sh` (26 verificações em ~1 s) e mostre o relatório |
| `502` no LB | `docker compose ps` — provavelmente réplica reiniciando; `docker compose logs api --tail 20` |
| Porta 8080 ocupada | `docker compose down` e suba de novo |
| A pilha não subiu o jar | `./gradlew bootJar` e `docker compose up --build -d` |
| Quer repetir a Demo B | crie **outro** evento (as chaves `Idempotency-Key` são únicas no banco; reusar em outro evento dá `409 IDEMPOTENCY_CONFLICT`) |
| Expiração não aconteceu | o TTL é 10 min: use o `UPDATE expires_at` da seção 7 |

Encerre com `docker compose down` (ou `down -v` para zerar os dados).

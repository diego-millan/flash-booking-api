# Code Review — Flash Booking

> Pontos que merecem atenção durante a apresentação e o code review: **o que** foi feito,
> **por quê**, **que alternativa foi rejeitada** e **como está provado**.
> Complementa o [`PLANEJAMENTO.md`](./PLANEJAMENTO.md) (decisões de projeto) e o
> [`PROGRESSO.md`](./PROGRESSO.md) (status).

---

## 1. Garantia anti-oversell: `UPDATE condicional`

**O que:** a reserva de capacity é feita por uma única instrução no banco:

```sql
UPDATE events SET reserved = reserved + :q
 WHERE id = :id AND reserved + :q <= capacity
-- 0 linhas afetadas => 409 CAPACITY_EXCEEDED
```

Em `EventRepository.addReserved` (JPQL `@Modifying`), chamado por `ReservationWriter`.

**Por quê:** duas transações concorrentes que leem `reserved = 98`, `capacity = 100` e
escrevem `100` e `101` (a clássica *read-modify-write*) só são impedidas pelo banco. O
`WHERE` é reavaliado dentro do lock da linha: quem espera, reavalia contra o valor já
atualizado e não encaixa.

| Alternativa | Por que foi rejeitada |
|---|---|
| `SELECT ... FOR UPDATE` + checagem na app | Lock de linha desde a leitura → contenção máxima no pico do flash sale (é a alternativa B do planejamento) |
| Checagem só na aplicação + `INSERT` | Falha com N instâncias — oversell garantido em algum momento |
| Fila serializante (Redis/lock distribuído) | Mais um ponto de falha e mais complexidade para o modelo atual |

**Segunda camada:** `CHECK (reserved <= capacity)` em `V1__create_events.sql`. A aplicação
pode ter bug; o banco não deixa passar.

**Prova:** `ReservationRepositoryTest` (0 linhas quando estoura a capacidade),
`ReservationApiIntegrationTest.should return 409 CAPACITY_EXCEEDED and never oversell when event sells out`
(capacidade 3, 4ª venda → 409 e `reserved == capacity`, nunca maior).

---

## 2. `ReservationWriter`: a unidade transacional isolada

**O que:** o `UPDATE` de capacity e o `INSERT` da reserva vivem numa classe separada
(`ReservationWriter`) com `@Transactional`; o `ReservationService` orquestra sem transação.

**Por quê — o *self-invocation trap*:** se `create()` e o método transacional estivessem
na mesma classe, `this.metodoTransacional()` chamaria o método direto, **sem passar pelo
proxy do Spring** → a transação nunca abriria → se o `INSERT` falhasse por `UNIQUE`, o
`UPDATE` de capacity **já teria sido persistido** e a vaga sumiria para sempre.

```
ReservationService.create()        ← sem @Transactional, pode tratar erro
   └── ReservationWriter.write()   ← @Transactional: UPDATE + INSERT juntos
         falhou → rollback total (capacity devolvida)
```

| Alternativa | Por que foi rejeitada |
|---|---|
| `@Transactional` no próprio service | Auto-invocação não gera proxy (é preciso outro bean ou `TransactionTemplate`) |
| Insert primeiro, UPDATE depois + compensação | Corrida entre as duas transações; compensação é mais código e menos robusta |
| `TransactionTemplate` inline | Funciona, mas mistura infraestrutura na regra de negócio |

**Prova:** `ReservationWriterTest` — o insert **nunca** é chamado quando o `UPDATE` não
altera nenhuma linha; `ReservationApiIntegrationTest` prova a tradução da violação de
`UNIQUE` dentro dessa transação.

---

## 3. Idempotência em duas camadas + re-leitura após rollback

**O que:**
1. `Idempotency-Key` é **obrigatório** (`400` se ausente);
2. repetição da chave → `200` com a reserva anterior (sem novo `UPDATE` de capacity);
3. `UNIQUE (idempotency_key)` no banco como guarda final;
4. dois requests **simultâneos** com a mesma chave: o perdedor recebe
   `DataIntegrityViolationException` → a transação faz rollback (capacity devolvida) →
   o serviço **re-lê a chave** → `200` com a reserva vencedora.

**Por quê:** a checagem da chave antes de gravar é só um atalho de UX — ela não impede que
dois requests simultâneos passem ao mesmo tempo. A garantia é do banco, e o rollback é o
que impede a capacity de ficar reservada duas vezes.

| Alternativa | Por que foi rejeitada |
|---|---|
| Chave opcional (UUID gerado no servidor) | O client que esquece o header perde a idempotência sem saber |
| Lock/serialização por evento | Seria o `FOR UPDATE` rejeitado na decisão 1 |
| Devolver `409` na corrida | O cliente não sabe se a reserva dele existe; o plano pede `200` com a reserva anterior |

** pegadinha real:** o `catch (DuplicateKeyException)` original **nunca dispararia** — o
Hibernate traduz essa violação como `DataIntegrityViolationException` neste caminho. Por
isso o código captura a classe-pai e **só faz replay se a chave existir**; qualquer outro
erro de integridade é propagado (vira `500`), não mascarado.

**Prova:** `ReservationServiceTest.should return replayed reservation when concurrent request
wins the unique key` e `.should rethrow data integrity error when reservation is not found
after write failure`; `ReservationApiIntegrationTest.should translate unique key violation
when writer writes a repeated idempotency key` (roda contra o constraint real).

---

## 4. Desserialização estrita do payload

**O que:** `spring.jackson.deserialization.fail-on-missing-creator-properties: true`.

**Por quê:** com o default, um campo obrigatório ausente **não gera erro** — o construtor
do data class recebe o valor default do tipo primitivo. Testei antes de ligar a flag:

```
{"name":"Rock Show"}        →  CreateEventRequest(name="Rock Show", capacity=0)
{"quantity":}               →  reserva com quantidade 0
```

Ou seja, `capacity` ausente virava `0` (e passaria reto pelo `@Min(1)` antigo), e
`quantity` ausente viraria `0`. Com a flag, vira `400 VALIDATION_ERROR` — que é o que a
tabela da seção 6 do planejamento chama de "payload inválido".

**Prova:** `EventControllerTest.should return 400 VALIDATION_ERROR when capacity is missing`,
`ReservationControllerTest.should return 400 VALIDATION_ERROR when quantity is missing`.

---

## 5. Tratamento de erros: um *catch-all* que virava 500

**O que:** `ApiExceptionHandler` mapeia `ApiException` (base de todos os erros de domínio)
e mais quatro exceções do Spring MVC que o `@ExceptionHandler(Exception::class)` engolia:

| Exceção | Antes | Depois |
|---|---|---|
| `NoResourceFoundException` (rota inexistente) | `500 INTERNAL_ERROR` | `404 NOT_FOUND` |
| `HttpRequestMethodNotSupportedException` | `500` | `405 METHOD_NOT_ALLOWED` |
| `HttpMediaTypeNotSupportedException` | `500` | `415 UNSUPPORTED_MEDIA_TYPE` |
| `MethodArgumentTypeMismatchException` | `500` | `400 VALIDATION_ERROR` |

**Por quê:** o handler genérico é o *fallback*, não o padrão — ele precisa ser o **último**
a falar. O teste `ApiExceptionHandlerTest` nasceu vermelho exatamente para provar o bug
(`expected:<404> but was:<500>`).

**Estrutura:** `ApiException(status, code, message, details)` → todos os erros de domínio
(`NotFoundException`, `CapacityExceededException`, `InvalidQuantityException`,
`QuantityLimitExceededException`, `IdempotencyConflictException`, `ValidationException`)
mapeiam para HTTP em **um único lugar**; adicionar um código novo não toca no handler.

---

## 6. Testes no PostgreSQL real (H2 removido)

**O que:** os testes de integração usam o banco `flash_booking_test` do Docker Compose,
com Flyway executando as migrations e `ddl-auto: validate`.

**Por quê:** H2 não é PostgreSQL. Rodar `ddl-auto=create-drop` em H2 significava que:

- as migrations (`V1`, `V2`, `V3`) **nunca** tinham sido executadas por ninguém;
- `CHECK (reserved <= capacity)` não existia de fato em lugar nenhum — a garantia central
  do projeto estava fora de qualquer teste.

`@AutoConfigureTestDatabase(replace = NONE)` evita que o `@DataJpaTest` troque por um
banco embutido.

**Prova:** `flyway_schema_history` com 3 linhas `success = t`; constraints consultadas em
`pg_constraint`; testes que violam `CHECK`/`UNIQUE` de propósito e recebem
`DataIntegrityViolationException`.

**Custo aceito:** `./gradlew test` exige `docker compose up -d postgres`. É o trade-off —
preferimos depender do Docker a ter uma garantia não testada.

---

## 7. Pegadinhas encontradas (e documentadas para não repetir)

### 7.1 `Map` é covariante em Kotlin → literais inteiros viram `Long`

```kotlin
assertEquals(mapOf("eventId" to 1L, "quantity" to 5), ex.details)
// falha com prints idênticos: {eventId=1, quantity=5} == {eventId=1, quantity=5}
```

`Map<K, out V>` faz o compilador inferir `V = Long`, então `5` é **boxed como `Long`**,
enquanto o mapa real tem `Integer`. Correção: `mapOf<String, Any>(...)` ou comparar chave
a chave. `1L != 1` em Kotlin, mas os dois imprimem `1`.

### 7.2 Timestamp do PostgreSQL tem precisão de microssegundos

`Instant` do Kotlin tem nanos; o Postgres guarda micros. Igualdade exata falha
(`...37.908351167Z` vs `...37.908351Z`). Correção nos testes:
`Instant.now().truncatedTo(ChronoUnit.MICROS)`.

### 7.3 Violação de `UNIQUE` detectada no commit vira outra exceção

Se o flush acontecer no commit (e não dentro do repositório), o `JpaTransactionManager`
devolve `TransactionSystemException`, não `DataIntegrityViolationException`. Por isso o
`ReservationWriter` usa `saveAndFlush` — o erro nasce **dentro** da transação, com o tipo
esperado.

### 7.4 Restrições de precisão não são validadas pelo Hibernate

`ddl-auto: validate` confere tabelas, colunas e tipos — **não** confere `CHECK`, `UNIQUE`
nem índices. Só a consulta direta em `pg_constraint` (e os testes que violam de propósito)
provam que existem.

---

## 8. Cancelamento: o `UPDATE` de status é o ponto de serialização

**O que:** `DELETE /reservations/:id` cancela e devolve a capacity numa única transação
(`ReservationWriter.cancel`), com duas instruções condicionais:

```sql
UPDATE reservations SET status = 'CANCELLED'
 WHERE id = :id AND status IN ('PENDING','CONFIRMED');   -- 0 linhas => não devolver

UPDATE events SET reserved = reserved - :quantity
 WHERE id = :eventId AND reserved >= :quantity;          -- guarda contra valor negativo
```

**Por quê:** a única coisa que impede **duas** devoluções para a mesma reserva é que
exatamente uma transação consiga atualizar o `status`. Quem perde a corrida (0 linhas)
re-lê a reserva já `CANCELLED` e **não** mexe na capacity — o cancelamento é idempotente
por construção, não por `if` na aplicação.

**Prova:** `should release capacity only once when reservation is cancelled twice` faz
`DELETE` duas vezes e confere que `reserved` volta ao original uma única vez (nunca mais);
`ReservationWriterTest` prova que 0 linhas atualizadas não disparam `releaseReserved`.

**Por que `409` numa reserva expirada:** o worker (ou a coleta *on-demand*) já devolveu a
capacity ao marcar `EXPIRED`. Devolver de novo seria *double release*. Por isso
`ReservationExpiredException` → `409 RESERVATION_EXPIRED`, testado em
`should return 409 RESERVATION_EXPIRED and keep capacity when cancelling past due reservation`.

**A expiração usa exatamente o mesmo primitivo:** `ReservationWriter.expire` é
`UPDATE reservations SET status = 'EXPIRED' WHERE id = :id AND status = 'PENDING'` seguido
de `releaseReserved` **somente se 1 linha foi afetada**. As duas estratégias do planejamento
(worker `@Scheduled` e coleta *on-demand* na leitura) passam por essa mesma transação, então
as 2 réplicas do compose rodando o worker não liberam a mesma vaga duas vezes —
`should release capacity only once when sweep runs twice` roda a varredura duas vezes e
confere que `reserved` não fica negativo.

| Alternativa | Por que foi rejeitada |
|---|---|
| Checar o `status` fora da transação e gravar depois | Dois requests veem `PENDING` → devolvem capacity duas vezes |
| `DELETE` físico da linha | Perde a história da reserva e a guarda contra *double release* |
| `204` sem corpo | O cliente não consegue conferir que a reserva virou `CANCELLED` |

---

## 9. Resumo para a apresentação

| # | Decisão | Se houvesse feito ao contrário |
|---|---------|--------------------------------|
| 1 | `UPDATE condicional` + `CHECK` | Oversell em algum momento sob concorrência |
| 2 | `ReservationWriter` transacional separado | Vaga perdida (capacity reservada sem reserva) |
| 3 | Chave `UNIQUE` + re-leitura pós-rollback | Reserva duplicada ou `500` na corrida |
| 4 | Jackson estrito | Payload incompleto aceito silenciosamente |
| 5 | Handler com fallback por último | Qualquer rota errada respondia `500` |
| 6 | Testes no Postgres real | A garantia central sem nenhuma cobertura |
| 7 | `UPDATE` de `status` como ponto de serialização do cancel | Capacity devolvida duas vezes no `DELETE` simultâneo |

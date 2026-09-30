# Manual de execução — Flash Booking

> Guia para executar o projeto **manualmente, passo a passo**: subir a aplicação inteira com
> Docker, rodar cada teste e chamar cada endpoint com `curl`.
> Complementa o [`README.md`](../README.md) (visão geral) e a
> [`APRESENTACAO.md`](./APRESENTACAO.md) (roteiro da demonstração).

Todos os comandos são executados **dentro da raiz do repositório clonado**.

---

## 1. Pré-requisitos

| Verificação | Comando | Esperado |
|-------------|---------|----------|
| Java | `java -version` | 17 ou superior |
| Docker | `docker version` | cliente + servidor OK |
| Compose | `docker compose version` | v2.x |
| Portas livres | `ss -ltn \| grep -E '8080\|5432'` | nada escutando (ou use portas alternativas) |

> **Permissão do Docker:** se `docker compose ps` retornar `permission denied`, o usuário
> não está no grupo `docker`. Duas saídas:
> `sudo usermod -aG docker $USER` (e faça logout/login), ou executar cada comando isoladamente
> com `sg docker -c "docker compose ..."`.
>
> Neste documento os comandos aparecem sem esse prefixo — acrescente-o se for o seu caso.

---

## 2. Subir a aplicação inteira com Docker

### Passo 1 — gerar o jar

```bash
./gradlew bootJar
ls -lh build/libs/
```

```
-rw-rw-r-- 1 user user 60M set 30 09:58 flash-booking-0.0.1-SNAPSHOT.jar
```

O `build/libs` precisa conter **um único jar**: é o que o `Dockerfile` copia
(`COPY build/libs/*.jar app.jar`). O task `jar` (plain jar) está desabilitado de propósito
no `build.gradle.kts` justamente para o glob não falhar — ver `CHANGELOG.md`.

### Passo 2 — subir a pilha

```bash
docker compose up --build -d
```

Na primeira vez baixa as imagens e builda a da API (1 a 2 minutos). Nas seguintes é rápido.

### Passo 3 — conferir o estado

```bash
docker compose ps
```

| Nome | Papel | Esperado |
|------|-------|----------|
| `cielo-postgres-1` | Postgres 16 | `(healthy)` |
| `cielo-api-1` | 1ª réplica da API | `(healthy)` |
| `cielo-api-2` | 2ª réplica da API | `(healthy)` |
| `cielo-lb-1` | load balancer nginx | `Up` |

> O `lb` só sobe depois das duas réplicas estarem `healthy` (é assim que o `depends_on` está
> configurado). Se alguma API ficar em `Restarting`, veja a seção 6.

### Passo 4 — health check

```bash
curl -s http://localhost:8080/actuator/health
```

```
{"status":"UP"}
```

### Passo 5 — smoke test

```bash
./docker/smoke.sh
```

```
===================================================
PASS=26 FAIL=0
```

Sai com código 0 só se todas as 26 verificações passarem.

### Passo 6 — acompanhar o tráfego

```bash
docker compose logs -f lb
```

Cada linha mostra a requisição e **em qual réplica** ela caiu:

```
172.18.0.1 - [30/Sep/2026:13:01:39 +0000] "POST /events HTTP/1.1" 201 via 172.18.0.3:8080
172.18.0.1 - [30/Sep/2026:13:01:39 +0000] "POST /events/39/reservations HTTP/1.1" 201 via 172.18.0.4:8080
```

`172.18.0.3` = `cielo-api-1`, `172.18.0.4` = `cielo-api-2`.

### Passo 7 — parar

```bash
docker compose down        # para tudo, mantém os dados do Postgres
docker compose down -v     # para e apaga os dados (recomeça do zero)
```

### Resumo dos serviços

| Serviço | Porta | Banco/URL |
|---------|-------|-----------|
| API (atrás do LB) | `http://localhost:8080` | — |
| PostgreSQL | `localhost:5432` | banco `flash_booking`, user/senha `flash` |
| Postgres (testes) | — | banco `flash_booking_test` (criado pelo `docker/postgres/init.sql`) |

---

## 3. Rodar os testes

### Pré-requisito: Postgres no ar

```bash
docker compose up -d postgres
```

Os testes de integração usam o banco `flash_booking_test`, executam as migrations do Flyway
e validam o schema com `ddl-auto: validate` — por isso **não existe H2** e o Docker é
obrigatório.

### Suíte completa

```bash
./gradlew test
```

```
BUILD SUCCESSFUL in 14s
```

A contagem (**126 testes, 0 falhas**) está no relatório HTML:

```bash
xdg-open build/reports/tests/test/index.html
```

A aba de resumo mostra `126 tests, 0 failures`. Para forçar reexecução mesmo com tudo
cacheado: `./gradlew test --rerun-tasks`.

### Uma classe inteira

```bash
./gradlew test --tests "com.cielo.flashbooking.event.EventServiceTest"
```

### Um único teste

O nome precisa ser o **exato**, incluindo os espaços (nomes no padrão
`should <comportamento> quando <condição>`), entre aspas:

```bash
./gradlew test --tests "com.cielo.flashbooking.event.EventServiceTest.should throw invalid quantity when capacity is zero"
```

Executa exatamente 1 teste. Se o nome não bater, o Gradle falha com
`No tests found for given includes` — é assim que você descobre que errou o nome.

Forma curta com curinga (evita digitar o nome inteiro):

```bash
./gradlew test --tests "com.cielo.flashbooking.event.EventServiceTest.should throw invalid quantity*"
```

### Descobrir os nomes

```bash
# todas as classes de teste
find src/test/kotlin -name "*Test*.kt" | sort

# nomes dos testes de uma classe
grep -rn 'fun `should' src/test/kotlin/com/cielo/flashbooking/reservation/ReservationServiceTest.kt | sed 's/.*fun /  /'
```

### Índice das 16 classes (126 testes)

Todos os FQN começam com o pacote da classe indicado na tabela.

| Classe (FQN) | Tipo | Testes |
|--------------|------|--------|
| `com.cielo.flashbooking.event.EventServiceTest` | unitário (Mockito) | 8 |
| `com.cielo.flashbooking.event.EventControllerTest` | unitário (MockMvc) | 10 |
| `com.cielo.flashbooking.error.ApiExceptionHandlerTest` | unitário (MockMvc) | 3 |
| `com.cielo.flashbooking.reservation.ReservationServiceTest` | unitário (Mockito) | 17 |
| `com.cielo.flashbooking.reservation.ReservationWriterTest` | unitário (Mockito) | 11 |
| `com.cielo.flashbooking.reservation.ReservationExpiryServiceTest` | unitário (Mockito) | 5 |
| `com.cielo.flashbooking.reservation.ReservationControllerTest` | unitário (MockMvc) | 15 |
| `com.cielo.flashbooking.event.EventApiIntegrationTest` | integração (Postgres) | 3 |
| `com.cielo.flashbooking.reservation.ReservationApiIntegrationTest` | integração (Postgres) | 13 |
| `com.cielo.flashbooking.reservation.ReservationExpiryApiIntegrationTest` | integração (Postgres) | 5 |
| `com.cielo.flashbooking.reservation.ReservationConcurrencyIntegrationTest` | concorrência (HTTP real) | 2 |
| `com.cielo.flashbooking.event.EventRepositoryTest` | integração (Postgres) | 7 |
| `com.cielo.flashbooking.reservation.ReservationRepositoryTest` | integração (Postgres) | 15 |
| `com.cielo.flashbooking.http.RequestLoggingIntegrationTest` | integração (Postgres) | 6 |
| `com.cielo.flashbooking.http.OpenApiContractIntegrationTest` | integração (Postgres) | 5 |
| `com.cielo.flashbooking.FlashBookingApplicationTests` | integração (Postgres) | 1 |
| **Total** | | **126** |

Comando para cada uma (cole o FQN da tabela):

```bash
./gradlew test --tests "com.cielo.flashbooking.reservation.ReservationConcurrencyIntegrationTest"
```

### Se os testes falharem

| Sintoma | Causa | Correção |
|---------|-------|----------|
| `Connection refused` / falha de conexão | Postgres de teste parado | `docker compose up -d postgres` |
| `No tests found for given includes` | nome do teste errado | confira o nome com o `grep` acima |
| `Permission denied` no Docker | usuário fora do grupo | ver seção 1 |

---

## 4. `curl` para cada endpoint

> Todos contra `http://localhost:8080` — ou seja, **na frente do load balancer**.
> Os ids dos exemplos vêm de uma execução real; substitua pelos seus.

### Passo 0 — criar um evento para usar nos exemplos

```bash
curl -s -X POST http://localhost:8080/events \
  -H 'Content-Type: application/json' \
  -d '{"name":"Rock Show","capacity":100}'
```

```
HTTP/1.1 201 Created
{"id":38,"name":"Rock Show","capacity":100,"reserved":0,"available":100,"status":"ACTIVE","createdAt":"2026-09-30T13:52:48.668065990Z"}
```

Guarde o `id` (aqui: `38`) — ele entra nas URLs abaixo.

---

### 1. `POST /events` — criar evento

```bash
curl -s -i -X POST http://localhost:8080/events \
  -H 'Content-Type: application/json' \
  -d '{"name":"Rock Show","capacity":100}'
```

`201 Created`:

```json
{"id":38,"name":"Rock Show","capacity":100,"reserved":0,"available":100,"status":"ACTIVE","createdAt":"2026-09-30T13:52:48.668065990Z"}
```

| Variação | Comando | Esperado |
|----------|---------|----------|
| `capacity` ausente | `-d '{"name":"x"}'` | `400` `VALIDATION_ERROR` (payload estrito) |
| `capacity` = 0 | `-d '{"name":"x","capacity":0}'` | `422` `INVALID_QUANTITY` |
| `Content-Type` fora de JSON | omita o `-H` (o curl envia `x-www-form-urlencoded`) ou use `-H 'Content-Type: text/plain'` | `415` `UNSUPPORTED_MEDIA_TYPE` |

---

### 2. `GET /events/:id` — consultar disponibilidade

```bash
curl -s http://localhost:8080/events/38
```

`200 OK`:

```json
{"id":38,"name":"Rock Show","capacity":100,"reserved":2,"available":98,"status":"ACTIVE","createdAt":"2026-09-30T13:01:39.263985Z"}
```

| Variação | Comando | Esperado |
|----------|---------|----------|
| inexistente | `curl -s http://localhost:8080/events/999999` | `404` `NOT_FOUND` |
| id não numérico | `curl -s http://localhost:8080/events/abc` | `400` `VALIDATION_ERROR` |
| método errado | `curl -s -X DELETE http://localhost:8080/events/38` | `405` `METHOD_NOT_ALLOWED` |

---

### 3. `POST /events/:id/reservations` — reservar ingressos

`Idempotency-Key` é **obrigatório**.

```bash
curl -s -i -X POST http://localhost:8080/events/38/reservations \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: pedido-42-1' \
  -d '{"quantity":2}'
```

`201 Created`:

```json
{"id":34,"eventId":38,"quantity":2,"status":"PENDING","expiresAt":"2026-09-30T14:02:48.690975858Z","createdAt":"2026-09-30T13:52:48.692956066Z"}
```

Repeta **exatamente o mesmo comando** (mesma chave):

```
HTTP/1.1 200 OK
{"id":34,"eventId":38,"quantity":2,"status":"PENDING",...}
```

Mesmo id, `200` em vez de `201`, e o `reserved` do evento **não muda**:

```bash
curl -s http://localhost:8080/events/38    # reserved=2, available=98
```

| Variação | Comando | Esperado |
|----------|---------|----------|
| sem a header | omita `Idempotency-Key` | `400` `VALIDATION_ERROR` (`details.Idempotency-Key`) |
| `quantity` = 0 | `-d '{"quantity":0}'` + chave nova | `422` `INVALID_QUANTITY` |
| `quantity` = 11 | `-d '{"quantity":11}'` + chave nova | `422` `INVALID_QUANTITY` (`details.limit=10`) |
| chave reusada em **outro** evento | mesma chave, outro `eventId` | `409` `IDEMPOTENCY_CONFLICT` |
| sem disponibilidade | evento esgotado | `409` `CAPACITY_EXCEEDED` |
| `Content-Type: text/plain` | troque o header | `415` |

Exemplo do `409` de esgotamento (evento de capacity 3, quarta venda):

```json
{"error":{"code":"CAPACITY_EXCEEDED","message":"Event has no availability for the requested quantity","details":{"eventId":40,"quantity":1,"available":0}}}
```

---

### 4. `GET /reservations/:id` — consultar reserva

```bash
curl -s http://localhost:8080/reservations/34
```

`200 OK`:

```json
{"id":34,"eventId":38,"quantity":2,"status":"PENDING","expiresAt":"2026-09-30T14:02:48.690975858Z","createdAt":"2026-09-30T13:52:48.692956066Z"}
```

| Variação | Comando | Esperado |
|----------|---------|----------|
| inexistente | `curl -s http://localhost:8080/reservations/999999` | `404` `NOT_FOUND` (`details.reservationId`) |
| id não numérico | `curl -s http://localhost:8080/reservations/abc` | `400` |
| reserva vencida (lida após o TTL) | — | `200` com `status=EXPIRED` e capacity já devolvida |

---

### 5. `DELETE /reservations/:id` — cancelar reserva

```bash
curl -s -i -X DELETE http://localhost:8080/reservations/34
```

`200 OK`:

```json
{"id":34,"eventId":38,"quantity":2,"status":"CANCELLED","expiresAt":"2026-09-30T14:02:48.690975858Z","createdAt":"2026-09-30T13:52:48.692956066Z"}
```

Repita o mesmo comando — cancelamento é **idempotente**:

```
HTTP/1.1 200 OK        (de novo 200, status CANCELLED)
```

A capacity é devolvida **uma única vez**. Confira no evento:

```bash
curl -s http://localhost:8080/events/38    # reserved=0, available=100
```

| Variação | Esperado |
|----------|----------|
| reserva já vencida (`EXPIRED`) | `409` `RESERVATION_EXPIRED` (o worker já devolveu a capacity) |
| inexistente | `404` `NOT_FOUND` |

---

### Tabela resumo — todos os casos

| # | Método + rota | Cenário | Status | `code` |
|---|---------------|---------|--------|--------|
| 1 | `POST /events` | válido | `201` | — |
| 1 | `POST /events` | sem `capacity` | `400` | `VALIDATION_ERROR` |
| 1 | `POST /events` | `capacity` ≤ 0 | `422` | `INVALID_QUANTITY` |
| 2 | `GET /events/:id` | existe | `200` | — |
| 2 | `GET /events/:id` | inexistente | `404` | `NOT_FOUND` |
| 2 | `GET /events/:id` | id não numérico | `400` | `VALIDATION_ERROR` |
| 3 | `POST /events/:id/reservations` | válido | `201` | — |
| 3 | `POST /events/:id/reservations` | replay da chave | `200` | — |
| 3 | `POST /events/:id/reservations` | sem `Idempotency-Key` | `400` | `VALIDATION_ERROR` |
| 3 | `POST /events/:id/reservations` | `quantity` ≤ 0 ou > 10 | `422` | `INVALID_QUANTITY` |
| 3 | `POST /events/:id/reservations` | esgotado | `409` | `CAPACITY_EXCEEDED` |
| 3 | `POST /events/:id/reservations` | chave em outro evento | `409` | `IDEMPOTENCY_CONFLICT` |
| 3 | `POST /events/:id/reservations` | `Content-Type` errado | `415` | `UNSUPPORTED_MEDIA_TYPE` |
| 4 | `GET /reservations/:id` | existe | `200` | — |
| 4 | `GET /reservations/:id` | inexistente | `404` | `NOT_FOUND` |
| 5 | `DELETE /reservations/:id` | cancela | `200` | — |
| 5 | `DELETE /reservations/:id` | cancela de novo | `200` | — |
| 5 | `DELETE /reservations/:id` | reserva expirada | `409` | `RESERVATION_EXPIRED` |
| — | qualquer | método não suportado | `405` | `METHOD_NOT_ALLOWED` |
| — | qualquer | rota inexistente | `404` | `NOT_FOUND` |
| — | qualquer | erro inesperado | `500` | `INTERNAL_ERROR` |

Envelope de erro (sempre o mesmo formato):

```json
{"error":{"code":"CAPACITY_EXCEEDED","message":"...","details":{}}}
```

---

### Documentação interativa — Swagger UI

Os mesmos endpoints e a mesma tabela de erros existem em formato interativo, gerado a partir
do código:

```bash
xdg-open http://localhost:8080/swagger-ui/index.html    # interface no navegador

# ou só a especificação, em JSON
curl -s http://localhost:8080/v3/api-docs | python3 -m json.tool | head -30
```

```bash
# /swagger-ui.html redireciona (302) para a UI
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/swagger-ui.html   # 302
```

O que a UI mostra: as **5 rotas**, a header **`Idempotency-Key` marcada como obrigatória**,
todos os status de cada operação (inclusive o `200` do replay ao lado do `201`) e o schema do
envelope `ErrorResponse` (`code`, `message`, `details`).

O contrato é **testado**, não só documentado — `OpenApiContractIntegrationTest` lê o JSON de
`/v3/api-docs` e exige que rotas, status, header e schemas batam com o código:

```bash
./gradlew test --tests "com.cielo.flashbooking.http.OpenApiContractIntegrationTest"
```

> `/v3/api-docs`, `/swagger-ui/**` e `/actuator` ficam fora do log de acesso (seção 5).

---

## 5. Ver os logs

```bash
docker compose logs -f api
```

Quatro tipos de linha, todas dinâmicas (IDs, status e duração):

```
INFO  event created eventId=45 capacity=10 name=Log Demo
INFO  request method=POST path=/events status=201 durationMs=175
INFO  reservation created reservationId=48 eventId=45 quantity=3 expiresAt=2026-09-30T14:23:59Z
WARN  api error status=404 code=NOT_FOUND path=/events/999999 details={eventId=999999}
INFO  reservation cancelled reservationId=48 eventId=45 quantity=3
INFO  reservation expired reservationId=49 eventId=46 quantity=4 source=worker
```

| Linha | Quando aparece | Nível |
|-------|----------------|-------|
| `request method=... path=... status=... durationMs=...` | fim de **toda** requisição | `INFO` |
| `event created` / `reservation created` / `reservation replayed` / `reservation cancelled` | operação concluída (transação confirmada) | `INFO` |
| `reservation expired ... source=worker\|on-demand` | expiração coletada | `INFO` |
| `api error status=... code=... path=... details=...` | resposta 4xx | `WARN` |
| `api error ... code=INTERNAL_ERROR` | resposta 5xx (com stack trace) | `ERROR` |

Filtros úteis:

```bash
docker compose logs api | grep "reservation created"
docker compose logs api | grep "WARN"
docker compose logs api | grep -cE "path=/actuator|path=/v3|path=/swagger"   # deve ser 0
```

> O healthcheck do Compose consulta `/actuator/health` a cada 5 s em cada réplica e o contrato
> em `/v3/api-docs`/`/swagger-ui` é artefato estático; essas requisições são ignoradas de
> propósito para não poluir o log.

---

## 6. Problemas comuns

| Sintoma | Causa provável | Correção |
|---------|----------------|----------|
| `build/libs/*.jar` não encontrado no build da imagem | `bootJar` não rodou | `./gradlew bootJar` |
| `port is already allocated` na porta 8080 | outra instância do projeto no ar | `docker compose down` |
| `cielo-lb-1` em `Restarting` | réplica ainda subindo | `docker compose ps` e aguarde `healthy` |
| Testes falham com erro de conexão | Postgres de teste parado | `docker compose up -d postgres` |
| `No tests found for given includes` | nome do teste incorreto | confira com o `grep` da seção 3 |
| API responde, mas o LB devolve `502` | container da API reiniciando | `docker compose logs api --tail 50` |

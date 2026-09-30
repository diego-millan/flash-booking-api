# Flash Booking API

![Kotlin](https://img.shields.io/badge/Kotlin-2.1-7F52FF?logo=kotlin&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F?logo=springboot&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-4169E1?logo=postgresql&logoColor=white)
![tests](https://img.shields.io/badge/testes-115-brightgreen)

Núcleo de um sistema de **reserva de ingressos** para eventos com capacidade limitada, em
modelo **flash sale** (janelas de venda com pico de concorrência): criar evento, consultar
disponibilidade, reservar ingressos com idempotência, consultar e cancelar reservas.

A exigência central é **nunca vender mais ingressos que a capacidade** — com N instâncias da
API rodando ao mesmo tempo, sem lock de linha e sem fila.

- **5 endpoints** (seção [Endpoints](#endpoints)) · **115 testes** · **smoke test 26/26**
- API **stateless** com 2 réplicas atrás de um load balancer nginx
- Worker de expiração + coleta *on-demand*, ambos idempotentes

---

## Índice

- [Stack](#stack)
- [Arquitetura](#arquitetura)
- [Pré-requisitos](#pré-requisitos)
- [Como rodar](#como-rodar)
- [Como rodar os testes](#como-rodar-os-testes)
- [Endpoints](#endpoints)
- [Contrato de erros](#contrato-de-erros)
- [Garantia anti-oversell](#garantia-anti-oversell)
- [Decisões arquiteturais e trade-offs](#decisões-arquiteturais-e-trade-offs)
- [Configuração](#configuração)
- [Estrutura do repositório](#estrutura-do-repositório)
- [Evoluções futuras](#evoluções-futuras)
- [Documentação](#documentação)

---

## Stack

| Camada | Escolha | Por quê |
|--------|---------|---------|
| Linguagem | Kotlin 2.1 | tipagem forte, concisidade, interoperabilidade JVM |
| Framework | Spring Boot 3.5 (Web, JPA, Validation, Actuator) | ecossistema maduro, injeção de dependência, tratamento de erros |
| Banco | PostgreSQL 16 | transações, `CHECK`/`UNIQUE` constraints, integridade como garantia |
| Migrações | Flyway | schema versionado (`V1`–`V3`), validado por `ddl-auto: validate` |
| Testes | JUnit 5 + Mockito + Spring Test | unitário, integração em Postgres real e teste de concorrência |
| Infra | Docker Compose | restrição do exercício + multi-instância local |

## Arquitetura

```
┌────────────┐     ┌────────────┐     ┌────────────────────┐
│  API (x N) │────▶│  Postgres  │◀────│ Worker (expiração) │
│  réplicas  │     │  (fonte de │     │  roda em cada réplica│
│ atrás do LB│     │  verdade)  │     └────────────────────┘
└────────────┘     └────────────┘
```

- **API stateless** — N instâncias atrás do load balancer; todo estado vive no Postgres.
  O Compose sobe 2 réplicas (`cielo-api-1`, `cielo-api-2`) e o nginx publica a porta 8080
  com round-robin (`docker compose logs lb` mostra em qual réplica cada requisição caiu).
- **PostgreSQL como fonte de verdade** — oversell é problema de *integridade transacional*;
  o banco resolve com `UPDATE` condicional e constraints, não com lógica aplicacional
  (que falharia com N instâncias).
- **Worker de expiração** — cada réplica roda um `@Scheduled` (5 s) sobre `expires_at`;
  a coleta *on-demand* acontece também na leitura de `GET /reservations/:id` e no
  `DELETE`. As duas estratégias usam o mesmo `UPDATE` condicional, então N workers não
  liberam a mesma vaga duas vezes.

## Pré-requisitos

- **JDK 17+** (`java -version`)
- **Docker** com plugin **Compose v2** (`docker compose version`)
- `./gradlew` é usado direto (o wrapper está no repositório)

## Como rodar

```bash
git clone https://github.com/diego-millan/flash-booking-api.git
cd flash-booking-api

./gradlew bootJar                 # gera build/libs/flash-booking-0.0.1-SNAPSHOT.jar (jar único)
docker compose up --build -d      # postgres + api x2 + lb
```

| Serviço | Endereço |
|---------|----------|
| API (atrás do LB) | http://localhost:8080 |
| PostgreSQL | `localhost:5432` (banco `flash_booking`, user/senha `flash`) |
| Health | http://localhost:8080/actuator/health |

```bash
docker compose ps                 # api-1, api-2 e postgres devem estar (healthy)
./docker/smoke.sh                 # 26 verificações → PASS=26 FAIL=0
docker compose logs -f lb         # acompanha cada requisição e a réplica que atendeu

docker compose down               # para a pilha (mantém o volume do Postgres)
docker compose down -v            # para e apaga os dados
```

> O Compose espera o `bootJar` pronto porque o `Dockerfile` copia o jar de `build/libs`
> (build *multi-stage* foi rejeitado: exigiria baixar Gradle e todas as dependências dentro
> do build da imagem).

## Como rodar os testes

```bash
docker compose up -d postgres     # os testes usam o banco flash_booking_test
./gradlew test                    # 115 testes
```

Relatório: `build/reports/tests/test/index.html`.

| Tipo | Infra | Exemplos |
|------|-------|----------|
| Unitário (Mockito/MockMvc) | sem banco | `EventServiceTest`, `ReservationServiceTest`, `ReservationWriterTest`, `ReservationExpiryServiceTest`, `*ControllerTest` |
| Integração (Postgres real) | `flash_booking_test` + Flyway | `*RepositoryTest`, `*ApiIntegrationTest`, `FlashBookingApplicationTests` |
| Concorrência (HTTP real) | porta aleatória do servidor embutido | `ReservationConcurrencyIntegrationTest` |

O H2 foi removido: `CHECK (reserved <= capacity)` só é confiável se o **próprio PostgreSQL**
o impor. Os testes de integração rodam as migrations de verdade com `ddl-auto: validate`.

O teste de concorrência dispara **20 requisições simultâneas** (liberadas por um
`CountDownLatch`) contra um evento de 5 lugares e exige exatamente 5 × `201` e 15 × `409`,
com `reserved == capacity` — nunca maior.

## Endpoints

| # | Método | Rota | Descrição | Sucesso |
|---|--------|------|-----------|---------|
| 1 | `POST` | `/events` | Criar evento | `201` |
| 2 | `GET` | `/events/:id` | Consultar disponibilidade | `200` |
| 3 | `POST` | `/events/:id/reservations` | Reservar ingressos (**`Idempotency-Key` obrigatório**) | `201` · `200` no replay |
| 4 | `GET` | `/reservations/:id` | Consultar reserva | `200` |
| 5 | `DELETE` | `/reservations/:id` | Cancelar reserva (devolve a capacity) | `200` |

### 1. Criar evento

```bash
curl -s -X POST http://localhost:8080/events \
  -H 'Content-Type: application/json' \
  -d '{"name":"Rock Show","capacity":100}'
```

```json
{"id":1,"name":"Rock Show","capacity":100,"reserved":0,"available":100,"status":"ACTIVE","createdAt":"2026-09-30T13:01:39.263985Z"}
```

### 2. Consultar disponibilidade

```bash
curl -s http://localhost:8080/events/1
```

```json
{"id":1,"name":"Rock Show","capacity":100,"reserved":2,"available":98,"status":"ACTIVE","createdAt":"2026-09-30T13:01:39.263985Z"}
```

### 3. Reservar ingressos

`Idempotency-Key` é **obrigatório** (`400` se ausente). Repetir a mesma chave devolve a
reserva anterior com `200`, **sem** reservar de novo.

```bash
curl -s -i -X POST http://localhost:8080/events/1/reservations \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: pedido-42-1' \
  -d '{"quantity":2}'
```

`201 Created`:

```json
{"id":1,"eventId":1,"quantity":2,"status":"PENDING","expiresAt":"2026-09-30T13:11:39.376051Z","createdAt":"2026-09-30T13:01:39.389112Z"}
```

Repetindo exatamente a mesma chamada:

```
HTTP/1.1 200 OK          ← mesma reserva (id 1), capacity não muda
```

Sem a header:

```json
{"error":{"code":"VALIDATION_ERROR","message":"Required request header is missing","details":{"Idempotency-Key":"header is required"}}}
```

Sem disponibilidade:

```json
{"error":{"code":"CAPACITY_EXCEEDED","message":"Event has no availability for the requested quantity","details":{"eventId":1,"quantity":2,"available":0}}}
```

### 4. Consultar reserva

```bash
curl -s http://localhost:8080/reservations/1
```

```json
{"id":1,"eventId":1,"quantity":2,"status":"PENDING","expiresAt":"2026-09-30T13:11:39.376051Z","createdAt":"2026-09-30T13:01:39.389112Z"}
```

`status` percorre `PENDING → CONFIRMED | CANCELLED | EXPIRED`. Uma reserva vencida lida
pela primeira vez é coletada *on-demand* e aparece como `EXPIRED`, com a capacity já devolvida.

### 5. Cancelar reserva

```bash
curl -s -X DELETE http://localhost:8080/reservations/1
```

```json
{"id":1,"eventId":1,"quantity":2,"status":"CANCELLED","expiresAt":"2026-09-30T13:11:39.376051Z","createdAt":"2026-09-30T13:01:39.389112Z"}
```

A capacity é devolvida **uma única vez**: cancelar de novo (ou dois `DELETE` simultâneos)
responde `200` com a reserva `CANCELLED` e **não** libera vaga em dobro. Cancelar uma
reserva já vencida responde `409 RESERVATION_EXPIRED` (o worker já devolveu a capacity).

## Contrato de erros

Todo erro segue o mesmo envelope:

```json
{
  "error": {
    "code": "CAPACITY_EXCEEDED",
    "message": "Event has no availability for the requested quantity",
    "details": { "eventId": 1, "quantity": 2, "available": 0 }
  }
}
```

| HTTP | `code` | Quando |
|------|--------|--------|
| 400 | `VALIDATION_ERROR` | payload inválido, JSON malformado, campo obrigatório ausente, tipo de parâmetro inválido, header `Idempotency-Key` ausente |
| 404 | `NOT_FOUND` | evento/reserva inexistente ou rota desconhecida |
| 405 | `METHOD_NOT_ALLOWED` | método não suportado pela rota |
| 409 | `CAPACITY_EXCEEDED` | tentativa de oversell |
| 409 | `IDEMPOTENCY_CONFLICT` | mesma `Idempotency-Key` usada em outro evento |
| 409 | `RESERVATION_EXPIRED` | cancelamento de reserva vencida |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | `Content-Type` diferente de `application/json` |
| 422 | `INVALID_QUANTITY` | quantidade ≤ 0 ou acima do limite por reserva (10) |
| 500 | `INTERNAL_ERROR` | erro inesperado (com log no servidor) |

O JSON de entrada é desserializado de forma **estrita** (`fail-on-missing-creator-properties`):
campo obrigatório ausente vira `400 VALIDATION_ERROR` em vez de valor default silencioso
(`capacity` ausente virando `0`).

## Garantia anti-oversell

Duas camadas, ambas no banco:

**1. `UPDATE condicional` atômico** — a reserva de capacity é uma única instrução:

```sql
UPDATE events
   SET reserved = reserved + :quantity
 WHERE id = :eventId
   AND reserved + :quantity <= capacity;
-- 0 linhas afetadas => 409 CAPACITY_EXCEEDED
```

Duas transações concorrentes que leem `reserved = 98` escrevem `100` e `101` num modelo
*read-modify-write* comum; aqui o `WHERE` é reavaliado **dentro do lock da linha**, então a
segunda enxerga `reserved = 100` e não encaixa. Não há `SELECT ... FOR UPDATE` nem lock
desde a leitura (alternativas rejeitadas por contenção no pico do flash sale).

**2. `CHECK (reserved <= capacity)`** — a aplicação pode ter bug; o banco não deixa passar.

A mesma técnica resolve os outros pontos onde capacity é devolvida: `DELETE` e expiração usam
`UPDATE reservations SET status = ... WHERE id = :id AND status IN (...)` — a transação que
**não** atualiza nenhuma linha **não** devolve a capacity. É o que garante liberação única
também com 2 réplicas rodando o worker.

**Provas:** teste de concorrência (20 requisições simultâneas vs. 5 lugares), teste que esgota
um evento de capacity 3 no Postgres e confere `reserved == capacity`, teste de cancelamento
2× e de varredura 2× devolvendo capacity exatamente uma vez — e o smoke test no ambiente real
(`docs/SMOKE_TEST.md`).

## Decisões arquiteturais e trade-offs

ADR completo em [`docs/PROGRESSO.md`](docs/PROGRESSO.md) §6 (19 decisões) e
[`docs/CODE_REVIEW.md`](docs/CODE_REVIEW.md) (com alternativas rejeitadas). Resumo:

| # | Decisão | Alternativa rejeitada | Motivo |
|---|---------|-----------------------|--------|
| 1 | Kotlin + Spring Boot | Node/Express, Python/FastAPI | preferência do time, ecossistema JVM |
| 2 | PostgreSQL | Redis-only | integridade transacional como garantia |
| 3 | `UPDATE condicional` p/ oversell | `FOR UPDATE`, lógica só na aplicação | atomicidade sem contenção; app não garante com N instâncias |
| 4 | Idempotência por header + `UNIQUE` | só app-side | duas camadas: UX (replay `200`) + integridade no banco |
| 5 | `ReservationWriter` como bean transacional | `@Transactional` no próprio service | auto-invocação não passa pelo proxy → a transação não existiria e o rollback de `UNIQUE` deixaria a capacity reservada |
| 6 | Testes em Postgres real | H2 | `CHECK`/`UNIQUE` só existem de verdade no Postgres |
| 7 | Jackson estrito | default silencioso | payload incompleto não pode virar `0` |
| 8 | Expor disponibilidade forte | leitura sempre eventual | RNF aceita eventual; entregar forte está acima do mínimo (cache/CDN é evolução) |

**Trade-offs aceitos:** dependência de Docker para rodar os testes; `CHECK`/`UNIQUE` não são
verificados por `ddl-auto: validate` (só por testes que violam de propósito); worker embutido
na API em vez de processo separado (simpler, e a liberação é idempotente).

## Configuração

| Propriedade | Default | Descrição |
|-------------|---------|-----------|
| `flash-booking.reservation.max-quantity` | `10` | limite de ingressos por reserva (`422`) |
| `flash-booking.reservation.ttl-minutes` | `10` | TTL da reserva (`expires_at`) |
| `flash-booking.reservation.expiry-scan-ms` | `5000` | intervalo da varredura de expiração |
| `flash-booking.reservation.expiry-initial-delay-ms` | `1000` | atraso inicial do worker (1 h no perfil `test`) |
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | `localhost:5432/flash_booking` / `flash` / `flash` | banco |
| `PORT` | `8080` | porta da API |

## Estrutura do repositório

```
├── docker/
│   ├── nginx/default.conf       # load balancer (round-robin + log de upstream)
│   ├── postgres/init.sql        # cria o banco de testes
│   └── smoke.sh                 # smoke test (26 verificações)
├── docs/
│   ├── PLANEJAMENTO.md          # requisitos, arquitetura e decisões originais
│   ├── PROGRESSO.md             # status, matriz de testes, 19 decisões, pendências
│   ├── CODE_REVIEW.md           # pontos que valem code review
│   └── SMOKE_TEST.md            # evidências da execução real
├── src/main/kotlin/com/cielo/flashbooking/
│   ├── event/                   # Event, EventService, EventController, repositório
│   ├── reservation/             # Reservation, ReservationService, ReservationWriter,
│   │                            # ReservationExpiryService (worker), controller
│   ├── error/                   # ApiException + exceções de domínio + handler
│   └── FlashBookingApplication.kt
├── src/main/resources/db/migration/   # Flyway V1–V3
├── docker-compose.yml           # postgres + api x2 + lb
├── Dockerfile
├── build.gradle.kts
└── CHANGELOG.md                 # histórico (Keep a Changelog)
```

## Evoluções futuras

- **Leitura via réplica/CDN** para o pico do flash sale (consistência eventual de verdade)
- **Sharding por evento** — separar a escrita por `event_id` quando um único Postgres não bastar
- **Outbox + mensageria (Kafka)** para notificações de confirmação de reserva
- **Rate limiting / fila de espera** na janela de venda (proteger a API antes do banco)
- **`CONFIRMED` de verdade** — pagamento/confirmação para tirar a reserva do TTL
- **Processo de worker separado** da API (hoje roda embutido em cada réplica)

## Documentação

| Documento | Conteúdo |
|-----------|----------|
| [`docs/PLANEJAMENTO.md`](docs/PLANEJAMENTO.md) | requisitos, arquitetura, modelo de dados e decisões originais |
| [`docs/PROGRESSO.md`](docs/PROGRESSO.md) | status da implementação, testes, 19 decisões e pendências |
| [`docs/CODE_REVIEW.md`](docs/CODE_REVIEW.md) | pontos que valem code review, alternativas rejeitadas e pegadinhas |
| [`docs/SMOKE_TEST.md`](docs/SMOKE_TEST.md) | evidências da pilha real (26/26, réplicas, worker) |
| [`CHANGELOG.md`](CHANGELOG.md) | histórico das mudanças |

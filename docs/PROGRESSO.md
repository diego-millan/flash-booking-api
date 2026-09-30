# Progresso — Flash Booking

> Status da implementação frente ao [`PLANEJAMENTO.md`](./PLANEJAMENTO.md).
> Pontos que valem discussão na apresentação: [`CODE_REVIEW.md`](./CODE_REVIEW.md).
> Roteiro da demonstração: [`APRESENTACAO.md`](./APRESENTACAO.md) · comandos: [`MANUAL.md`](./MANUAL.md).
> Evidência da execução real da pilha: [`SMOKE_TEST.md`](./SMOKE_TEST.md).
> Atualizar este arquivo a cada etapa concluída.

**Última atualização:** 30/09/2026
**Estado:** entrega completa — 5 endpoints + expiração + concorrência + smoke (26/26) + README + logs estruturados · 120 testes
**Repositório:** https://github.com/diego-millan/flash-booking-api (`origin/master`, público)
**Próxima etapa:** nenhuma pendente — entrega completa (5 endpoints, expiração, concorrência,
smoke test 26/26, README e revisão final do histórico)

---

## 1. Endpoints

| # | Método | Rota | Status | Observação |
|---|--------|------|--------|------------|
| 1 | POST | `/events` | ✅ Concluído | Criação com validação, `422 INVALID_QUANTITY` e envelope de erros |
| 2 | GET | `/events/:id` | ✅ Concluído | Disponibilidade (`available = capacity - reserved`), `404 NOT_FOUND`, teste end-to-end |
| 3 | POST | `/events/:id/reservations` | ✅ Concluído | `UPDATE condicional` anti-oversell, `Idempotency-Key` obrigatório, `409 CAPACITY_EXCEEDED` |
| 4 | GET | `/reservations/:id` | ✅ Concluído | Status, quantidade, `expiresAt`, `createdAt`; `404 NOT_FOUND` |
| 5 | DELETE | `/reservations/:id` | ✅ Concluído | Cancela e devolve capacity atomicamente; `409 RESERVATION_EXPIRED`; repetir cancel devolve `200` sem devolver capacity de novo |

---

## 2. Requisitos não funcionais

| # | Requisito | Status | Como está resolvido |
|---|-----------|--------|---------------------|
| 1 | Múltiplas instâncias | ✅ Concluído | `deploy: replicas: 2` atrás do LB nginx (`lb`); log com `$upstream_addr` mostra as 2 réplicas servindo requisições |
| 2 | Nunca oversell | ✅ Concluído | `UPDATE condicional` (`WHERE reserved + qty <= capacity`) + `CHECK (reserved <= capacity)`, ambos provados no Postgres |
| 3 | Expiração automática | ✅ Concluído | `expires_at` + TTL de 10 min; worker `@Scheduled` (5 s, configurável) **e** coleta *on-demand* na leitura de `GET`/`DELETE`, ambos pelo mesmo `UPDATE` condicional → devolve capacity 1× por reserva |
| 4 | Idempotência | ✅ Concluído | `Idempotency-Key` obrigatório → `200` com a reserva anterior; `UNIQUE` no banco + re-leitura após rollback na corrida |
| 5 | Consistência eventual (leitura) | ✅ Superado | Qualquer réplica lê do Postgres → leitura **forte** (acima do mínimo pedido); cache/CDN fica como evolução (seção 9) |
| 6 | Tratamento explícito de erros | ✅ Concluído | Envelope + `ApiException` + `ApiExceptionHandler` |

---

## 3. O que já existe

### Código

```
src/main/kotlin/com/cielo/flashbooking/
├── error/
│   ├── ApiException.kt               # base: status HTTP + code + details
│   ├── ValidationException.kt        # 400 VALIDATION_ERROR de domínio
│   ├── NotFoundException.kt          # 404 NOT_FOUND
│   ├── CapacityExceededException.kt  # 409 CAPACITY_EXCEEDED
│   ├── IdempotencyConflictException.kt# 409 IDEMPOTENCY_CONFLICT
│   ├── ReservationExpiredException.kt# 409 RESERVATION_EXPIRED (cancelamento de reserva vencida)
│   ├── InvalidQuantityException.kt   # 422 INVALID_QUANTITY (≤ 0)
│   ├── QuantityLimitExceededException.kt # 422 INVALID_QUANTITY (acima do limite)
│   ├── ApiExceptionHandler.kt        # @RestControllerAdvice
│   └── ErrorResponse.kt              # envelope {"error":{code,message,details}}
├── event/
│   ├── Event.kt                      # entidade events
│   ├── EventStatus.kt                # ACTIVE | PAUSED
│   ├── EventRepository.kt            # addReserved e releaseReserved (UPDATEs condicionais)
│   ├── EventService.kt               # create, get
│   ├── EventController.kt            # POST /events, GET /events/:id
│   └── dto/                          # CreateEventRequest, EventResponse
├── FlashBookingApplication.kt        # @SpringBootApplication + @EnableScheduling
└── reservation/
    ├── Reservation.kt                # entidade reservations
    ├── ReservationStatus.kt          # PENDING | CONFIRMED | CANCELLED | EXPIRED
    ├── ReservationRepository.kt      # findExpiredIds, markExpired, markCancelled, findByIdempotencyKey
    ├── ReservationWriter.kt          # unidade transacional: write, cancel e expire (todas condicionais)
    ├── ReservationExpiryService.kt   # worker @Scheduled + coleta on-demand na leitura
    ├── ReservationService.kt         # validações, idempotência, replay, consulta e cancelamento
    ├── ReservationController.kt      # POST /events/:eventId/reservations, GET|DELETE /reservations/:id
    └── dto/                          # CreateReservationRequest, ReservationResponse, CreateReservationResult
```

### Migrations (Flyway)

| Arquivo | Conteúdo |
|---------|----------|
| `V1__create_events.sql` | `events(id, name, capacity, reserved, created_at)` + `CHECK (capacity > 0)` + `CHECK (reserved <= capacity)` |
| `V2__add_status_to_events.sql` | `status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE'` + `CHECK (status IN ('ACTIVE','PAUSED'))` |
| `V3__create_reservations.sql` | `reservations(id, event_id, quantity, status, idempotency_key, expires_at, created_at)` + `CHECK (quantity > 0)` + `UNIQUE (idempotency_key)` + `FK → events(id)` + índice em `event_id` + índice parcial em `(expires_at) WHERE status = 'PENDING'` |

### Configuração

| Propriedade | Default | Uso |
|-------------|---------|-----|
| `flash-booking.reservation.max-quantity` | `10` | limite de ingressos por reserva → `422 INVALID_QUANTITY` |
| `flash-booking.reservation.ttl-minutes` | `10` | TTL da reserva (`expires_at`) |
| `flash-booking.reservation.expiry-scan-ms` | `5000` | intervalo da varredura (`@Scheduled`) |
| `flash-booking.reservation.expiry-initial-delay-ms` | `1000` | atraso inicial do worker (3600000 no perfil `test`, para não disparar durante os testes) |
| `DB_URL` / `DB_USER` / `DB_PASSWORD` / `PORT` | `localhost:5432/flash_booking` / `flash` / `flash` / `8080` | infraestrutura |

### Infraestrutura (Docker Compose)

| Serviço | Build/imagem | Porta | Papel |
|---------|--------------|-------|-------|
| `postgres` | `postgres:16-alpine` | `5432` | banco `flash_booking` (+ criação de `flash_booking_test` no `init.sql`) |
| `api` | `Dockerfile` (jar único em `build/libs`) | interna `8080` | **2 réplicas** (`deploy.replicas: 2`), stateless, cada uma roda o worker `@Scheduled` |
| `lb` | `nginx:1.27-alpine` | `8080` | load balancer (round-robin pelo DNS do Docker) e log de `$upstream_addr` |

Arquivos: `Dockerfile`, `docker/nginx/default.conf`, `docker/postgres/init.sql`,
`docker/smoke.sh` (ver [`SMOKE_TEST.md`](./SMOKE_TEST.md)).

### Contrato de erro (seção 6 do planejamento)

| HTTP | `code` | Status |
|------|--------|--------|
| 400 | `VALIDATION_ERROR` | ✅ (bean validation, JSON malformado, campo obrigatório ausente, tipo inválido, header obrigatório ausente) |
| 404 | `NOT_FOUND` | ✅ (rota desconhecida, evento/reserva inexistente) |
| 405 | `METHOD_NOT_ALLOWED` | ✅ |
| 409 | `CAPACITY_EXCEEDED` | ✅ (`POST /reservations` sem disponibilidade) |
| 409 | `IDEMPOTENCY_CONFLICT` | ✅ (chave já usada em outro evento) |
| 409 | `RESERVATION_EXPIRED` | ✅ (`DELETE` de reserva vencida — a capacity já foi devolvida pelo worker) |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | ✅ |
| 422 | `INVALID_QUANTITY` | ✅ (≤ 0 e acima do limite por reserva) |
| 500 | `INTERNAL_ERROR` | ✅ (fallback com log) |

---

## 4. Testes

**120 testes, todos verdes.**

| Classe | Tipo | Nº | Cobre |
|--------|------|----|-------|
| `EventServiceTest` | Unitário (Mockito) | 8 | criação, trim, `capacity <= 0` → 422, timestamp, consulta e 404 |
| `EventControllerTest` | Unitário (MockMvc) | 10 | 200, 201, 400, 404, 405, 415, 422, 500 |
| `ApiExceptionHandlerTest` | Unitário (MockMvc) | 3 | 404, 405, 500 |
| `ReservationServiceTest` | Unitário (Mockito) | 17 | validações, idempotência/replay, conflito de chave, 404, 409, re-leitura após erro de integridade, consulta, cancelamento |
| `ReservationWriterTest` | Unitário (Mockito) | 11 | `UPDATE condicional` → 0 linhas vira `409`, insert só após capacity, cancel e expire devolvem capacity só 1× |
| `ReservationExpiryServiceTest` | Unitário (Mockito) | 5 | varredura varre e ignora lista vazia, coleta *on-demand* e *skip* de futuro/cancelada |
| `ReservationControllerTest` | Unitário (MockMvc) | 15 | 200 (replay, consulta e cancel), 201, 400, 404, 409, 422, header ausente |
| `EventApiIntegrationTest` | Integração (Postgres) | 3 | fluxo completo `POST` → `GET`, 404 e id não numérico |
| `ReservationApiIntegrationTest` | Integração (Postgres) | 13 | reserva reduz disponibilidade, **esgota sem oversell**, replay idempotente, consulta, **cancel devolve capacity (1× e 2×)**, expirada → 409, 404/400/422, tradução de `UNIQUE` |
| `ReservationExpiryApiIntegrationTest` | Integração (Postgres) | 5 | sweep expira e devolve capacity (1× e 2×), futuro fica `PENDING`, coleta *on-demand* na leitura, `DELETE` de vencida → `409` |
| `ReservationConcurrencyIntegrationTest` | Integração (HTTP real, porta aleatória) | 2 | **20 requisições simultâneas** (portão de partida) contra 5 lugares: `reserved` nunca passa de `capacity`, e com `quantity=2` toda requisição que ainda cabe é aceita |
| `EventRepositoryTest` | Integração (Postgres) | 7 | persistência, `status` e **constraints do banco** |
| `ReservationRepositoryTest` | Integração (Postgres) | 15 | `UNIQUE`, `CHECK`, `UPDATE` do cancel e do expire, guarda de `releaseReserved` e `findExpiredIds` |
| `RequestLoggingIntegrationTest` | Integração (Postgres) | 5 | linha de acesso (método/caminho/status/duração), `WARN` com `code`+`path` em erro, `/actuator` não logado e IDs nas criações/cancelamento |
| `FlashBookingApplicationTests` | Integração (Postgres) | 1 | contexto + schema validado |

Como rodar:

```bash
docker compose up -d postgres   # necessário: testes de integração usam Postgres real
./gradlew test
```

Os testes de integração usam o banco `flash_booking_test` (criado por
`docker/postgres/init.sql`), rodam as migrations pelo Flyway e validam o schema com
`ddl-auto: validate`. O H2 foi removido: `reserved <= capacity` só é confiável se o
próprio PostgreSQL a impor.

---

## 5. Histórico de commits

| Commit | Tipo | Descrição |
|--------|------|-----------|
| `1f77c7d` | chore | scaffold Spring Boot + Kotlin |
| `ad30e41` | docs | planejamento inicial |
| `9c95d72` | docs | `AGENTS.md` + `CHANGELOG.md` |
| `43fc753` | feat | `POST /events` com validação e envelope de erros |
| `c067333` | fix | contrato do `POST /events` (status, códigos de erro, payload estrito) |
| `9c3ed9c` | test | testes de integração em PostgreSQL real com Flyway |
| `f4e47ef` | docs | documento de progresso (`docs/PROGRESSO.md`) |
| `213bb6a` | docs | checklist de pendências + notas de retomada de sessão |
| `5523c07` | feat | `GET /events/:id` com `404 NOT_FOUND` e teste end-to-end |
| `4eac27d` | docs | progresso após `GET /events/:id` |
| `36ce0be` | feat | `POST /events/:id/reservations` com `UPDATE condicional` e idempotência |
| `3f79299` | docs | progresso após `POST /events/:id/reservations` |
| `b812e77` | docs | `docs/CODE_REVIEW.md` com os pontos que valem code review |
| `13960a3` | feat | `GET /reservations/:id` |
| `43bf4c2` | docs | progresso após `GET /reservations/:id` |
| `d06677f` | feat | `DELETE /reservations/:id` com devolução atômica de capacity |
| `7c5cc71` | feat | expiração: worker `@Scheduled` + coleta *on-demand* |
| `e894978` | test | teste de concorrência real com 20 requisições simultâneas |
| `b5bc7e9` | fix | 2 réplicas atrás do LB nginx + jar único para o `Dockerfile` |
| `882d698` | test | `docker/smoke.sh` (26 verificações) |
| `27886ee` | docs | evidências do smoke test (`docs/SMOKE_TEST.md`) |
| `fc24bfc` | docs | `README.md` com rotas, garantias e decisões |
| _último_ | docs | revisão final do `CHANGELOG.md` e desta tabela |

> `1f77c7d` e `ad30e41` antecedem a criação do `CHANGELOG.md` (no `9c95d72`) e por isso não
> o alteram; a partir do `9c95d72`, **todos** os commits atualizam o `CHANGELOG.md`
> (convenção do `AGENTS.md`) — verificado com `git show --name-only` em toda a árvore.

---

## 6. Decisões registradas nesta etapa

| # | Decisão | Alternativa rejeitada | Motivo |
|---|---------|-----------------------|--------|
| 6 | `capacity <= 0` → `422 INVALID_QUANTITY` | `400 VALIDATION_ERROR` | A tabela da seção 6 mapeia "quantidade ≤ 0" para 422 |
| 7 | Desserialização estrita (`fail-on-missing-creator-properties`) | default silencioso | `capacity` ausente virava `0` sem avisar |
| 8 | `ApiException` como base dos erros de domínio | `if/else` no handler | `CAPACITY_EXCEEDED` e `RESERVATION_EXPIRED` chegam no próximo endpoint |
| 9 | Testes de integração no Postgres do Compose | H2 / Testcontainers | Constraint só é confiável no banco real; Compose já é restrição do exercício |
| 10 | `status ACTIVE \| PAUSED` com `CHECK` no banco | só enum na aplicação | Invariante aplicada por constraint, coerente com a decisão 3 |
| 11 | `Idempotency-Key` obrigatório (`400` se ausente) | opcional com UUID gerado no servidor | Exige idempotência explícita do client; a doc pedia "duas camadas" |
| 12 | Limite de 10 ingressos por reserva (`flash-booking.reservation.max-quantity`) | sem limite | Cobre o "acima do limite" da tabela 422 e reduz revenda |
| 13 | `UPDATE condicional` como `@Modifying` no `EventRepository` | `SELECT ... FOR UPDATE` na linha do evento | Mesma razão da decisão 3: sem lock de linha no pico |
| 14 | Unidade transacional isolada em `ReservationWriter` | `@Transactional` no próprio service | Auto-invocação não passa pelo proxy → a transação não existiria; assim o rollback de `UNIQUE` desfaz a capacity |
| 15 | Corrida idempotente resolvida por re-leitura após rollback | lock/serialização | `DataIntegrityViolationException` → rollback → a reserva vencedora já está visível → `200` |
| 16 | Ponto de serialização do cancel: `UPDATE status WHERE status IN ('PENDING','CONFIRMED')` na mesma transação da devolução | checar `status` na app e depois gravar | Duas chamadas simultâneas ao `DELETE` veriam `PENDING` e devolveriam capacity duas vezes; com 0 linhas atualizadas o segundo request não devolve nada |
| 17 | Guarda `reserved >= :quantity` em `releaseReserved` | só o `CHECK (reserved >= 0)` | A devolução não pode deixar `reserved` negativo nem quando o estado do banco já divergir; `0 linhas` = nada a fazer |
| 18 | Worker e coleta *on-demand* compartilham o mesmo primitivo atômico (`ReservationWriter.expire`) | lógica de expiração separada em cada caminho | As duas estratégias do planejamento (§5) devolvem capacity pelo mesmo `UPDATE ... WHERE status = 'PENDING'` → N instâncias do worker não liberam em dobro |
| 19 | Expira apenas `PENDING` (não `CONFIRMED`) | expirar tudo que estiver vencido | `CONFIRMED` representa reserva já garantida; só `PENDING` é liberada pelo TTL |
| 20 | Log estruturado em 3 camadas: acesso (método, caminho, status, duração) no fim de toda requisição, eventos de negócio com os IDs **depois** da transação confirmar, e erros com `code`/`path` — 4xx em `WARN`, 5xx em `ERROR`, `/actuator` ignorado | log genérico por request no controller (sem IDs) ou nível `ERROR` para tudo | Uma linha por request não diz *qual* objeto mudou; `ERROR` em 4xx mascararia incidentes reais; e o healthcheck (5 s × 2 réplicas) inundaria o log |

---

## 7. Pendências

Legenda: ⬜ não iniciado · 🟡 em andamento · ✅ concluído

### 7.1 Endpoints (obrigatórios — seção 1 do planejamento)

- [x] ✅ **`POST /events/:id/reservations`** — feito: `UPDATE condicional`
      (`WHERE reserved + qty <= capacity`) no `ReservationWriter`, header `Idempotency-Key`
      obrigatório (`200` no replay), `409 CAPACITY_EXCEEDED`, `409 IDEMPOTENCY_CONFLICT`,
      `422 INVALID_QUANTITY` (≤ 0 e acima de 10), `404 NOT_FOUND`
- [x] ✅ **`GET /events/:id`** — feito: `200` com `capacity`, `reserved`, `available` e
      `status`; `404 NOT_FOUND` com `details.eventId`; id não numérico → `400`; coberto por
      teste end-to-end no Postgres
- [x] ✅ **`GET /reservations/:id`** — feito: `200` com `status`, `quantity`, `expiresAt` e
      `createdAt`; `404 NOT_FOUND` com `details.reservationId`; id não numérico → `400`
- [x] ✅ **`DELETE /reservations/:id`** — feito: `200` com a reserva `CANCELLED`;
      `UPDATE ... WHERE status IN ('PENDING','CONFIRMED')` + devolução de capacity na mesma
      transação (`ReservationWriter.cancel`); cancelar de novo → `200` **sem** devolver capacity
      outra vez; reserva vencida → `409 RESERVATION_EXPIRED`; inexistente → `404 NOT_FOUND`

### 7.2 Requisitos não funcionais

- [x] ✅ **Oversell** — `UPDATE condicional` em `EventRepository.addReserved` + `CHECK`
      no banco; teste de integração esgota um evento de capacidade 3 e prova
      `reserved == capacity` (nunca maior)
- [x] ✅ **Idempotência** — `idempotency_key UNIQUE`, replay `200` com a reserva anterior e
      re-leitura após rollback quando dois requests simultâneos usam a mesma chave
- [x] ✅ **Expiração** — coluna `expires_at` + TTL de 10 min; worker `@Scheduled` varrendo
      `expires_at < now()` a cada 5 s (`ReservationExpiryService.sweep`) **e** coleta
      *on-demand* dentro de `GET /reservations/:id` e `DELETE /reservations/:id`; ambos
      idempotentes pelo mesmo `UPDATE` condicional
- [x] ✅ **Múltiplas instâncias** — `docker compose up --build` sobe `cielo-api-1` e
      `cielo-api-2` atrás do LB nginx; o log `via 172.18.0.3:8080` / `via 172.18.0.4:8080`
      mostra as duas servindo, e uma reserva criada numa réplica é lida na outra
- [x] ✅ **Consistência eventual** — leitura servida direto do Postgres em qualquer réplica
      (consistência forte, acima do mínimo exigido); cache/CDN é evolução futura (seção 9)

### 7.3 Testes (seção 8 do planejamento)

- [x] ✅ **Concorrência** — `ReservationConcurrencyIntegrationTest` dispara **20 requisições
      simultâneas** (todas liberadas pelo mesmo `CountDownLatch`) contra um evento de 5
      lugares, via HTTP real na porta aleatória: exatamente 5 `201`, 15 `409`, e
      `reserved == capacity` (nunca maior); com `quantity=2`, exatamente 2 `201` e
      `available == 1` — toda requisição que ainda cabe é aceita
- [x] ✅ Idempotência: mesma chave → mesma reserva, sem duplicar (feito)
- [x] ✅ Expiração devolve capacity exatamente uma vez (`should release capacity only once
      when sweep runs twice` roda o worker 2× e confere `reserved == 0`, nunca negativo)
- [x] ✅ Cancelamento devolve capacity (`releaseReserved` provado no banco e e2e: cancel 1×
      e cancel 2× devolvem `reserved` ao original, nunca mais que isso)
- [x] ✅ Integração de cada endpoint + envelope de erro (5 de 5 endpoints com teste e2e:
      `POST /events` cria→consulta, `GET /events/:id` (200/404/400), `POST /reservations`
      (201/200 replay/400 header/404/409/422/UNIQUE), `GET /reservations/:id` (200/404) e
      `DELETE /reservations/:id` (200 devolvendo capacity 1× e 2×, 404, 409 expirada))
- [x] ✅ Constraint `CHECK (reserved <= capacity)` provada no Postgres (feito)
- [x] ✅ Endpoint `POST /reservations` esgota sem oversell no Postgres (feito)

### 7.4 `README.md` (obrigatório na entrega) ✅ Concluído

O `README.md` existe e cobre tudo que a restrição 3 do planejamento exige:

- [x] ✅ Título, descrição do sistema e stack (Kotlin, Spring Boot 3.5, PostgreSQL 16, Docker Compose)
- [x] ✅ Pré-requisitos (JDK 17, Docker + Compose)
- [x] ✅ Como rodar: `./gradlew bootJar && docker compose up --build` (API em `:8080` via LB, Postgres em `:5432`)
- [x] ✅ Como rodar os testes: `docker compose up -d postgres && ./gradlew test` (120)
- [x] ✅ Tabela das 5 rotas com exemplos de `curl` (request + response reais do smoke test)
- [x] ✅ Contrato de erros (envelope + tabela de códigos)
- [x] ✅ Decisões arquiteturais e **trade-offs** (tabela resumida + ponteiro para §6 e `CODE_REVIEW.md`)
- [x] ✅ Garantia anti-oversell explicada (`UPDATE condicional` + `CHECK` + liberação única + teste de concorrência)
- [x] ✅ Evoluções futuras (seção 9: réplica/CDN, sharding, outbox + Kafka, rate limiting)
- [x] ✅ Link para `docs/PLANEJAMENTO.md`, `PROGRESSO`, `CODE_REVIEW`, `SMOKE_TEST` e `CHANGELOG`
- [x] ✅ Badges (Kotlin, Spring Boot, PostgreSQL, nº de testes)

### 7.5 Infra e qualidade

- [x] ✅ **Smoke test da API** — `./docker/smoke.sh` bate nos endpoints reais através do LB:
      **26/26 PASS** (health, criação, idempotência, esgotamento, cancelamento e todo o
      contrato de erros). Evidências em [`SMOKE_TEST.md`](./SMOKE_TEST.md), incluindo o worker
      provado direto no banco
- [x] ✅ Healthcheck `/actuator/health` — `docker compose ps` mostra `cielo-api-1` e
      `cielo-api-2` **healthy** (`wget` no actuator); o `lb` só sobe depois das duas saudáveis
- [x] ✅ `Dockerfile` — `tasks.jar { enabled = false }` deixa **um** jar em `build/libs`
      (antes o `COPY *.jar` falhava com 2); o jar é pré-requisito documentado no README
      (multi-stage rejeitado: exigiria baixar Gradle e dependências dentro do build)
- [x] ✅ Revisão final do `CHANGELOG.md` e do histórico antes da entrega — grupos
      `Added`/`Changed`/`Fixed`/`Removed` na ordem exigida, 8 entradas repetitivas de progresso
      consolidadas em uma, contagens de testes conferidas (12 → 24 → 70 → 97 → 113 → 115),
      anotação sobre os 2 commits anteriores ao arquivo, `git log` revisado (25 commits,
      Conventional Commits) e `master` publicado em `github.com/diego-millan/flash-booking-api`
- [x] ✅ Logs estruturados (fora do escopo original, pedido na entrega) — acesso com
      método/caminho/status/duração, eventos de negócio com IDs após o commit, erros com
      `code`/`path` (4xx `WARN`, 5xx `ERROR`), `/actuator` ignorado; decisão 20
- [x] ✅ `docs/MANUAL.md` e `docs/APRESENTACAO.md` — execução manual sem auxílio de
      ferramentas (Docker, teste a teste, `curl` por endpoint) e roteiro da apresentação

---

## 8. Retomada da sessão

Para voltar exatamente de onde paramos:

```bash
cd ~/IdeaProjects/cielo
docker compose up -d postgres    # banco de teste (flash_booking_test) precisa estar no ar
./gradlew test                   # 120 testes — os de integração exigem o Postgres
git status                       # deve estar limpo e sincronizado com origin/master
```

**Pilha real (opcional, para demonstrar):**

```bash
./gradlew bootJar
docker compose up --build -d     # postgres + api x2 + lb em http://localhost:8080
./docker/smoke.sh                # 26 verificações → PASS=26 FAIL=0
docker compose logs -f lb        # cada requisição e a réplica que atendeu
docker compose down               # para tudo (o volume do Postgres permanece)
```

**Estado do repositório:** `master` sincronizado com `origin/master`, árvore limpa,
push automático autenticado (credencial guardada fora do repositório, em `~/.git-credentials`).

**Entrega:** **concluída.** Todos os itens das seções 7.1 a 7.5 estão `[x]` — 5 endpoints,
expiração (worker + coleta *on-demand*), teste de concorrência, smoke test 26/26 com 2 réplicas,
`README.md` (§7.4), revisão final do `CHANGELOG.md`/histórico, logs estruturados e os documentos
de execução/apresentação.

Para a apresentação, o caminho curto é: `docker compose up --build -d` → `./docker/smoke.sh`
→ `docker compose logs -f lb` (mostra as 2 réplicas atendendo) e seguir
[`APRESENTACAO.md`](./APRESENTACAO.md) (roteiro com comandos e saídas reais), com
[`MANUAL.md`](./MANUAL.md) como referência de comandos isolados, [`PLANEJAMENTO.md`](./PLANEJAMENTO.md)
(decisões), [`CODE_REVIEW.md`](./CODE_REVIEW.md) (pontos de discussão) e
[`SMOKE_TEST.md`](./SMOKE_TEST.md) (evidências).

Este documento (`docs/PROGRESSO.md`) é o ponto de partida da próxima sessão — junto com
`docs/PLANEJAMENTO.md` (decisões) e o `CHANGELOG.md` (histórico).

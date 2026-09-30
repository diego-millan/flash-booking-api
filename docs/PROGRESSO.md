# Progresso — Flash Booking

> Status da implementação frente ao [`PLANEJAMENTO.md`](./PLANEJAMENTO.md).
> Pontos que valem discussão na apresentação: [`CODE_REVIEW.md`](./CODE_REVIEW.md).
> Atualizar este arquivo a cada etapa concluída.

**Última atualização:** 30/09/2026
**Estado:** 5 de 5 endpoints completos 🎉 · 97 testes verdes · build OK
**Repositório:** https://github.com/diego-millan/flash-booking-api (`origin/master`, público)
**Próxima etapa:** expiração de reservas (worker + coleta *on-demand*) e teste de concorrência real

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
| 1 | Múltiplas instâncias | ⬜ Pendente | `docker-compose.yml` já sobe 2 réplicas da API; falta validar com teste |
| 2 | Nunca oversell | ✅ Concluído | `UPDATE condicional` (`WHERE reserved + qty <= capacity`) + `CHECK (reserved <= capacity)`, ambos provados no Postgres |
| 3 | Expiração automática | 🟡 Parcial | `expires_at` + TTL de 10 min gravados na reserva; falta o worker e a coleta *on-demand* |
| 4 | Idempotência | ✅ Concluído | `Idempotency-Key` obrigatório → `200` com a reserva anterior; `UNIQUE` no banco + re-leitura após rollback na corrida |
| 5 | Consistência eventual (leitura) | ⬜ Não iniciado | `GET /events/:id` poderá servir de cache/réplica |
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
└── reservation/
    ├── Reservation.kt                # entidade reservations
    ├── ReservationStatus.kt          # PENDING | CONFIRMED | CANCELLED | EXPIRED
    ├── ReservationRepository.kt      # findByIdempotencyKey + markCancelled (UPDATE condicional do cancel)
    ├── ReservationWriter.kt          # unidade transacional: write (capacity + insert) e cancel (status + devolução)
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

**97 testes, todos verdes.**

| Classe | Tipo | Nº | Cobre |
|--------|------|----|-------|
| `EventServiceTest` | Unitário (Mockito) | 8 | criação, trim, `capacity <= 0` → 422, timestamp, consulta e 404 |
| `EventControllerTest` | Unitário (MockMvc) | 10 | 200, 201, 400, 404, 405, 415, 422, 500 |
| `ApiExceptionHandlerTest` | Unitário (MockMvc) | 3 | 404, 405, 500 |
| `ReservationServiceTest` | Unitário (Mockito) | 17 | validações, idempotência/replay, conflito de chave, 404, 409, re-leitura após erro de integridade, consulta, cancelamento |
| `ReservationWriterTest` | Unitário (Mockito) | 8 | `UPDATE condicional` → 0 linhas vira `409`, insert só após capacity, cancel devolve capacity só uma vez |
| `ReservationControllerTest` | Unitário (MockMvc) | 15 | 200 (replay, consulta e cancel), 201, 400, 404, 409, 422, header ausente |
| `EventApiIntegrationTest` | Integração (Postgres) | 3 | fluxo completo `POST` → `GET`, 404 e id não numérico |
| `ReservationApiIntegrationTest` | Integração (Postgres) | 13 | reserva reduz disponibilidade, **esgota sem oversell**, replay idempotente, consulta, **cancel devolve capacity (1× e 2×)**, expirada → 409, 404/400/422, tradução de `UNIQUE` |
| `EventRepositoryTest` | Integração (Postgres) | 7 | persistência, `status` e **constraints do banco** |
| `ReservationRepositoryTest` | Integração (Postgres) | 12 | persistência, `UNIQUE` da chave, `CHECK (quantity > 0)`, `UPDATE condicional` do cancel e guarda de `releaseReserved` no banco |
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
- [ ] 🟡 **Expiração** — coluna `expires_at` + TTL de 10 min já gravados; falta o worker de
      varredura (`expires_at < now()`) **e** a coleta *on-demand* na leitura, ambos idempotentes
- [ ] ⬜ **Múltiplas instâncias** — validar com as 2 réplicas já configuradas no compose
- [ ] ⬜ **Consistência eventual** — leitura de disponibilidade servida de cache/réplica

### 7.3 Testes (seção 8 do planejamento)

- [ ] ⬜ **Concorrência** — N requisições **simultâneas** para capacidade < N →
      `reserved <= capacity` **sempre** (hoje a prova é sequencial)
- [x] ✅ Idempotência: mesma chave → mesma reserva, sem duplicar (feito)
- [ ] 🟡 Expiração devolve capacity exatamente uma vez — depende do worker (item "Expiração"
      acima); o cancelamento já exercita a devolução única no mesmo caminho de código
- [x] ✅ Cancelamento devolve capacity (`releaseReserved` provado no banco e e2e: cancel 1×
      e cancel 2× devolvem `reserved` ao original, nunca mais que isso)
- [ ] 🟡 Integração de cada endpoint + envelope de erro (5 de 5 endpoints com teste e2e)
- [x] ✅ Constraint `CHECK (reserved <= capacity)` provada no Postgres (feito)
- [x] ✅ Endpoint `POST /reservations` esgota sem oversell no Postgres (feito)

### 7.4 `README.md` (obrigatório na entrega — ainda não existe)

O repositório não tem README. Conteúdo mínimo exigido pela restrição 3 do planejamento:

- [ ] Título, descrição do sistema e stack (Kotlin, Spring Boot 3.5, PostgreSQL 16, Docker Compose)
- [ ] Pré-requisitos (JDK 17, Docker + Compose)
- [ ] Como rodar: `docker compose up --build` (API em `:8080`, Postgres em `:5432`)
- [ ] Como rodar os testes: `docker compose up -d postgres && ./gradlew test`
- [ ] Tabela das 5 rotas com exemplos de `curl` (request + response)
- [ ] Contrato de erros (envelope + tabela de códigos)
- [ ] Decisões arquiteturais e **trade-offs** (seções 3, 6 e 10 do planejamento;
      ADR resumido também em `docs/PROGRESSO.md` §6)
- [ ] Garantia anti-oversell explicada (`UPDATE condicional` + `CHECK` + teste de concorrência)
- [ ] Evoluções futuras (seção 9: réplica/CDN, sharding, outbox + Kafka, rate limiting)
- [ ] Link para `docs/PLANEJAMENTO.md` e `docs/PROGRESSO.md`
- [ ] Badges (build, licença) — opcional

### 7.5 Infra e qualidade

- [ ] ⬜ **Smoke test da API** — a aplicação ainda não foi iniciada contra o Postgres
      (só os testes); rodar `docker compose up --build` e bater nos endpoints com `curl`
- [ ] ⬜ Healthcheck `/actuator/health` já exposto — confirmar que o compose sobe as 2 réplicas
- [ ] ⬜ `Dockerfile` — o build copia `build/libs/*.jar`; garantir que o compose builda o jar
      antes (hoje depende de `./gradlew build` na mão) — avaliar multi-stage build
- [ ] ⬜ Publicar o repositório com histórico limpo e revisar `CHANGELOG.md` antes da entrega

---

## 8. Retomada da sessão

Para voltar exatamente de onde paramos:

```bash
cd ~/IdeaProjects/cielo
docker compose up -d postgres    # banco de teste (flash_booking_test) precisa estar no ar
./gradlew test                   # 97 testes — os de integração exigem o Postgres
git status                       # deve estar limpo e sincronizado com origin/master
```

**Estado do repositório:** `master` sincronizado com `origin/master`, árvore limpa,
push automático autenticado (credencial guardada fora do repositório, em `~/.git-credentials`).

**Continuar por:** os 5 endpoints da §7.1 estão **todos concluídos**. Próximos passos, em
ordem: (1) expiração de reservas — worker de varredura + coleta *on-demand* na leitura
(§7.2), (2) teste de concorrência real com N requisições simultâneas (§7.3), (3) smoke test
com `docker compose up --build` + `curl` (§7.5) e (4) `README.md` (§7.4).

Este documento (`docs/PROGRESSO.md`) é o ponto de partida da próxima sessão — junto com
`docs/PLANEJAMENTO.md` (decisões) e o `CHANGELOG.md` (histórico).

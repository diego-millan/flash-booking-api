# Progresso — Flash Booking

> Status da implementação frente ao [`PLANEJAMENTO.md`](./PLANEJAMENTO.md).
> Atualizar este arquivo a cada etapa concluída.

**Última atualização:** 30/09/2026
**Estado:** `POST /events` completo · 24 testes verdes · build OK
**Repositório:** https://github.com/diego-millan/flash-booking-api (`origin/master`, público)
**Próxima etapa:** `GET /events/:id`

---

## 1. Endpoints

| # | Método | Rota | Status | Observação |
|---|--------|------|--------|------------|
| 1 | POST | `/events` | ✅ Concluído | Criação com validação, `422 INVALID_QUANTITY` e envelope de erros |
| 2 | GET | `/events/:id` | ⬜ Não iniciado | Consulta de disponibilidade (consistência eventual) |
| 3 | POST | `/events/:id/reservations` | ⬜ Não iniciado | `UPDATE condicional` anti-oversell + `Idempotency-Key` |
| 4 | GET | `/reservations/:id` | ⬜ Não iniciado | — |
| 5 | DELETE | `/reservations/:id` | ⬜ Não iniciado | Devolve capacity de forma atômica |

---

## 2. Requisitos não funcionais

| # | Requisito | Status | Como está resolvido |
|---|-----------|--------|---------------------|
| 1 | Múltiplas instâncias | ⬜ Pendente | `docker-compose.yml` já sobe 2 réplicas da API; falta validar com teste |
| 2 | Nunca oversell | 🟡 Parcial | `CHECK (reserved <= capacity)` provado no Postgres; falta o `UPDATE condicional` |
| 3 | Expiração automática | ⬜ Não iniciado | Worker cron + coleta *on-demand* na leitura |
| 4 | Idempotência | ⬜ Não iniciado | Header `Idempotency-Key` + `UNIQUE` no banco |
| 5 | Consistência eventual (leitura) | ⬜ Não iniciado | `GET /events/:id` poderá servir de cache/réplica |
| 6 | Tratamento explícito de erros | ✅ Concluído | Envelope + `ApiException` + `ApiExceptionHandler` |

---

## 3. O que já existe

### Código

```
src/main/kotlin/com/cielo/flashbooking/
├── error/
│   ├── ApiException.kt            # base: status HTTP + code + details
│   ├── InvalidQuantityException.kt# 422 INVALID_QUANTITY
│   ├── ApiExceptionHandler.kt     # @RestControllerAdvice
│   └── ErrorResponse.kt           # envelope {"error":{code,message,details}}
└── event/
    ├── Event.kt                   # entidade events
    ├── EventStatus.kt             # ACTIVE | PAUSED
    ├── EventRepository.kt
    ├── EventService.kt            # regra de negócio (create)
    ├── EventController.kt         # POST /events
    └── dto/                       # CreateEventRequest, EventResponse
```

### Migrations (Flyway)

| Arquivo | Conteúdo |
|---------|----------|
| `V1__create_events.sql` | `events(id, name, capacity, reserved, created_at)` + `CHECK (capacity > 0)` + `CHECK (reserved <= capacity)` |
| `V2__add_status_to_events.sql` | `status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE'` + `CHECK (status IN ('ACTIVE','PAUSED'))` |

### Contrato de erro (seção 6 do planejamento)

| HTTP | `code` | Status |
|------|--------|--------|
| 400 | `VALIDATION_ERROR` | ✅ (bean validation, JSON malformado, campo obrigatório ausente, tipo inválido) |
| 404 | `NOT_FOUND` | ✅ (rota desconhecida; evento/reserva inexistente vem no próximo endpoint) |
| 405 | `METHOD_NOT_ALLOWED` | ✅ |
| 409 | `CAPACITY_EXCEEDED` | ⬜ no `POST /reservations` |
| 409 | `RESERVATION_EXPIRED` | ⬜ no cancelamento |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | ✅ |
| 422 | `INVALID_QUANTITY` | ✅ |
| 500 | `INTERNAL_ERROR` | ✅ (fallback com log) |

---

## 4. Testes

**24 testes, todos verdes.**

| Classe | Tipo | Nº | Cobre |
|--------|------|----|-------|
| `EventServiceTest` | Unitário (Mockito) | 6 | criação, trim, `capacity <= 0` → 422, timestamp |
| `EventControllerTest` | Unitário (MockMvc) | 7 | 201, 400, 422, 415 |
| `ApiExceptionHandlerTest` | Unitário (MockMvc) | 3 | 404, 405, 500 |
| `EventRepositoryTest` | Integração (Postgres) | 7 | persistência, `status` e **constraints do banco** |
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

---

## 6. Decisões registradas nesta etapa

| # | Decisão | Alternativa rejeitada | Motivo |
|---|---------|-----------------------|--------|
| 6 | `capacity <= 0` → `422 INVALID_QUANTITY` | `400 VALIDATION_ERROR` | A tabela da seção 6 mapeia "quantidade ≤ 0" para 422 |
| 7 | Desserialização estrita (`fail-on-missing-creator-properties`) | default silencioso | `capacity` ausente virava `0` sem avisar |
| 8 | `ApiException` como base dos erros de domínio | `if/else` no handler | `CAPACITY_EXCEEDED` e `RESERVATION_EXPIRED` chegam no próximo endpoint |
| 9 | Testes de integração no Postgres do Compose | H2 / Testcontainers | Constraint só é confiável no banco real; Compose já é restrição do exercício |
| 10 | `status ACTIVE \| PAUSED` com `CHECK` no banco | só enum na aplicação | Invariante aplicada por constraint, coerente com a decisão 3 |

---

## 7. Pendências

Legenda: ⬜ não iniciado · 🟡 em andamento · ✅ concluído

### 7.1 Endpoints (obrigatórios — seção 1 do planejamento)

- [ ] ⬜ **`GET /events/:id`** — retornar `capacity`, `reserved`, `available`, `status`;
      `404 NOT_FOUND` para evento inexistente; leitura pode ser eventual (RNF 5)
- [ ] ⬜ **`POST /events/:id/reservations`** — o coração do exercício:
      `UPDATE condicional` (`WHERE reserved + qty <= capacity`), header `Idempotency-Key`,
      `409 CAPACITY_EXCEEDED`, `422 INVALID_QUANTITY`, `404 NOT_FOUND`
- [ ] ⬜ **`GET /reservations/:id`** — status, quantidade, `expires_at`
- [ ] ⬜ **`DELETE /reservations/:id`** — cancelar devolvendo capacity de forma atômica;
      `409 RESERVATION_EXPIRED` para reserva já expirada

### 7.2 Requisitos não funcionais

- [ ] ⬜ **Oversell** — `UPDATE condicional` no `ReservationService` (a constraint do banco
      já existe e está provada; falta a camada atômica da aplicação)
- [ ] ⬜ **Idempotência** — coluna `idempotency_key UNIQUE` em `reservations` + retorno
      `200` com a reserva anterior em caso de repetição
- [ ] ⬜ **Expiração** — worker de varredura (`expires_at < now()`) **e** coleta
      *on-demand* na leitura, ambos idempotentes (sem devolver capacity duas vezes)
- [ ] ⬜ **Múltiplas instâncias** — validar com as 2 réplicas já configuradas no compose
- [ ] ⬜ **Consistência eventual** — leitura de disponibilidade servida de cache/réplica

### 7.3 Testes (seção 8 do planejamento)

- [ ] ⬜ **Concorrência** — N requisições simultâneas para capacidade < N →
      `reserved <= capacity` **sempre**
- [ ] ⬜ Idempotência: mesma chave → mesma reserva, sem duplicar
- [ ] ⬜ Expiração devolve capacity exatamente uma vez
- [ ] ⬜ Cancelamento devolve capacity
- [ ] ⬜ Integração de cada endpoint + envelope de erro para todos os casos
- [ ] ✅ Constraint `CHECK (reserved <= capacity)` provada no Postgres (feito)

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
./gradlew test                   # 24 testes — os de integração exigem o Postgres
git status                       # deve estar limpo e sincronizado com origin/master
```

**Estado do repositório:** `master` sincronizado com `origin/master`, árvore limpa,
push automático autenticado (credencial guardada fora do repositório, em `~/.git-credentials`).

**Continuar por:** §7.1 → `GET /events/:id` (mais simples, depende só do `EventRepository`),
depois `POST /events/:id/reservations`, que desbloqueia §7.2 e §7.3.

Este documento (`docs/PROGRESSO.md`) é o ponto de partida da próxima sessão — junto com
`docs/PLANEJAMENTO.md` (decisões) e o `CHANGELOG.md` (histórico).

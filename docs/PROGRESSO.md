# Progresso — Flash Booking

> Status da implementação frente ao [`PLANEJAMENTO.md`](./PLANEJAMENTO.md).
> Atualizar este arquivo a cada etapa concluída.

**Última atualização:** 29/09/2026
**Estado:** `POST /events` completo · 24 testes verdes · build OK

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

## 7. Próximos passos

1. **`GET /events/:id`** — consulta de disponibilidade + `404 NOT_FOUND`
2. **`POST /events/:id/reservations`** — `UPDATE condicional`, `Idempotency-Key`,
   `409 CAPACITY_EXCEEDED` (o coração do exercício)
3. **`GET /reservations/:id`** e **`DELETE /reservations/:id`**
4. Expiração de reservas (worker + *on-demand*)
5. Teste de concorrência (seção 8 do planejamento)
6. `README.md` com instruções, decisões e trade-offs

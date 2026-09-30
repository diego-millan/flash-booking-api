# Flash Booking — Planejamento Inicial

> Documento de referência para o time: como o projeto foi concebido, quais decisões
> arquiteturais foram tomadas e por quê. Serve de base para a apresentação e o code review.

**Status:** em desenvolvimento
**Stack escolhida:** Kotlin + Spring Boot (Java 17) · PostgreSQL · Docker Compose

---

## 1. Contexto

Construir o núcleo de um sistema de **reserva de ingressos** para eventos com capacidade
limitada, operando em modelo **flash sale** (janelas de venda com alto pico de concorrência).

Após a entrega haverá sessão de *code review* e apresentação das decisões arquiteturais.

### Requisitos funcionais

| Método | Rota                              | Descrição                  |
|--------|-----------------------------------|----------------------------|
| POST   | `/events`                         | Criar evento               |
| GET    | `/events/:id`                     | Consultar disponibilidade  |
| POST   | `/events/:id/reservations`        | Reservar ingressos         |
| GET    | `/reservations/:id`               | Consultar reserva          |
| DELETE | `/reservations/:id`               | Cancelar reserva           |

### Requisitos não funcionais

1. Múltiplas instâncias simultâneas da API
2. **Nunca permitir oversell**
3. Expiração automática de reservas pendentes
4. Idempotência
5. Consistência eventual para disponibilidade
6. Tratamento explícito de erros

### Restrições técnicas

- Linguagem, framework e banco de dados livres
- **Obrigatórios:** Docker Compose, testes automatizados e README
- Entrega em repositório GitHub com instruções, decisões arquiteturais, trade-offs e evoluções futuras

---

## 2. Arquitetura proposta

```
┌────────────┐     ┌────────────┐     ┌───────────────────┐
│  API (x N) │────▶│  Postgres  │◀────│ Worker (expiração)│
│ instâncias │     │ (fonte de  │     └───────────────────┘
└────────────┘     │  verdade)  │
                   └────────────┘
```

- **API stateless** — N instâncias atrás do load balancer; todo estado vive no Postgres.
- **PostgreSQL como fonte de verdade** — oversell é problema de *integridade transacional*;
  o banco relacional resolve com garantias, não com lógica aplicacional (que pode falhar
  com N instâncias).
- **Worker de expiração** — processo separado (cron/compose) que libera reservas pendentes.

### Stack

| Camada      | Escolha                          | Justificativa                                      |
|-------------|----------------------------------|----------------------------------------------------|
| Linguagem   | Kotlin                           | Tipagem forte, concisidade, interoperabilidade JVM |
| Framework   | Spring Boot 3.5 (Web, JPA, Validation) | Ecossistema maduro, DI, tratamento de erros   |
| Banco       | PostgreSQL 16                    | Transações, constraints, locks de linha            |
| Testes      | JUnit 5 + Spring Test            | Integração + teste de concorrência                |
| Infra       | Docker Compose                   | Restrição do exercício, multi-instância local      |

---

## 3. Decisão central: garantia de "nunca oversell"

### Alternativas avaliadas

| # | Abordagem                                             | Prós                                            | Contras                                             |
|---|-------------------------------------------------------|--------------------------------------------------|-----------------------------------------------------|
| A | **`UPDATE condicional` atômico** (recomendada)        | Simples, atômico, funciona com N instâncias, sem lock explícito | Precisa de índice bom                        |
| B | `SELECT ... FOR UPDATE` + checagem                    | Flexível para regras complexas                   | Lock de linha → contenção em pico de flash sale     |
| C | Checagem apenas na aplicação + INSERT                 | Código simples                                   | **Falha com concorrência** — oversell garantido em algum momento |
| D | Fila serializante (Redis Streams / lock distribuído)  | Escala horizontal da escrita                     | Complexidade extra, mais um ponto de falha          |

### Decisão

**Alternativa A**, com `CHECK` constraint no banco como *segunda camada de defesa*:

```sql
-- Atômico: só incrementa se couber na capacidade
UPDATE events
   SET reserved = reserved + $qtd
 WHERE id = $1
   AND reserved + $qtd <= capacity;
-- 0 linhas afetadas => 409 CAPACITY_EXCEEDED
```

**Trade-off aceito:** em cenários com regras muito complexas de alocação, `FOR UPDATE` seria
mais flexível — mas para o modelo atual o condicional é suficiente e evita contenção.

---

## 4. Modelo de dados

```sql
events (
  id, name, capacity, reserved, status, created_at
  -- CHECK (reserved <= capacity)
)

reservations (
  id, event_id, quantity, status,          -- PENDING | CONFIRMED | CANCELLED | EXPIRED
  idempotency_key,                         -- UNIQUE
  expires_at, created_at
)
-- INDEX (event_id)
-- partial index em (status, expires_at) para a varredura de expiração
```

---

## 5. Requisitos não funcionais → estratégia

| Requisito                        | Estratégia                                                                 |
|----------------------------------|----------------------------------------------------------------------------|
| Múltiplas instâncias             | API stateless; todo estado no Postgres                                     |
| **Nunca oversell**               | `UPDATE condicional` atômico + `CHECK` constraint                          |
| Expiração automática             | Worker cron varrendo `expires_at < now()` **e** coleta *on-demand* na leitura; ambos atômicos/idempotentes (N workers não podem devolver capacity duas vezes) |
| Idempotência                     | Header `Idempotency-Key`: repetição retorna a reserva anterior (200); `UNIQUE` no banco como guarda |
| Consistência eventual (leitura) | `GET /events/:id` pode servir de cache/réplica; **reservar é fortemente consistente**, ler disponibilidade é eventual |
| Tratamento explícito de erros    | Envelope padronizado (seção 6)                                             |

### Expiração — detalhe

Duas estratégias complementares, ambas **idempotentes**:

1. *On-demand*: ao consultar, mover expiradas para `EXPIRED` e devolver capacity.
2. *Worker*: agendador no compose varrendo `expires_at < now()`.

A devolução de capacity deve ser uma única transação atômica
(`UPDATE ... SET reserved = reserved - qty WHERE ...`) para não liberar vaga em dobro.

---

## 6. Contrato de erros

```json
{
  "error": {
    "code": "CAPACITY_EXCEEDED",
    "message": "Evento sem disponibilidade para a quantidade solicitada",
    "details": { "eventId": "..." }
  }
}
```

| Código HTTP | `code`                    | Quando                              |
|-------------|---------------------------|-------------------------------------|
| 400         | `VALIDATION_ERROR`        | Payload inválido                    |
| 404         | `NOT_FOUND`               | Evento/reserva inexistente          |
| 409         | `CAPACITY_EXCEEDED`       | Tentativa de oversell               |
| 409         | `RESERVATION_EXPIRED`     | Reserva expirada (ex.: cancelamento)|
| 422        | `INVALID_QUANTITY`        | Quantidade ≤ 0 ou acima do limite   |

---

## 7. Estrutura do repositório

```
cielo/
├── docs/                    # Este planejamento e demais docs
├── src/
│   ├── main/kotlin/com/cielo/flashbooking/
│   └── test/kotlin/         # unit + integração + concorrência
├── src/main/resources/application.yml
├── docker-compose.yml       # api (x2) + postgres + worker
├── Dockerfile
├── build.gradle.kts
└── README.md
```

---

## 8. Plano de testes (obrigatórios)

- [ ] Integração de **cada endpoint** dos requisitos funcionais
- [ ] **Teste de concorrência:** N requisições simultâneas para capacidade menor que N →
      assert de que `reserved <= capacity` **sempre** (nunca oversell)
- [ ] Idempotência: mesma chave → mesma reserva, sem duplicar
- [ ] Expiração devolve capacity exatamente uma vez
- [ ] Cancelamento devolve capacity
- [ ] Formato do envelope de erro para todos os casos

---

## 9. Evoluções futuras (para o README)

- Leitura via réplica/CDN para o pico do flash sale (consistência eventual de verdade)
- Sharding por evento
- Outbox pattern + mensageria (Kafka) para notificações
- Rate limiting / fila de espera na janela de venda

---

## 10. Registro de decisões (ADR resumido)

| # | Decisão                         | Alternativa rejeitada          | Motivo                                      |
|---|---------------------------------|--------------------------------|---------------------------------------------|
| 1 | Kotlin + Spring Boot            | Node/Express, Python/FastAPI   | Preferência do time, ecossistema JVM        |
| 2 | PostgreSQL                      | Redis-only                     | Integridade transacional como garantia      |
| 3 | `UPDATE condicional` p/ oversell| `FOR UPDATE`, lógica na app    | Atomicidade sem contenção; app não garante  |
| 4 | Idempotência por header + UNIQUE| Só app-side                    | Duas camadas: UX + integridade no banco     |
| 5 | Expor disponibilidade eventual  | Leitura sempre forte           | RNF pede consistência eventual; reserva é o ponto forte |

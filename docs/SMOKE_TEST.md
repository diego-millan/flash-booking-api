# Smoke test — Flash Booking

> Execução contra a **pilha real** (Docker Compose): Postgres + **2 réplicas da API** atrás de
> um load balancer nginx. Complementa [`PROGRESSO.md`](./PROGRESSO.md) §7.5 e fornece os exemplos
> de `curl` do `README.md`.
>
> Reproduzível:
>
> ```bash
> ./gradlew bootJar                 # gera build/libs/flash-booking-0.0.1-SNAPSHOT.jar (único)
> docker compose up --build -d      # postgres + api x2 + lb
> ./docker/smoke.sh                 # 26 verificações → PASS=26 FAIL=0 (exit 0)
> ```

`docker compose ps` deve mostrar: `cielo-postgres-1`, `cielo-api-1`, `cielo-api-2`, `cielo-lb-1`.

---

## Resultado — 26/26 PASS

Execuções consecutivas com o mesmo resultado (`PASS=26 FAIL=0`, `exit 0`).

| # | Cenário | Verificação | Esperado | Obtido |
|---|---------|-------------|----------|--------|
| 1 | Health | `GET /actuator/health` | `200` `{"status":"UP"}` | ✅ |
| 2 | Criação | `POST /events` (capacity 5) | `201` + `reserved=0, available=5` | ✅ |
| 2 | Criação | `GET /events/:id` | `200` | ✅ |
| 3 | Reserva | `POST /events/:id/reservations` (qty 2, com chave) | `201` | ✅ |
| 3 | Idempotência | repetir a mesma `Idempotency-Key` | `200` com a **mesma** reserva | ✅ |
| 3 | Header ausente | `POST` sem `Idempotency-Key` | `400 VALIDATION_ERROR` | ✅ |
| 3 | Quantidade | `quantity=0` | `422 INVALID_QUANTITY` | ✅ |
| 3 | Limite | `quantity=11` | `422` + `details.limit=10` | ✅ |
| 3 | Mídia | `Content-Type: text/plain` | `415 UNSUPPORTED_MEDIA_TYPE` | ✅ |
| 3 | Método | `GET /events/:id/reservations` | `405 METHOD_NOT_ALLOWED` | ✅ |
| 3 | Estado | `GET /events/:id` após reservar | `reserved=2, available=3` | ✅ |
| 4 | Consulta | `GET /reservations/:id` | `200` (`PENDING`, `expiresAt`, `createdAt`) | ✅ |
| 4 | Id inválido | `GET /reservations/abc` | `400` | ✅ |
| 4 | Inexistente | `GET /reservations/999999` | `404` + `details.reservationId` | ✅ |
| 5 | Esgotado | 3× venda em evento de capacity 3 | 3 × `201` | ✅ |
| 5 | Esgotado | 4ª venda | `409 CAPACITY_EXCEEDED` + `available=0` | ✅ |
| 5 | Esgotado | `GET /events/:id` | `reserved=3, available=0` (**nunca maior**) | ✅ |
| 6 | Cancelamento | `DELETE /reservations/:id` | `200` + `status=CANCELLED` | ✅ |
| 6 | Cancel 2× | repetir o `DELETE` | `200` **sem** devolver capacity de novo | ✅ |
| 6 | Cancelamento | `GET /events/:id` | `reserved=0, available=5` (capacity devolvida 1×) | ✅ |
| 7 | Rota inexistente | `GET /events/999999` | `404 NOT_FOUND` | ✅ |
| 7 | Método | `DELETE /events/:id` | `405` | ✅ |
| 7 | Rota | `POST /nope` | `404` + `details.path` | ✅ |
| 7 | Payload | `POST /events` sem `capacity` | `400 VALIDATION_ERROR` (Jackson estrito) | ✅ |

---

## Evidência 1 — o tráfego passa pelas duas réplicas

Mapeamento de IP (Docker):

```
/cielo-api-1 -> 172.18.0.3
/cielo-api-2 -> 172.18.0.4
```

Log do load balancer (`docker compose logs lb`), com `$upstream_addr` em cada linha:

```
"POST /events HTTP/1.1" 201 via 172.18.0.3:8080
"POST /events/3/reservations HTTP/1.1" 201 via 172.18.0.4:8080
"GET /events/3 HTTP/1.1" 200 via 172.18.0.3:8080
"DELETE /reservations/1 HTTP/1.1" 200 via 172.18.0.4:8080
"GET /reservations/5 HTTP/1.1" 200 via 172.18.0.4:8080
"GET /events/4 HTTP/1.1" 200 via 172.18.0.3:8080
```

As duas réplicas servem requisições (round-robin do nginx). O caso mais interessante: a reserva
é **criada pela réplica `.3`** e **lida pela `.4`** com o mesmo estado — todo o estado vive no
Postgres, a API é stateless (planejamento §3).

Também foi observado na prática o `409 IDEMPOTENCY_CONFLICT`: rodar o smoke test duas vezes com
as mesmas chaves (sem o sufixo por execução) devolve `409` ao reutilizar a chave em outro evento.

---

## Evidência 2 — o worker de expiração roda no ambiente

Para não confundir com a coleta *on-demand* (que só age quando a API é lida), a reserva foi
vencida **direto no banco** e o estado foi consultado **sem nenhuma requisição HTTP**:

```sql
UPDATE reservations SET expires_at = now() - interval '1 minute' WHERE id = 6;
-- sleep 7s  (varredura do worker é a cada 5s)

 id | status  | quantity            -- reservations
  6 | EXPIRED |        4

 id | capacity | reserved | available   -- events
  5 |        4 |        0 |         4
```

Depois de 7 s, **sem passar pela API**: status `EXPIRED` e `reserved` devolvido de 4 para 0
(`available` de volta a 4). A coleta *on-demand* e a varredura compartilham o mesmo
`UPDATE ... WHERE status = 'PENDING'`, então liberam no máximo uma vez.

---

## O que o smoke test pegou (e foi corrigido)

| # | Problema | Sintoma | Correção |
|---|----------|---------|----------|
| 1 | `Dockerfile` copiava `build/libs/*.jar` com **dois** jars (boot + plain) | `COPY failed: found 2 files, expected 1 file` | `tasks.jar { enabled = false }` no Gradle: `build/libs` fica com um único artefato |
| 2 | `deploy: replicas: 2` + `ports: 8080:8080` | conflito: as 2 réplicas disputam a porta do host | réplicas passam a `expose: 8080` e o acesso externo é via **`lb` (nginx)** — exatamente "N instâncias atrás do load balancer" do planejamento §3 |
| 3 | `log_format` dentro do `server` | `[emerg] "log_format" directive is not allowed here` | mover para o contexto `http` (topo do `docker/nginx/default.conf`) |
| 4 | `proxy_pass http://api` sem porta | `502 Bad Gateway` (`connect() ... port 80`) | `set $api http://api:8080;` + `resolver 127.0.0.11` (DNS do Docker, re-resolve a cada 10 s) |
| 5 | Smoke test reutilizava as mesmas chaves | `409 IDEMPOTENCY_CONFLICT` na segunda execução | chaves com sufixo por execução (`date +%s%N`) |

---

## Como verificar manualmente

```bash
docker compose logs -f lb        # acompanha cada requisição e em qual réplica caiu
curl -s localhost:8080/actuator/health
curl -s -X POST localhost:8080/events -H 'Content-Type: application/json' \
  -d '{"name":"Show","capacity":100}'
```

A API responde em `http://localhost:8080`; o Postgres de produção local em `localhost:5432`
(banco `flash_booking`). Os testes usam outro banco (`flash_booking_test`) para não misturar.

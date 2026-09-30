# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

> **History note:** the two initial commits (`1f77c7d` scaffold, `ad30e41` planning document)
> predate this file, created in `9c95d72`, so they could not update it; their content is
> covered by the first entries under `Added`. From `9c95d72` onwards, **every** commit updates
> `CHANGELOG.md` (convention in the "Commits and Changelog" section of `AGENTS.md`).

## [Unreleased]

### Added

- Initial project scaffold: Spring Boot 3.5 + Kotlin 2.1, Gradle 8.14.4, Dockerfile and
  docker-compose.yml (Postgres 16 + API with 2 replicas).
- Initial planning document (`docs/PLANEJAMENTO.md`) with architecture, decisions and trade-offs.
- `AGENTS.md` with team conventions (English code, no comments, unit tests per
  Service/Controller/Repository, changelog on every commit).
- `CHANGELOG.md` following Keep a Changelog.
- `POST /events` endpoint with bean validation and standard error envelope
  (`VALIDATION_ERROR`, `INTERNAL_ERROR`).
- Flyway migration `V1__create_events.sql` with `events` table, positive capacity and
  `reserved <= capacity` CHECK constraints.
- Unit tests for `EventService`, `EventController` and `EventRepository`
  (12 tests, including validation and malformed body cases).
- `status` column on `events` (`ACTIVE` | `PAUSED`) via Flyway migration
  `V2__add_status_to_events.sql`, exposed together with `createdAt` in the API response.
- `ApiException` base class so domain errors map to HTTP status codes in one place.
- Strict Jackson deserialization (`fail-on-missing-creator-properties`): a missing required
  field returns `400 VALIDATION_ERROR` instead of a silent default value.
- Test coverage grew from 12 to 24 tests: error envelope for every response class, event
  status and the database-level `CHECK (reserved <= capacity)` guarantee.
- `GET /events/:id` endpoint returning availability (`available = capacity - reserved`),
  `404 NOT_FOUND` for unknown events and `400 VALIDATION_ERROR` for non-numeric ids.
- `NotFoundException` mapped to the standard error envelope.
- `POST /events/:id/reservations` endpoint: atomic conditional `UPDATE` that increments
  `events.reserved` only when it fits the capacity, never overselling; `Idempotency-Key`
  header is required and a repeated key returns `200` with the previous reservation.
- Flyway migration `V3__create_reservations.sql` with `reservations` table, `CHECK
  (quantity > 0)`, `UNIQUE (idempotency_key)`, `FK → events(id)` and indexes for event
  lookups plus a partial index on pending expirations.
- `Reservation`, `ReservationStatus` (`PENDING | CONFIRMED | CANCELLED | EXPIRED`),
  `ReservationRepository`, `ReservationWriter` (transactional unit) and `ReservationService`
  (validation, idempotency and replay).
- New error codes: `409 CAPACITY_EXCEEDED`, `409 IDEMPOTENCY_CONFLICT`, `422
  INVALID_QUANTITY` above the configurable per-reservation limit
  (`flash-booking.reservation.max-quantity`, default 10) and `400 VALIDATION_ERROR` for a
  missing `Idempotency-Key` header.
- Reservation TTL (`flash-booking.reservation.ttl-minutes`, default 10) stored in `expires_at`.
- 38 new tests (70 in total) covering the reservation flow, including an end-to-end test
  that sells out an event and asserts `reserved == capacity` (never greater).
- `GET /reservations/:id` endpoint returning status, quantity, `expiresAt` and `createdAt`,
  with `404 NOT_FOUND` (`details.reservationId`) for unknown reservations.
- `DELETE /reservations/:id` endpoint cancelling a reservation and returning the capacity
  atomically. The `UPDATE ... WHERE status IN ('PENDING','CONFIRMED')` is the serialization
  point: a concurrent or repeated delete updates zero rows and never releases capacity twice.
  Cancelling an already cancelled reservation answers `200` without a second release, while
  an expired one answers `409 RESERVATION_EXPIRED` (the worker already returned its capacity).
  `EventRepository.releaseReserved` guards `reserved >= quantity` so capacity can never go
  negative (97 tests in total).
- Automatic reservation expiry, both strategies from the plan and both idempotent:
  a `@Scheduled` sweep in `ReservationExpiryService` (`flash-booking.reservation.expiry-scan-ms`,
  default 5s) and on-demand collection inside `GET /reservations/:id` and
  `DELETE /reservations/:id`. Both go through `ReservationWriter.expire`, where
  `UPDATE ... SET status = 'EXPIRED' WHERE status = 'PENDING'` is the serialization point,
  so N API replicas running the worker release each reservation exactly once. A past-due
  reservation cancelled by the user is collected first and answered with
  `409 RESERVATION_EXPIRED` (113 tests in total).
- Real concurrency test hitting the embedded server on a random port
  (`ReservationConcurrencyIntegrationTest`): 20 requests released together by a start gate
  against an event with 5 seats must yield exactly 5 `201` and 15 `409 CAPACITY_EXCEEDED`,
  `reserved == 5` and never more; with quantity 2 the same 20 requests must yield exactly 2
  `201`, `reserved == 4` and `available == 1`, proving the conditional update admits every
  request that still fits (115 tests in total).
- `docs/PROGRESSO.md` as the living status document, updated after every step: endpoint,
  non-functional requirement, error contract and configuration tables, the test matrix
  growing from 12 to 115 tests, decisions 1-19, commit history, the pending-work checklist
  (§7) and the session resumption notes (§8).
- `docs/CODE_REVIEW.md` documenting the review-worthy decisions: conditional update,
  isolated transactional unit, two-layer idempotency with post-rollback re-read, strict
  payload deserialization, exception-handler fallback, real-PostgreSQL tests, the
  serialization points of cancel and expiry, the worker running on every replica, and the
  pitfalls found along the way (Kotlin covariant `Map`, timestamp precision, unique-violation
  translation, self-invocation bypassing the Spring proxy).
- `docker/smoke.sh`: smoke test for the running stack (26 checks) covering health, event
  creation, reservations with idempotency, sell-out, cancellation and the whole error
  contract (400/404/405/409/415/422). It exits non-zero when any expectation fails and uses
  idempotency keys unique per run, so it can be repeated back to back.
- `docs/SMOKE_TEST.md` with the evidence: the 26/26 result table, the nginx log proving both
  replicas serve traffic, the expiry worker proven directly in the database (no HTTP read)
  and the five issues the smoke test caught. `docs/PROGRESSO.md` marks "multiple instances",
  "eventual consistency" and section 7.5 as done.
- `README.md` (mandatory deliverable): system description, stack, architecture, prerequisites,
  how to run the stack and the tests, the 5 routes with real `curl` request/response examples,
  the error contract, the anti-oversell guarantee, a summarized ADR with trade-offs,
  configuration, repository structure, future evolutions and links to every document.
- Structured logging instead of static log lines: an access log at the end of every request
  (`request method=... path=... status=... durationMs=...`, ids included in the path),
  business events with the objects involved (`event created eventId=...`,
  `reservation created/replayed/cancelled reservationId=...`,
  `reservation expired ... source=worker|on-demand`) emitted only after the transaction
  commits, and error lines carrying `code`, `path` and `details` — `4xx` at `WARN`, `5xx` at
  `ERROR` with the stack trace. `/actuator` requests are skipped so the compose healthcheck
  (every 5s on each replica) never floods the log. New `RequestLoggingInterceptor` +
  `WebConfig` in the `http` package, covered by `RequestLoggingIntegrationTest` (5 tests,
  120 in total); the expiry sweep unit test now stubs the writer return value.
- `docs/MANUAL.md`: manual to run the project unaided — the 7 steps to bring the whole stack
  up with Docker (jar, `compose up`, health, smoke test, logs, shutdown), how to run a whole
  test class or a single test method (exact name or wildcard, with the 15 classes and their
  120 tests), and a `curl` example for every endpoint with real responses, every error
  variation and a summary table of all 21 cases.
- `docs/APRESENTACAO.md`: presentation script — 12-minute timeline, narration for each block,
  the live demos with their real output (flow + idempotency, 20 requests vs 5 seats through
  the load balancer, database proofs, the expiry trick), the likely code-review questions with
  answers and proofs, the log walkthrough and a plan B for demo failures.
- OpenAPI contract generated from the code (springdoc-openapi) with Swagger UI: `/swagger-ui`
  and `/v3/api-docs` document the 5 routes with every status of each operation (including the
  `200` of the idempotent replay beside the `201`), the shared `ErrorResponse` envelope, the
  **required** `Idempotency-Key` header and the `capacity`/`quantity` limits. The spec is locked
  against drift by `OpenApiContractIntegrationTest` (5 tests: routes, statuses, header and
  schemas must match the code) and `/v3/api-docs` + `/swagger-ui` stay out of the access log
  like `/actuator` (126 tests in total).
- Documentation updated for the interactive contract: `README` gained an "OpenAPI and Swagger
  UI" section (URLs, what is documented and the anti-drift test), `MANUAL` a Swagger walkthrough
  with the commands to open the UI and fetch the spec, `APRESENTACAO` a live demo block and a
  likely review question, `CODE_REVIEW` decision 9 (contract generated and tested vs handwritten
  spec), and every test count moved from 120 to 126.

### Changed

- Consolidated the eight step-by-step `docs/PROGRESSO.md` entries of this file into a single
  bullet, and documented at the top that the two initial commits predate this changelog.
- `capacity <= 0` now returns `422 INVALID_QUANTITY` (as planned in `docs/PLANEJAMENTO.md`
  section 6) instead of `400 VALIDATION_ERROR`.
- Tests now run against **real PostgreSQL 16** (Docker Compose) instead of in-memory H2:
  `EventRepositoryTest` and `FlashBookingApplicationTests` use the `flash_booking_test`
  database, execute the Flyway migrations and validate the schema with `ddl-auto: validate`.
- `docker/postgres/init.sql` creates the `flash_booking_test` database on first boot.

### Fixed

- Unknown paths, unsupported methods and unsupported content types returned `500
  INTERNAL_ERROR` because of a catch-all exception handler; they now return `404 NOT_FOUND`,
  `405 METHOD_NOT_ALLOWED` and `415 UNSUPPORTED_MEDIA_TYPE` with the error envelope.
- The 2 API replicas in `docker-compose.yml` both published host port `8080`, so scaling the
  service could not work. Replicas now only `expose` 8080 and a new `lb` service (nginx)
  publishes 8080 with round-robin over both instances and logs `$upstream_addr` — the
  "N instances behind a load balancer" from the plan section 3.
- The Dockerfile `COPY build/libs/*.jar app.jar` failed when Gradle produced both the boot
  jar and the plain jar; `tasks.jar { enabled = false }` keeps a single artifact in
  `build/libs`.

### Removed

- H2 test dependency and the in-memory datasource used by tests: the `reserved <= capacity`
  constraint can only be trusted when it is enforced by PostgreSQL itself.

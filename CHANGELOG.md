# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
- `docs/PROGRESSO.md` tracking implementation progress: endpoints, non-functional
  requirements, test matrix, decisions and next steps.
- Detailed pending-work checklist in `docs/PROGRESSO.md` (§7): open endpoints, pending
  non-functional requirements and tests, required `README.md` outline, infrastructure
  tasks, plus a session resumption section (§8).
- `docs/PROGRESSO.md` updated after `GET /events/:id`: endpoint and test matrix tables
  (32 tests) and commit history.
- `docs/PROGRESSO.md` updated after `POST /events/:id/reservations`: endpoint, non-functional
  requirements, error contract and test matrix (70 tests), plus decisions 11-15.
- `docs/CODE_REVIEW.md` documenting the review-worthy decisions: conditional update,
  isolated transactional unit, two-layer idempotency with post-rollback re-read, strict
  payload deserialization, exception-handler fallback, real-PostgreSQL tests and the three
  pitfalls found along the way (Kotlin covariant `Map`, timestamp precision, unique-violation
  translation).
- `docs/PROGRESSO.md` updated after `GET /reservations/:id`: endpoint table (4 of 5 done),
  test matrix (77 tests) and commit history.
- `docs/PROGRESSO.md` updated after `DELETE /reservations/:id`: all 5 endpoints done, test
  matrix (97 tests), decisions 16-17 (cancel serialization point and release guard) and
  commit history.

### Changed

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

### Removed

- H2 test dependency and the in-memory datasource used by tests: the `reserved <= capacity`
  constraint can only be trusted when it is enforced by PostgreSQL itself.

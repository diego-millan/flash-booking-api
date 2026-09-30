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
- `docs/PROGRESSO.md` tracking implementation progress: endpoints, non-functional
  requirements, test matrix, decisions and next steps.

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

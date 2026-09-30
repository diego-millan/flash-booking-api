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

### Removed

- Unused `src/test/resources/application-test.yml` (tests run on in-memory H2 for now;
  migration to Testcontainers/Postgres planned once Docker is available).

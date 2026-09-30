# AGENTS.md

Guidelines for all contributors (human and AI) working on this repository.

## Language

- All code must be written in **English**: identifiers, classes, methods, packages, routes, error codes and test names.
- Documentation for the team may be written in Portuguese (`docs/`, `README.md`).

## Comments

- Avoid writing comments in the code unless it is **really necessary**.
- Code should be self-explanatory through good naming.
- Only comment on the *why* (non-obvious decisions, workarounds), never the *what*.

## Testing

- Every **Service**, **Controller** and **Repository** class must have unit tests.
- Tests must follow the naming pattern: `` `should <expected behavior> when <condition>` ``.
- Tests must be independent and must not rely on external services unless explicitly integration tests.

## Commits and Changelog

- Every commit **must** update `CHANGELOG.md` in the same commit.
- Keep the `[Unreleased]` section grouped by `Added`, `Changed`, `Fixed`, `Removed`.
- Release entries are created by moving the `Unreleased` content to a versioned section.
- Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/):
  - `feat: ...` for new functionality
  - `fix: ...` for bug fixes
  - `docs: ...` for documentation
  - `test: ...` for tests
  - `chore: ...` for maintenance

## Architecture

- Business rules live in **Service** classes; Controllers only handle HTTP concerns.
- Never trust the application layer alone for data integrity: enforce invariants
  (e.g. never oversell) with database constraints as well.
- All API errors must follow the envelope defined in `docs/PLANEJAMENTO.md`.

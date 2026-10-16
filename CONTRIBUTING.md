# Contributing

Thanks for helping out! This guide covers how to get set up and what we expect in a pull request. By taking part, you agree to follow our [Code of Conduct](CODE_OF_CONDUCT.md).

## Setup

Follow [Local development](README.md#local-development) in the README.

## Git hooks

Checks run automatically before you commit and push. `npm install` in `frontend/` turns them on;
backend-only contributors can run `make hooks` instead.

| Hook | When | What it checks |
|------|------|----------------|
| `pre-commit` | `git commit` | No `.env` files, private keys, conflict markers or files over 5 MB. Staged frontend files: ESLint, type check and production build. Staged backend files: `gradle build` — compile and tests (needs Docker running). |
| `pre-push` | `git push` | Light, about a second: refuses direct pushes to `main`, and re-checks the outgoing commits for `.env` files, private keys and conflict markers (in case a commit skipped its hook). No builds or tests. |

The hooks live in `.githooks/`. To skip them once, in an emergency, use `--no-verify` (or `SKIP_HOOKS=1`).

## Workflow

1. Branch from `main`: `feat/<short-name>`, `fix/<short-name>`, `chore/<short-name>`, `docs/<short-name>`.
2. Keep pull requests small and focused on one change.
3. Run `make check` before opening a pull request — it runs backend tests and the frontend lint, typecheck, tests and build.
4. Use [Conventional Commits](https://www.conventionalcommits.org/): `feat: add tag filter to posts list`, `fix(backend): ...`.

## Conventions

Read [docs/architecture.md](docs/architecture.md) first. In short:

**Backend**
- Group code by feature (`post/`, `tag/`, ...), with `controller/ dto/ entity/ repository/ service/` inside.
- DTOs are Java records with Bean Validation annotations; every response uses `ApiResponse`.
- Schema changes go in a **new** Flyway migration (`V{n}__description.sql`); never edit a merged one.
- Never log secrets or personal data.

**Frontend**
- Colors, radii and fonts come from `src/styles/tokens.css` — no raw hex values in components.
- Server-only environment variables never get the `NEXT_PUBLIC_` prefix.
- Next.js 16 differs from older versions; check `frontend/node_modules/next/dist/docs/` when in doubt.
- The frontend needs **npm ≥ 11.16** (`npm install -g npm@latest`). Older versions write a `package-lock.json` that `npm ci` in the Docker image rejects, so `frontend/.npmrc` makes them refuse to install.

## Reporting bugs and ideas

Open an issue and pick the form that fits — bug report, feature request, documentation issue, or question/discussion. Pull requests use a template with a description, how to test, and a checklist; fill in what applies.
For security problems, follow [SECURITY.md](SECURITY.md) instead.

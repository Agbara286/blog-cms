# blog-cms

Open-source CMS for writing, managing and publishing blog posts.

`blog-cms` has two jobs:

1. **Give editors one admin UI** to write posts in a rich-text editor, organise them with
   authors and tags, manage an image library, and move each post through its
   draft → published → archived lifecycle.
2. **Keep content in a single, consistent store**: a Spring Boot API that validates every
   change, enforces the publishing rules and persists everything in PostgreSQL.

- **Frontend** — Next.js 16 (App Router), React 19, TypeScript, Tailwind CSS v4
- **Backend** — Spring Boot 3.4, Java 21, Gradle
- **Database** — PostgreSQL 16, schema managed by Flyway

> **Status:** the CMS works end to end — write, edit and publish posts, manage authors,
> tags and images, all stored in PostgreSQL through the Spring Boot API.

## Design at a glance

```text
           Browser (admin UI)
                  │
                  │  same-origin only: /api/auth/*, /api/v1/*
                  ▼
        frontend (Next.js server)
         ├─ proxy.ts           no session cookie → /login
         ├─ /api/auth/*        login / logout, sets httpOnly session cookie
         └─ /api/v1/[...path]  proxy: cookie → Authorization: Bearer <JWT>
                  │
                  │  BACKEND_API_URL (server-side only, private network)
                  ▼
        backend (Spring Boot)
         ├─ CorrelationFilter  X-Correlation-ID on every request and log line
         ├─ Security           validates the JWT on every call
         ├─ Controllers        /api/v1/{posts,authors,tags,media,auth}
         └─ Services           business rules, slugs, content analysis
                  │
         ┌────────┴─────────┐
         ▼                  ▼
    PostgreSQL         Media storage
   (Flyway schema)    (Nextcloud via WebDAV)
```

The browser never talks to the backend directly. The backend needs no CORS config, can
stay on a private network, and answers every request with the same envelope:
`{"data": ..., "meta": ..., "errors": [...]}`.

Full details — auth, API, business rules and code layout — are in
[`docs/architecture.md`](docs/architecture.md).

## Workflow design

### Request flow

```text
Screen (*Canvas.tsx)
      │  calls
      ▼
modules/<feature>/api.ts        one function per backend endpoint
      │
      ▼
lib/api-client.ts  ──►  /api/v1/* route handler  ──►  backend /api/v1/*
                              │                            │
                              │ 401 → clear cookie,        │ Controller → Service
                              │ redirect to /login?next=…  │ → Repository → Postgres
                              ▼                            ▼
                        response envelope  ◄──────  ApiResponse / ApiError
```

### Post lifecycle

```text
                 create / duplicate
                         │
                         ▼
   ┌──────────────►   DRAFT   ◄──────────────┐
   │                     │                   │
   │ unpublish           │ publish           │ unpublish (restore)
   │                     │ (needs a title    │
   │                     │  and content)     │
   │                     ▼                   │
   └───────────────  PUBLISHED               │
                         │                   │
                         │ archive           │
                         ▼                   │
                     ARCHIVED  ──────────────┘
```

- A draft can be archived directly too; unpublishing an archived post restores it to draft.
- `publishedAt` is set on first publication and kept across unpublish/republish.
- Every save carries the `version` the editor loaded; a stale one gets
  `409 VERSION_CONFLICT` instead of overwriting someone else's changes.
- On save, the backend derives the post's plain text (for search), word count, reading time
  and the library images it embeds — images in use can't be deleted.

### Contribution workflow

```text
feature branch ──► git commit ──► git push ──► pull request ──► review ──► main
                       │              │
                       ▼              ▼
                  pre-commit      pre-push
                  hygiene,        no direct pushes to main,
                  lint, types,    no .env files, keys or
                  build, tests    conflict markers
                  (staged parts only)
```

Hooks live in [`.githooks/`](.githooks) and are enabled by `make install` (or `make hooks`).
Pre-commit runs only the checks for the parts of the repo you staged — frontend changes get
ESLint, typecheck and a production build; backend changes get the Gradle build with tests
(Docker required for Testcontainers).

## Repository layout

```
blog-cms/
├── frontend/            Next.js admin UI (own package.json, Dockerfile)
├── backend/             Spring Boot API (own Gradle wrapper, Dockerfile)
├── docs/                Architecture notes
├── .githooks/           pre-commit and pre-push checks
├── .github/             Issue and pull request templates
├── docker-compose.yml   Full local stack: postgres + backend + frontend
└── Makefile             Shortcuts for both stacks — run `make help`
```

Each app is self-contained: it builds, tests and ships on its own, with its own toolchain.

## Quick start (Docker)

Requires Docker with Compose v2.

Copy the example environment file:

    cp .env.example .env

Before starting the stack, edit `.env` and configure `NEXTCLOUD_URL`, `NEXTCLOUD_USERNAME`, and `NEXTCLOUD_PASSWORD` with valid Nextcloud credentials. Use a dedicated Nextcloud app password where supported. Set `NEXTCLOUD_REMOTE_DIRECTORY` to the remote upload directory if you want something other than `/BlogCMS`. The values in `.env.example` are placeholders and will not connect to a real instance.

Start the stack:

    make up

Alternatively, run `docker compose up --build`.

| Service  | URL                                    |
|----------|----------------------------------------|
| Frontend | http://localhost:3000                  |
| Backend  | http://localhost:8080/health/readiness |
| Postgres | `localhost:5432` (credentials from `.env`) |

With the defaults from `.env.example` you sign in as **admin@example.com / admin**. Change `ADMIN_EMAIL`, `ADMIN_PASSWORD_HASH`, and `JWT_SECRET` before exposing it anywhere.

## Local development

Requires **Java 21**, **Node.js ≥ 20.9** with **npm ≥ 11.16**, and Docker (for Postgres and backend tests).

```bash
make db                                   # start Postgres only
make backend                              # Spring Boot on :8080 (profile: local)

cp frontend/.env.example frontend/.env.local
make install && make frontend             # Next.js on :3000 (also enables the git hooks)
```

### Useful commands

| Command      | What it does                                                   |
|--------------|----------------------------------------------------------------|
| `make test`  | Backend tests (Testcontainers starts a real Postgres)          |
| `make lint`  | Frontend lint + typecheck                                      |
| `make check` | Everything: backend tests, frontend lint, types, tests, build  |

## Configuration

| Variable                     | App      | Description                                  |
|------------------------------|----------|----------------------------------------------|
| `SPRING_DATASOURCE_URL`      | backend  | JDBC URL, e.g. `jdbc:postgresql://host:5432/blog_cms` |
| `SPRING_DATASOURCE_USERNAME` | backend  | Database user                                |
| `SPRING_DATASOURCE_PASSWORD` | backend  | Database password                            |
| `ADMIN_EMAIL`                | backend  | Email of the single admin account            |
| `ADMIN_PASSWORD_HASH`        | backend  | bcrypt hash of the admin password (see below) |
| `JWT_SECRET`                 | backend  | Session-token signing key, ≥ 32 characters   |
| `JWT_TTL`                    | backend  | Session length (default `8h`)                |
| `NEXTCLOUD_URL`               | backend  | Base URL of the Nextcloud instance; use HTTPS in production |
| `NEXTCLOUD_USERNAME`          | backend  | Nextcloud account used for media storage |
| `NEXTCLOUD_PASSWORD`          | backend  | Nextcloud app password; keep it secret |
| `NEXTCLOUD_REMOTE_DIRECTORY`  | backend  | Remote directory for uploads (default `/BlogCMS`) |
| `SERVER_PORT`                | backend  | HTTP port (default `8080`)                   |
| `BACKEND_API_URL`            | frontend | Backend base URL, used server-side only      |
| `SESSION_COOKIE_SECURE`      | frontend | `false` to allow sign-in over plain HTTP on a host other than localhost (default: on in production) |

The admin password is never configured in plain text. Generate its hash with:

```bash
docker run --rm httpd:2.4-alpine htpasswd -nbBC 10 "" 'your-password' | tr -d ':\n'
```

In `.env`, wrap the hash in single quotes — it contains `$`, which Compose would otherwise expand.

See [`backend/.env.example`](backend/.env.example) and [`frontend/.env.example`](frontend/.env.example).

## Contributor entry points

Start with:

- [`docs/architecture.md`](docs/architecture.md) — principles, auth, API, business rules, code layout
- [`CONTRIBUTING.md`](CONTRIBUTING.md)
- [`backend/.env.example`](backend/.env.example) and [`frontend/.env.example`](frontend/.env.example)

## Picking up a task

Work in this repository is tracked as GitHub issues. Contributors and candidates both
follow the same route: pick one issue, implement it, open a pull request.

```text
pick an issue ──► comment to claim it ──► fork + branch ──► commit ──► push ──► pull request
                                                                                      │
                                                                       review ──► merge to main
```

1. **Choose an issue.** Browse
   [open issues](https://github.com/atomityhq/blog-crm/issues) and pick one that is not
   already assigned. If you are a candidate, use the issue you were pointed at.
   Nothing suitable? Open an issue yourself with the bug report or feature request form
   and wait for a maintainer to confirm the scope before you start.
2. **Claim it.** Comment on the issue saying you are taking it, so nobody duplicates the
   work. Ask your questions in that thread — the issue is the place to agree on scope.
3. **Fork and branch.** Fork the repository, then branch from `main` using the prefixes in
   [CONTRIBUTING.md](CONTRIBUTING.md#workflow): `feat/`, `fix/`, `chore/` or `docs/`,
   e.g. `git checkout -b feat/tag-filter`.
4. **Set up locally.** Follow [Local development](#local-development), then run
   `make install` so the git hooks are active.
5. **Make the change.** Keep it scoped to the issue — one issue per pull request. Follow
   the conventions in [`docs/architecture.md`](docs/architecture.md) and add or update
   tests for what you changed.
6. **Check before you push.** Run `make check` (backend tests, frontend lint, typecheck, tests and
   build). The pre-commit hook runs the checks for the parts you staged; the pre-push hook
   blocks direct pushes to `main`.
7. **Commit and push.** Use [Conventional Commits](https://www.conventionalcommits.org/),
   e.g. `feat: add tag filter to posts list`, then push the branch to your fork.
8. **Open a pull request** against `main`. The template asks for a description, how to test
   and a checklist — fill in what applies, and link the issue with `Closes #123`.
9. **Respond to review.** Push follow-up commits to the same branch; a maintainer merges
   once CI and review are green.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) and our [Code of Conduct](CODE_OF_CONDUCT.md). Security issues: see [SECURITY.md](SECURITY.md).

## License

[MIT](LICENSE)

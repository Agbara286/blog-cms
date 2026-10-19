# CLAUDE.md — agent guide for the issue #11 take-home

> Temporary file. It exists only on branch `feat/public-blog-endpoints` and is **deleted in task T27**
> so the upstream pull request stays focused on one issue. Read this file, then [`TRACKER.md`](TRACKER.md).

## Mission

Deliver **one pull request** that closes
[atomityhq/blog-cms#11](https://github.com/atomityhq/blog-cms/issues/11) — *feat: expose endpoints to access the blogs*:
public, read-only, paginated access to **published** posts (list + single post by id or slug), with tests and docs.

This is a hiring take-home (Java Backend Intern). Submission = a public link to the PR, emailed by
**Mon 26 Oct 2026, 23:59**. Target: submit **Sat 24 Oct**. Quality and fit with existing patterns beat feature count.

## How to work (protocol — follow exactly)

1. Read this file, then `TRACKER.md`. Pick the first task whose status is `TODO` and whose dependencies are `DONE`.
   **One agent at a time; tasks are sequential.** Tasks marked **[USER]** are done by the human — stop and ask.
2. Set the task to `IN_PROGRESS` in `TRACKER.md`. Do exactly that task's scope. No drive-by refactors.
3. Read the files named in the task and the existing code it must imitate **before** writing anything.
4. Verify with the task's command. Fast loop: `cd backend && ./gradlew test --tests '*BlogApiTest'`.
5. Set the task to `DONE` with a one-line note in `TRACKER.md`.
6. Stage **named files only** (`git add <paths>`). Never `git add -A` / `git add .`. Never stage `.env`.
7. Commit with a Conventional Commit message (the one in the tracker). End the message with:
   `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`
   Git hooks must run — **never** `--no-verify` or `SKIP_HOOKS=1`.
8. Push after **every** task: `git push -u origin feat/public-blog-endpoints` (plain push; the pre-push hook forbids `main`).
9. If anything fails (hook, build, push), fix the root cause and note it under **Blockers** in `TRACKER.md`.
   Do not rewrite history or force-push without the user's approval.
10. Tasks with no code change (T01–T03) record their outcome in the tracker and ride along in the next commit.

### Ask the user first (outward-facing / hard to reverse)
Commenting on the GitHub issue · opening or editing the PR · sending the email · force-pushing · deleting branches ·
anything that publishes content outside this repo's feature branch.

## Repository facts

- Monorepo: `backend/` (Spring Boot 3.4, Java 21, Gradle, PostgreSQL 16, Flyway) and `frontend/` (Next.js 16). **This work is backend + docs only.**
- Remotes: `origin` = `https://github.com/vishank020/blog-cms` (the user's public fork, push here);
  `upstream` = `https://github.com/atomityhq/blog-cms` (read-only; PR target, base branch `main`).
- Branch: `feat/public-blog-endpoints`, created from `main` at c26cd04. Hooks: `git config core.hooksPath .githooks`.
- Hooks: `pre-commit` = hygiene checks + `./gradlew build` for staged `backend/src|build files` (**Docker must be running**,
  Testcontainers starts Postgres); `pre-push` = blocks pushes to `main`, `.env` files, private keys, conflict markers.
- Windows: use Git Bash for `./gradlew`, `git`, `curl`; PowerShell also works (`.\gradlew.bat`). `make` may be missing — the
  Makefile targets are only wrappers (`git config core.hooksPath .githooks`, `cd backend && ./gradlew test`).

## Commands

```bash
docker info                                   # must succeed before any backend test/commit
cd backend && ./gradlew compileJava           # fast compile check
cd backend && ./gradlew test --tests '*BlogApiTest'   # fast loop
cd backend && ./gradlew build                 # what pre-commit runs (compile + all tests)
```
Test reports: `backend/build/reports/tests/test/index.html`.

## Code conventions (imitate, do not invent)

Source of truth: `docs/architecture.md`, `CONTRIBUTING.md`, `.editorconfig` (4-space Java, LF, final newline).

**Layout** — `io.atomity.blogcms.<feature>/{controller,dto,entity,repository,service}`. This work adds `blog/{controller,dto,service}`.

**Controllers** — `@RestController @RequestMapping("/api/v1/<plural>")`, constructor injection, thin; list endpoints bind query params into a
record (no `@RequestParam`), return `ApiResponse<List<T>>` with `PageMeta.of(page)`. One Javadoc line per endpoint
(see `post/controller/PostController.java`).

**DTOs** — Java `record`s with a `public static X of(Entity)` factory; flat (no inheritance); camelCase JSON; timestamps are UTC `Instant`.

**Services** — `@Service`, constructor injection, `@Transactional(readOnly = true)` for reads. Map entities to DTOs **inside** the
transaction (`spring.jpa.open-in-view=false`; lazy collections are batch-fetched, `default_batch_fetch_size: 50`).
404 → `ApiException.notFound("<Thing>")` → `404 NOT_FOUND`, message `"<Thing> not found"`.

**Envelope** — every JSON response is `ApiResponse` = `{"data": ..., "meta": ..., "errors": [...]}`. Errors carry `errorCode`, `message`,
`correlationId`. Malformed UUID path variable → 400 `BAD_REQUEST` (already handled by `GlobalExceptionHandler`).

**Reuse** (all verified to exist):
- `post/repository/PostSpecifications.hasStatus(PostStatus)` — null-safe `Specification<Post>`.
- `post/dto/PostListQuery.toPageable()` — sort whitelist (`updatedAt,title,publishedAt,wordCount`), direction check, page ≥ 0,
  size clamped 1–100 (default 10), `id` tiebreaker; throws `ApiException.badRequest("BAD_REQUEST", ...)`.
- `shared/{ApiResponse, ApiException, PageMeta}`, `author/dto/AuthorRef`, `tag/dto/TagRef`, `media/dto/MediaRef` (image URLs are already public).

**Security** — `auth/config/SecurityConfig.java`: public routes are listed as exact, method-scoped `requestMatchers` with a one-line comment
above each, before `.anyRequest().authenticated()`. **No `/**` wildcards.** JWT is validated by Spring's resource-server filter, which also
runs on public paths: an invalid/expired `Authorization` header → 401 even on public routes (document, don't change).

**Database** — schema only via new Flyway migrations; **never edit `V1__initial_schema.sql`**. This task needs **no migration**
(PR #18 for issue #10 adds `V2__…`; avoid a version collision).

**Tests** — Testcontainers Postgres (no H2). Integration tests: `<Feature>ApiTest extends IntegrationTest` (`src/test/java/io/atomity/blogcms/<feature>/`),
package-private test class and methods, descriptive camelCase names, **no** `@DisplayName`/`@Nested`/`@ParameterizedTest`, `// ── Section ──` banners,
static imports of MockMvc builders/matchers, AssertJ + `jsonPath(...)`. In `IntegrationTest`:
`call(request[, body])` sends the admin Bearer token; `mockMvc.perform(...)` sends **no** token (use it for public endpoints);
`data(result)` returns the `data` node; `json(obj)` serialises; the DB (`posts, authors, tags, media CASCADE`) is truncated before each test.
Copy fixture style from `post/PostApiTest.java` (`doc(...)`, `postBody(...)`, `createPost(...)` are package-private there — keep small private
copies in `BlogApiTest` rather than widening visibility). Unit tests without Spring: see `shared/SlugsTest`, `post/ContentAnalyzerTest`.

**Docs** — update `docs/architecture.md` (API table, Authentication, Backend layout) and the controllers line in `README.md` when behaviour changes.

## Guardrails

- Scope = issue #11 only. **Out of scope:** issue #10 / Markdown import, frontend changes, tag/author/`q` filters on public endpoints,
  rate limiting, CORS, OpenAPI, new migrations/indexes, new environment variables, fixing unrelated docs.
- Never commit `.env*` (only `*.example`), keys, tokens or personal data. Never log secrets.
- Never expose editor-only fields on public DTOs: `status`, `version`, `createdAt`, `focusKeyword`.
- Never reveal that a draft/archived post exists: everything not `PUBLISHED` is `404`.
- Don't claim something works unless you ran it. Record real command output/results in `TRACKER.md`.

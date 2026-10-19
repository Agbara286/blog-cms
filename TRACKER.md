# TRACKER — issue #11 "expose endpoints to access the blogs"

> Temporary file, deleted in **T27**. Agents: read [`CLAUDE.md`](CLAUDE.md) first (protocol + conventions), then work this list top to bottom.

| | |
|---|---|
| **Goal** | One PR to `atomityhq/blog-cms` closing #11: public, read-only, paginated published posts (list + by id + by slug), tests, docs |
| **Deadline** | Email a public PR link by **Mon 26 Oct 2026 23:59**. Target submit: **Sat 24 Oct** |
| **Branch** | `feat/public-blog-endpoints` (from `main` @ c26cd04) → push to `origin` (`vishank020/blog-cms`) after **every** task |
| **PR** | `vishank020:feat/public-blog-endpoints` → `atomityhq:main`, title `feat: expose public endpoints to read published blogs`, body has `Closes #11` |
| **Created** | 2026-10-19 |

**Status legend:** `TODO` · `IN_PROGRESS` · `DONE` · `BLOCKED` · `SKIPPED` · `OPTIONAL` (do only if ahead of schedule)
**Schedule:** Waves 1–2 Mon 19–Tue 20 · Wave 3 Wed 21 · Wave 4 Thu 22 · Wave 5 Fri 23 → submit Sat 24 · Sun–Mon buffer / review feedback

## Current state (update every task)

- Branch `feat/public-blog-endpoints` exists locally; `upstream` remote added; `core.hooksPath=.githooks` set (T02).
- Docker Desktop is **running** (`docker info` → server 29.4.1). T01 done, T03 baseline green.
- Waves 1–4 done and pushed: T04–T23 committed (commit per task), `*BlogApiTest` green, live curl verification passed (see Notes). T19 skipped on purpose; T18 done.
- `PLAN.md` stays untracked (local only, never enters the PR diff).
- Competing work seen upstream: PR #16 (also implements #11; maintainer asked for newest-first and no unused repo methods),
  PR #14/#18 (issue #10, #18 adds `V2__markdown_sources.sql`), PR #17 (re-implements existing `GET /posts`).

## Design (what is being built)

New package `io.atomity.blogcms.blog/{controller,dto,service}`. All endpoints `GET`, public, standard envelope.

| Endpoint | Returns | Notes |
|---|---|---|
| `GET /api/v1/blogs?sort&direction&page&size` | `ApiResponse<List<BlogSummaryResponse>>` + `meta` (`PageMeta`) | default `sort=publishedAt`, `direction=DESC` (**newest first**), size 10, max 100; sort whitelist from `PostListQuery` |
| `GET /api/v1/blogs/{id}` | `ApiResponse<BlogResponse>` | UUID; malformed → 400 `BAD_REQUEST` |
| `GET /api/v1/blogs/slug/{slug}` | `ApiResponse<BlogResponse>` | separate path ⇒ no id/slug ambiguity |

Not `PUBLISHED` (draft, archived, unpublished, unknown) → `404 NOT_FOUND`.
`BlogSummaryResponse` = `id, title, slug, excerpt, featured, authors, tags, coverImage, wordCount, readingTimeMinutes, publishedAt, updatedAt`.
`BlogResponse` = same + `content, coverImageAlt, seoTitle, metaDescription, canonicalUrl`.
**Never** included: `status, version, createdAt, focusKeyword`.

## Wave 1 — Setup & agent scaffolding (T01–T04)

| ID | Status | Task | Depends | Commit |
|---|---|---|---|---|
| T01 | DONE | **[USER]** Start Docker Desktop (`docker info` must print a server version). Optional: `gh auth login`. Comment on issue #11 to claim it (README step 2). | – | no commit |
| T02 | DONE | Git setup: `upstream` remote added + fetched (`main == upstream/main == c26cd04`), `core.hooksPath=.githooks`, branch `feat/public-blog-endpoints` created. | – | no commit |
| T03 | DONE | Baseline: `cd backend && ./gradlew build` on the untouched branch. Record result + duration under Notes. If red, stop and report. | T01, T02 | no commit |
| T04 | DONE | `TRACKER.md` + `CLAUDE.md` written (untracked). Remaining: `git add TRACKER.md CLAUDE.md`, commit, **first push**. Deviation: done before T03 because Docker is down; T03 result is added to this file later. | T02 | `docs: add agent tracker and workflow for issue #11` |

## Wave 2 — Implementation (T05–T11)

| ID | Status | Task | Depends | Verify | Commit |
|---|---|---|---|---|---|
| T05 | DONE | `PostSummaryResponse`: change `authorRefs(Post)` and `tagRefs(Post)` from package-private to `public static`. Nothing else. | T03 | `./gradlew compileJava` | `refactor(backend): expose author/tag ref mapping for blog DTOs` |
| T06 | DONE | `PostRepository`: add `Optional<Post> findByIdAndStatus(UUID id, PostStatus status);` and `Optional<Post> findBySlugAndStatus(String slug, PostStatus status);` (both must be used by `BlogService` — no dead methods). | T05 | compile | `feat(backend): look up published posts by id and slug` |
| T07 | DONE | `blog/dto/BlogSummaryResponse` and `BlogResponse` records with `of(Post)`; reuse `PostSummaryResponse.authorRefs/tagRefs`, `MediaRef.of`. Javadoc says what is deliberately omitted. | T06 | compile | `feat(backend): add public blog response DTOs` |
| T08 | DONE | `blog/dto/BlogListQuery(String sort, String direction, Integer page, Integer size)` with `toPageable()` = `new PostListQuery(PostStatus.PUBLISHED, null, null, null, sort == null ? "publishedAt" : sort, direction, page, size).toPageable()`. Plus `src/test/.../blog/BlogListQueryTest` (no Spring): default `publishedAt` DESC size 10; size clamp 1–100; negative page → 0; unknown sort and bad direction → `ApiException` `BAD_REQUEST`. | T07 | `./gradlew test --tests '*BlogListQueryTest'` | `feat(backend): add public blog list query` |
| T09 | DONE | `blog/service/BlogService` (`@Service`, `@Transactional(readOnly = true)`, ctor-inject `PostRepository`): `Page<BlogSummaryResponse> list(BlogListQuery)` via `postRepository.findAll(PostSpecifications.hasStatus(PostStatus.PUBLISHED), query.toPageable()).map(BlogSummaryResponse::of)`; `BlogResponse getById(UUID)` and `getBySlug(String)` via the T06 finders, `.orElseThrow(() -> ApiException.notFound("Blog"))`. | T08 | compile | `feat(backend): add blog service for published posts` |
| T10 | DONE | `blog/controller/BlogController` (`@RequestMapping("/api/v1/blogs")`): `list(BlogListQuery)` → `ApiResponse.ok(page.getContent(), PageMeta.of(page))`; `get(@PathVariable UUID id)`; `getBySlug(@PathVariable String slug)` on `/slug/{slug}`. Javadoc line per endpoint, "Public (see SecurityConfig)". | T09 | compile | `feat(backend): expose public blog endpoints` |
| T11 | DONE | `SecurityConfig`: before `.anyRequest().authenticated()` add comment `// Published blog posts are public so a website can read them without signing in.` and `.requestMatchers(HttpMethod.GET, "/api/v1/blogs", "/api/v1/blogs/*", "/api/v1/blogs/slug/*").permitAll()`. | T10 | `./gradlew test` (existing tests green) | `feat(backend): allow unauthenticated reads of published blogs` |

## Wave 3 — Tests (T12–T17) — `backend/src/test/java/io/atomity/blogcms/blog/BlogApiTest.java`

Public calls: `mockMvc.perform(get(...))` (no token). Fixtures: create/publish through the admin API with `call(...)`
(`POST /api/v1/posts`, `POST /api/v1/posts/{id}/publish|unpublish|archive`). Publishing sequentially yields strictly increasing `publishedAt`.

| ID | Status | Task | Depends | Commit |
|---|---|---|---|---|
| T12 | DONE | Scaffold + helpers. Test: list works **without a token** and returns only `PUBLISHED`; draft / archived / unpublished excluded; envelope has `data` array + `meta` (`page,size,totalElements,totalPages`). | T11 | `test(backend): cover public blog list` |
| T13 | DONE | Ordering & pagination: newest-first by default; `sort=title&direction=asc`; `page`/`size` → correct `totalElements`/`totalPages` and slices; page beyond range → 200 + empty `data`; `size=1000` clamped to 100; `sort=password` and `direction=sideways` → 400 `BAD_REQUEST`. | T12 | `test(backend): cover blog list sorting and pagination` |
| T14 | DONE | Detail by id: 200 with full Tiptap `content`, authors, tags, cover; 404 `NOT_FOUND` for draft, archived, unpublished-after-publish, unknown id; malformed id → 400. | T13 | `test(backend): cover blog detail by id` |
| T15 | DONE | Detail by slug: 200; 404 unknown/draft; editor changes slug → old 404, new 200; unpublish → 404, republish → 200. | T14 | `test(backend): cover blog detail by slug` |
| T16 | IN_PROGRESS | No leaks: `jsonPath(...).doesNotExist()` for `status`, `version`, `createdAt`, `focusKeyword` on list items **and** detail; draft title/body never appears in any list response. | T15 | `test(backend): ensure public blogs expose no internal fields` |
| T17 | DONE | Security boundary: `GET /api/v1/posts` and `/api/v1/posts/{id}` still 401 without token; `POST`/`PUT`/`DELETE` on `/api/v1/blogs…` → 401; valid Bearer also works on `/blogs`; invalid Bearer on a public path → 401 (documents known behaviour). After T17 the user may open a **Draft** PR for early visibility. | T16 | `test(backend): lock down security boundary of public blog API` |

## Wave 4 — Hardening & docs (T18–T22)

| ID | Status | Task | Depends | Commit |
|---|---|---|---|---|
| T18 | DONE | *(recommended, time-box 1h)* N+1 guard: with Hibernate statistics enabled for the test (`@TestPropertySource` `spring.jpa.properties.hibernate.generate_statistics=true`), listing 10 posts that have authors + tags stays within a small fixed statement budget. If over budget, fix via batch-friendly mapping — **do not** add `@EntityGraph` (collection fetch + `Pageable` = in-memory paging). | T17 | `test(backend): guard blog list against N+1 queries` |
| T19 | SKIPPED | *(stretch, default SKIP)* `ShallowEtagHeaderFilter` scoped to `/api/v1/blogs/*` (no `max-age` ⇒ unpublish is never served stale; frontend proxy already forwards `etag`/`if-none-match`) + 304 test. | T17 | `feat(backend): add ETag revalidation to public blog endpoints` |
| T20 | DONE | `docs/architecture.md`: API table rows for the 3 endpoints; Authentication bullet 3 lists them as public; backend layout tree gains `blog/`; business rule "only published posts are public, everything else 404, public DTOs omit editor-only fields". | T17 | `docs: document public blog endpoints in architecture` |
| T21 | DONE | `docs/architecture.md`: short "Public blog API" section — `curl` examples + sample JSON for list / by id / by slug and a 404 example. | T20 | `docs: add public blog API examples` |
| T22 | DONE | `README.md`: controllers line → `/api/v1/{posts,authors,tags,media,blogs,auth}`; one sentence mentioning the public read API. | T21 | `docs: mention public blog API in README` |

## Wave 5 — Verify, polish, submit (T23–T30)

| ID | Status | Task | Depends | Commit |
|---|---|---|---|---|
| T23 | DONE | Full verification: `./gradlew build` green; run stack (`docker compose up -d postgres` + backend `local` profile with dummy `NEXTCLOUD_*`); publish a post via the admin API; run the curl script below; paste real outputs under Notes. | T22 | `docs(tracker): record verification results` |
| T24 | DONE | Self-review `git diff upstream/main...HEAD` (use `/code-review`); walk `.github/pull_request_template.md` checklist; fix findings, one commit each. | T23 | `fix(backend): …` per finding |
| T25 | DONE | `git fetch upstream`; if `main` moved, **merge** `upstream/main` into the branch (no force-push), rerun `./gradlew build`. | T24 | `chore: merge upstream main` (only if needed) |
| T26 | DONE | Draft PR title + body in the appendix below (template sections, `Closes #11`, how to test, honest checklist, design notes incl. "no migration" rationale and out-of-scope list). | T25 | `docs(tracker): draft PR description` |
| T27 | TODO | Copy the PR body out, then `git rm TRACKER.md CLAUDE.md`. Final PR diff = backend + docs only. | T26 | `chore: remove agent workflow files from PR` |
| T28 | TODO | **[USER]** Open the PR (browser, or `gh pr create` after `gh auth login`): `vishank020:feat/public-blog-endpoints` → `atomityhq:main`. | T27 | – |
| T29 | TODO | **[USER]** Open the PR URL in a private/incognito window (signed out) to confirm it loads; reply to the email with the **PR link** (+ fork branch link) by **Sat 24 Oct**. | T28 | – |
| T30 | TODO | Ongoing until the deadline: respond to review with follow-up commits on the same branch (restore this tracker from history if needed: `git show <commit>:TRACKER.md`). | T28 | per fix |

## Manual verification script (T23)

```bash
TOKEN=$(curl -s localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"admin@example.com","password":"admin"}' | jq -r .data.token)
# create + publish a post: POST /api/v1/posts then POST /api/v1/posts/{id}/publish (Authorization: Bearer $TOKEN)
curl -s  localhost:8080/api/v1/blogs                 # 200, no token, published only, newest first, meta present
curl -s  localhost:8080/api/v1/blogs/<id>            # 200, full content, no status/version
curl -s  localhost:8080/api/v1/blogs/slug/<slug>     # 200
curl -si localhost:8080/api/v1/blogs/<draft-id>      # 404 NOT_FOUND envelope
curl -si localhost:8080/api/v1/posts                 # 401 — admin API still protected
```

## Decisions log

| Date | Decision | Why |
|---|---|---|
| 2026-10-19 | Target issue **#11**, backend only | Challenge is for **Java Backend Intern**; #10 is the frontend challenge |
| 2026-10-19 | Separate `/slug/{slug}` path instead of one `{idOrSlug}` | A UUID-shaped string is also a valid slug; separate paths are unambiguous and use both new finders |
| 2026-10-19 | Dedicated public DTOs, not `PostResponse` | Admin DTO exposes `status`, `version`, `focusKeyword` |
| 2026-10-19 | Exact `permitAll` matchers, no `/**` | Future `/blogs/...` routes must opt in explicitly |
| 2026-10-19 | No Flyway migration | No schema change needed; avoids `V2` clash with PR #18. A partial index on `published_at` is a follow-up |
| 2026-10-19 | Delegate to `PostListQuery.toPageable()` | Reuse sort whitelist / size clamp / tiebreaker instead of duplicating |
| 2026-10-19 | `TRACKER.md` + `CLAUDE.md` committed, removed in T27 | Durable for any agent/machine; clean upstream PR diff |
| 2026-10-19 | T04 done before T03 | Docker was down; baseline result is recorded when T03 runs |
| 2026-10-19 | T19 (ETag revalidation) skipped | Stretch task, default SKIP; revalidation caching is a follow-up, not part of this PR |
| 2026-10-19 | T23 ran compose Postgres on `POSTGRES_PORT=55432` | This machine has a native PostgreSQL 18 service on 5432/5433; env-only workaround, no repo change |

## Out of scope
Issue #10 / Markdown import · frontend changes · tag/author/`q` filters on public endpoints (unauthenticated search is an expensive surface) ·
rate limiting · CORS · OpenAPI · new migration/index · new env vars · the stale "local-disk" wording in `docs/architecture.md`.

## Blockers
- ~~Docker Desktop not running~~ — resolved (T01).
- `gh` not authenticated — only needed to open the PR from the CLI; browser works.
- Local machine: a native PostgreSQL 18 Windows service holds host ports 5432/5433; run the compose Postgres on another port (e.g. `POSTGRES_PORT=55432`) for manual testing (T23).

## Notes (agents append real results here)
- _T01:_ Docker Desktop started; `docker info` returns server `29.4.1`. Issue #11 not claimed (outward-facing, needs user approval); `gh` not authenticated.
- _T03 baseline:_ `cd backend && .\gradlew.bat build --console=plain` → **BUILD SUCCESSFUL in 3m 50s** (230s, includes first-time Gradle 8.11.1 download). 10 test suites, **68 tests, 0 failures, 0 errors, 0 skipped**. Docker running, Testcontainers Postgres used.
- _T05–T18:_ every commit ran the pre-commit hook (`./gradlew build`, compile + all tests, Testcontainers Postgres) and passed; each was pushed. `*BlogApiTest` (10 tests) and `*BlogQueryCountTest` (1 test) ran green on every invocation; T11 also ran the full suite green.
- _T23 full verification (2026-10-19, live stack):_ `cd backend && .\gradlew.bat build` → BUILD SUCCESSFUL (7 tasks up-to-date). Environment deviation: host ports 5432/5433 are taken by a native PostgreSQL 18 Windows service, so Postgres was published on `POSTGRES_PORT=55432` (env only, no repo change). `docker compose up -d postgres` → healthy; backend with `SPRING_PROFILES_ACTIVE=local` + dummy `NEXTCLOUD_*` → Flyway applied `V1` on an empty schema, started in 8.6s, `/health/readiness` 200.
  Real curl results (post published + a separate draft, no token sent on the blog calls):
  - `GET /api/v1/blogs?size=2` → **200**, only the published post; `meta {page:0,size:2,totalElements:1,totalPages:1}`; item has `id,title,slug,excerpt,featured,authors,tags,coverImage,wordCount,readingTimeMinutes,publishedAt,updatedAt` and no `status/version/createdAt/focusKeyword`.
  - `GET /api/v1/blogs/32273ddc-774b-45cc-b86a-901143434199` → **200**, full Tiptap `content`, `seoTitle`/`metaDescription` present, editor-only fields absent.
  - `GET /api/v1/blogs/slug/verify-1792424423` → **200**, same body as by id.
  - `GET /api/v1/blogs/34c0653f-0019-4303-a0fe-0c3069a01972` (draft) → **404** `{"errors":[{"errorCode":"NOT_FOUND","message":"Blog not found","correlationId":"…"}]}`.
  - `GET /api/v1/posts` (no token) → **401** `UNAUTHORIZED`, admin API still protected.
  Stack torn down afterwards (`docker compose down -v`).
- _T24 self-review (2026-10-19):_ reviewed `git diff upstream/main...HEAD` (933 insertions across 15 files); `git diff --check` clean; walked `.github/pull_request_template.md` (checklist mapped in the appendix). Findings: none requiring code changes.
- _T25 sync (2026-10-19):_ `git fetch upstream` → `upstream/main` still `c26cd04` and an ancestor of HEAD; no merge needed, no commit.

## Appendix — PR description draft (filled in T26)

**Title:** `feat: expose public endpoints to read published blogs`

**Body (paste into the PR; `Closes #11`):**

````markdown
## Description

Closes #11.

Adds a public, read-only, paginated API for **published** posts, so an external site can
render the blog without an admin session:

- `GET /api/v1/blogs?sort&direction&page&size` — published posts, **newest first** by
  default; `meta` carries `page/size/totalElements/totalPages`.
- `GET /api/v1/blogs/{id}` and `GET /api/v1/blogs/slug/{slug}` — one published post with
  its full Tiptap body and SEO metadata.

Anything that is not `PUBLISHED` — draft, archived, unpublished, unknown — answers
`404 NOT_FOUND`, so the existence of a draft is never revealed. The new `blog/` package
uses its own response DTOs that omit the editor-only fields (`status`, `version`,
`createdAt`, `focusKeyword`) instead of reusing the admin `PostResponse`. The sort
whitelist, direction check, size clamp (1–100) and `id` tiebreaker are reused from the
existing `PostListQuery#toPageable`.

Design notes:

- **No Flyway migration or index**: no schema change is needed; a partial index on
  `published_at` is a noted follow-up (this also avoids a `V2` version clash with #18).
- Security matchers are the three exact paths (`/api/v1/blogs`, `/api/v1/blogs/*`,
  `/api/v1/blogs/slug/*`) — no `/**` wildcard, so future blog routes must opt in.
- Known behaviour, documented not changed: the bearer filter also runs on public paths,
  so a malformed or expired `Authorization` header still gets `401` on `/blogs`.
- Out of scope: tag/author/`q` filters, rate limiting, CORS, ETag caching, frontend.

## Type of change

- [x] New feature

## Steps to reproduce / how to test

1. `cd backend && ./gradlew build` (Docker running; Testcontainers starts Postgres).
2. Start the stack: `docker compose up -d postgres`, then
   `SPRING_PROFILES_ACTIVE=local ./gradlew bootRun` (dummy `NEXTCLOUD_*` values are fine).
3. Sign in as `admin@example.com` / `admin`; create a post and publish it
   (`POST /api/v1/posts`, then `POST /api/v1/posts/{id}/publish`). Create a second post
   and leave it as a draft.
4. `curl -s localhost:8080/api/v1/blogs` → 200, only the published post, newest first,
   `meta` present.
5. `curl -s localhost:8080/api/v1/blogs/<id>` and
   `curl -s localhost:8080/api/v1/blogs/slug/<slug>` → 200 with `content`, without
   `status/version/createdAt/focusKeyword`.
6. `curl -si localhost:8080/api/v1/blogs/<draft-id>` → 404 `NOT_FOUND`;
   `curl -si localhost:8080/api/v1/posts` → 401 — the admin API is unchanged.

**Expected result:** published content is readable without a token, drafts stay invisible
everywhere, and the admin endpoints remain protected.

## Checklist

**Title**

- [x] The PR title follows `type: short summary` — `feat: expose public endpoints to read published blogs`

**Tests**

- [x] Backend tests pass — `./gradlew build` (the pre-commit hook ran compile + all tests
  with Testcontainers Postgres on every commit)
- [ ] N/A — no frontend changes, so `cd frontend && npm run check` is not needed
- [x] Tests added: `BlogApiTest` (list, ordering/pagination, detail by id and slug, no
  leaks, security boundary), `BlogListQueryTest` (unit), `BlogQueryCountTest` (batched
  fetch guard)
- [x] Tested by hand in the running app (published a post, called all three endpoints
  without a token, checked a draft answers 404)

**Code**

- [x] The git hooks ran on all commits (no `--no-verify`)
- [x] No database change — no new Flyway migration needed
- [ ] N/A — no frontend styling
- [x] No secrets, tokens or personal data

**Docs & config**

- [x] No new environment variables
- [x] `docs/architecture.md` (API table, auth, business rules, layout, examples) and
  `README.md` updated
````

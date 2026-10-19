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
- Nothing committed or pushed yet. `TRACKER.md` + `CLAUDE.md` are written but untracked (T04 = commit + first push). `PLAN.md` stays untracked (local only, never enters the PR diff).
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
| T09 | TODO | `blog/service/BlogService` (`@Service`, `@Transactional(readOnly = true)`, ctor-inject `PostRepository`): `Page<BlogSummaryResponse> list(BlogListQuery)` via `postRepository.findAll(PostSpecifications.hasStatus(PostStatus.PUBLISHED), query.toPageable()).map(BlogSummaryResponse::of)`; `BlogResponse getById(UUID)` and `getBySlug(String)` via the T06 finders, `.orElseThrow(() -> ApiException.notFound("Blog"))`. | T08 | compile | `feat(backend): add blog service for published posts` |
| T10 | TODO | `blog/controller/BlogController` (`@RequestMapping("/api/v1/blogs")`): `list(BlogListQuery)` → `ApiResponse.ok(page.getContent(), PageMeta.of(page))`; `get(@PathVariable UUID id)`; `getBySlug(@PathVariable String slug)` on `/slug/{slug}`. Javadoc line per endpoint, "Public (see SecurityConfig)". | T09 | compile | `feat(backend): expose public blog endpoints` |
| T11 | TODO | `SecurityConfig`: before `.anyRequest().authenticated()` add comment `// Published blog posts are public so a website can read them without signing in.` and `.requestMatchers(HttpMethod.GET, "/api/v1/blogs", "/api/v1/blogs/*", "/api/v1/blogs/slug/*").permitAll()`. | T10 | `./gradlew test` (existing tests green) | `feat(backend): allow unauthenticated reads of published blogs` |

## Wave 3 — Tests (T12–T17) — `backend/src/test/java/io/atomity/blogcms/blog/BlogApiTest.java`

Public calls: `mockMvc.perform(get(...))` (no token). Fixtures: create/publish through the admin API with `call(...)`
(`POST /api/v1/posts`, `POST /api/v1/posts/{id}/publish|unpublish|archive`). Publishing sequentially yields strictly increasing `publishedAt`.

| ID | Status | Task | Depends | Commit |
|---|---|---|---|---|
| T12 | TODO | Scaffold + helpers. Test: list works **without a token** and returns only `PUBLISHED`; draft / archived / unpublished excluded; envelope has `data` array + `meta` (`page,size,totalElements,totalPages`). | T11 | `test(backend): cover public blog list` |
| T13 | TODO | Ordering & pagination: newest-first by default; `sort=title&direction=asc`; `page`/`size` → correct `totalElements`/`totalPages` and slices; page beyond range → 200 + empty `data`; `size=1000` clamped to 100; `sort=password` and `direction=sideways` → 400 `BAD_REQUEST`. | T12 | `test(backend): cover blog list sorting and pagination` |
| T14 | TODO | Detail by id: 200 with full Tiptap `content`, authors, tags, cover; 404 `NOT_FOUND` for draft, archived, unpublished-after-publish, unknown id; malformed id → 400. | T13 | `test(backend): cover blog detail by id` |
| T15 | TODO | Detail by slug: 200; 404 unknown/draft; editor changes slug → old 404, new 200; unpublish → 404, republish → 200. | T14 | `test(backend): cover blog detail by slug` |
| T16 | TODO | No leaks: `jsonPath(...).doesNotExist()` for `status`, `version`, `createdAt`, `focusKeyword` on list items **and** detail; draft title/body never appears in any list response. | T15 | `test(backend): ensure public blogs expose no internal fields` |
| T17 | TODO | Security boundary: `GET /api/v1/posts` and `/api/v1/posts/{id}` still 401 without token; `POST`/`PUT`/`DELETE` on `/api/v1/blogs…` → 401; valid Bearer also works on `/blogs`; invalid Bearer on a public path → 401 (documents known behaviour). After T17 the user may open a **Draft** PR for early visibility. | T16 | `test(backend): lock down security boundary of public blog API` |

## Wave 4 — Hardening & docs (T18–T22)

| ID | Status | Task | Depends | Commit |
|---|---|---|---|---|
| T18 | OPTIONAL | *(recommended, time-box 1h)* N+1 guard: with Hibernate statistics enabled for the test (`@TestPropertySource` `spring.jpa.properties.hibernate.generate_statistics=true`), listing 10 posts that have authors + tags stays within a small fixed statement budget. If over budget, fix via batch-friendly mapping — **do not** add `@EntityGraph` (collection fetch + `Pageable` = in-memory paging). | T17 | `test(backend): guard blog list against N+1 queries` |
| T19 | OPTIONAL | *(stretch, default SKIP)* `ShallowEtagHeaderFilter` scoped to `/api/v1/blogs/*` (no `max-age` ⇒ unpublish is never served stale; frontend proxy already forwards `etag`/`if-none-match`) + 304 test. | T17 | `feat(backend): add ETag revalidation to public blog endpoints` |
| T20 | TODO | `docs/architecture.md`: API table rows for the 3 endpoints; Authentication bullet 3 lists them as public; backend layout tree gains `blog/`; business rule "only published posts are public, everything else 404, public DTOs omit editor-only fields". | T17 | `docs: document public blog endpoints in architecture` |
| T21 | TODO | `docs/architecture.md`: short "Public blog API" section — `curl` examples + sample JSON for list / by id / by slug and a 404 example. | T20 | `docs: add public blog API examples` |
| T22 | TODO | `README.md`: controllers line → `/api/v1/{posts,authors,tags,media,blogs,auth}`; one sentence mentioning the public read API. | T21 | `docs: mention public blog API in README` |

## Wave 5 — Verify, polish, submit (T23–T30)

| ID | Status | Task | Depends | Commit |
|---|---|---|---|---|
| T23 | TODO | Full verification: `./gradlew build` green; run stack (`docker compose up -d postgres` + backend `local` profile with dummy `NEXTCLOUD_*`); publish a post via the admin API; run the curl script below; paste real outputs under Notes. | T22 | `docs(tracker): record verification results` |
| T24 | TODO | Self-review `git diff upstream/main...HEAD` (use `/code-review`); walk `.github/pull_request_template.md` checklist; fix findings, one commit each. | T23 | `fix(backend): …` per finding |
| T25 | TODO | `git fetch upstream`; if `main` moved, **merge** `upstream/main` into the branch (no force-push), rerun `./gradlew build`. | T24 | `chore: merge upstream main` (only if needed) |
| T26 | TODO | Draft PR title + body in the appendix below (template sections, `Closes #11`, how to test, honest checklist, design notes incl. "no migration" rationale and out-of-scope list). | T25 | `docs(tracker): draft PR description` |
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

## Out of scope
Issue #10 / Markdown import · frontend changes · tag/author/`q` filters on public endpoints (unauthenticated search is an expensive surface) ·
rate limiting · CORS · OpenAPI · new migration/index · new env vars · the stale "local-disk" wording in `docs/architecture.md`.

## Blockers
- Docker Desktop not running (needed for `./gradlew build`, Testcontainers and the pre-commit hook on backend files) — **user action (T01)**.
- `gh` not authenticated — only needed to open the PR from the CLI; browser works.

## Notes (agents append real results here)
- _T01:_ Docker Desktop started; `docker info` returns server `29.4.1`. Issue #11 not claimed (outward-facing, needs user approval); `gh` not authenticated.
- _T03 baseline:_ `cd backend && .\gradlew.bat build --console=plain` → **BUILD SUCCESSFUL in 3m 50s** (230s, includes first-time Gradle 8.11.1 download). 10 test suites, **68 tests, 0 failures, 0 errors, 0 skipped**. Docker running, Testcontainers Postgres used.

## Appendix — PR description draft (filled in T26)
_empty_

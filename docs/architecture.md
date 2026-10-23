# Architecture

```
Browser ──► frontend (Next.js) ──► backend (Spring Boot) ──► PostgreSQL
            route handlers proxy     /api/v1/*, /health/*
```

## Principles

- **The browser never talks to the backend directly.** Server components and
  route handlers in `frontend/` call the backend using `BACKEND_API_URL`
  (server-only). The backend therefore needs no CORS config and can stay on
  a private network.
- **One response envelope.** Every backend JSON response is
  `{"data": ..., "meta": ..., "errors": [...]}` (`shared/ApiResponse`).
  Errors carry an `errorCode`, a human `message` and the request's
  `correlationId`.
- **Correlation IDs.** Each request gets an `X-Correlation-ID` (the caller's,
  if well-formed, otherwise generated). It is echoed in the response header,
  included in error bodies and printed on every log line.
- **Schema via migrations only.** Flyway migrations in
  `backend/src/main/resources/db/migration/V{n}__description.sql`;
  Hibernate only validates (`ddl-auto: validate`). Never edit a migration that
  has been merged — add a new one.
- **Tests run against real Postgres** (Testcontainers), not H2, because the
  schema uses Postgres-specific types.

## Authentication

There is one admin account, configured through environment variables
(`ADMIN_EMAIL`, `ADMIN_PASSWORD_HASH` — bcrypt). There are no users or roles.

1. `POST /api/v1/auth/login` checks the credentials and returns an HS256 JWT
   (`JWT_SECRET`, valid for `JWT_TTL`). Failed attempts are rate-limited per client
   (5 per 15 minutes).
2. The frontend keeps the token in an httpOnly cookie and forwards it as
   `Authorization: Bearer` on every API call.
3. Everything under `/api/v1` needs the token, except login,
   `GET /api/v1/media/{id}/file` (images are public so published pages can embed them)
   and the public blog reads (`GET /api/v1/blogs`, `/blogs/{id}`, `/blogs/slug/{slug}`).

## API

All under `/api/v1`, JSON in camelCase, wrapped in the response envelope.

| Endpoint | Purpose |
|----------|---------|
| `POST /auth/login` | Exchange admin credentials for a token |
| `GET /posts?status&q&tag&author&sort&direction&page&size` | Paginated list (`meta` holds page info) |
| `GET /posts/counts` | Posts per status, for the list tabs |
| `GET /blogs?sort&direction&page&size` | **Public.** Published posts, newest first (`meta` holds page info) |
| `GET /blogs/{id}` · `GET /blogs/slug/{slug}` | **Public.** One published post with its full content |
| `GET/PUT/DELETE /posts/{id}`, `POST /posts` | Read, update (send the loaded `version`), delete, create |
| `POST /posts/{id}/publish` · `/unpublish` · `/archive` · `/duplicate` | Lifecycle actions |
| `GET/POST /authors`, `PUT/DELETE /authors/{id}` | Authors |
| `GET/POST /tags`, `PUT/DELETE /tags/{id}` | Tags |
| `GET/POST /media` (multipart `file`), `PATCH/DELETE /media/{id}`, `GET /media/{id}/file` | Image library |
| `POST /markdown-sources` (multipart `file`), `GET /markdown-sources/{id}/file` | Original `.md` files imported into posts |

### Business rules

- New posts are drafts. Publishing requires a title and some content (words or an
  image). `publishedAt` is set on first publication and kept across unpublish/republish.
- Saves carry the `version` the editor loaded; a stale version gets `409 VERSION_CONFLICT`
  instead of overwriting someone else's changes.
- Slugs are unique per resource and derived from the title/name when left empty.
- The post body is Tiptap JSON. On save the backend derives its plain text (for search),
  word count, reading time, and the library images it embeds; links must be
  `http(s)`, `mailto:`, `/` or `#`, and images `http(s)` or `/`.
- Uploads are identified from their bytes (JPEG, PNG, WebP, GIF; ≤ 10 MB), never from the
  client's file name or Content-Type. An image used as a cover, inline, or as an avatar
  can't be deleted.
- An imported `.md` file (`.md`/`.markdown`, UTF-8 text, ≤ 1 MB) is stored as is and linked to
  its post through `markdownSourceId`, so the editor can show it again after a reload. Replacing
  or removing it, or deleting the post, deletes the file once no post uses it (a duplicate shares
  it); the post content stays.
- Deleting an author or tag removes it from posts; the posts stay.
- The public blog (the `GET /blogs…` reads) only ever returns `PUBLISHED` posts; anything
  else — draft, archived, unpublished, unknown — answers `404 NOT_FOUND`, so the existence
  of a draft is never revealed. Public responses omit the editor-only fields `status`,
  `version`, `createdAt` and `focusKeyword`; the detail response adds the body and SEO
  metadata, the list stays body-free with `publishedAt` default-descending.

### Public blog API

No `Authorization` header is needed for the three `GET /blogs…` endpoints. An invalid or
expired header is still rejected with `401` — the bearer filter runs on public paths too.

```bash
curl -s 'http://localhost:8080/api/v1/blogs?sort=publishedAt&direction=desc&size=2'
curl -s  http://localhost:8080/api/v1/blogs/8b1d0d0e-2f4a-4c1e-9d6b-3f0a1c2d4e5f
curl -s  http://localhost:8080/api/v1/blogs/slug/rightsizing-kubernetes-pods
curl -si http://localhost:8080/api/v1/blogs/<draft-id>            # 404: drafts stay invisible
```

```json
{
  "data": [
    {
      "id": "8b1d0d0e-2f4a-4c1e-9d6b-3f0a1c2d4e5f",
      "title": "Rightsizing Kubernetes pods",
      "slug": "rightsizing-kubernetes-pods",
      "excerpt": "Requests vs limits, in one table.",
      "featured": false,
      "authors": [{ "id": "2c9f…", "name": "Lena", "avatar": null }],
      "tags": [{ "id": "77aa…", "name": "Kubernetes", "slug": "kubernetes" }],
      "coverImage": null,
      "wordCount": 612,
      "readingTimeMinutes": 3,
      "publishedAt": "2026-10-18T09:12:44.123456Z",
      "updatedAt": "2026-10-18T09:12:44.123456Z"
    }
  ],
  "meta": { "page": 0, "size": 2, "totalElements": 1, "totalPages": 1 }
}
```

`GET /blogs/{id}` and `GET /blogs/slug/{slug}` return one object instead of a list, with
the body and SEO fields added:

```json
{
  "data": {
    "id": "8b1d0d0e-2f4a-4c1e-9d6b-3f0a1c2d4e5f",
    "title": "Rightsizing Kubernetes pods",
    "slug": "rightsizing-kubernetes-pods",
    "excerpt": "Requests vs limits, in one table.",
    "featured": false,
    "authors": [{ "id": "2c9f…", "name": "Lena", "avatar": null }],
    "tags": [{ "id": "77aa…", "name": "Kubernetes", "slug": "kubernetes" }],
    "coverImage": { "id": "9f31…", "url": "/api/v1/media/9f31…/file", "altText": "Pods and node capacity" },
    "wordCount": 612,
    "readingTimeMinutes": 3,
    "publishedAt": "2026-10-18T09:12:44.123456Z",
    "updatedAt": "2026-10-18T09:12:44.123456Z",
    "content": { "type": "doc", "content": [{ "type": "paragraph", "content": [{ "type": "text", "text": "Requests vs limits…" }] }] },
    "coverImageAlt": "Pods and node capacity",
    "seoTitle": "Rightsizing Kubernetes pods",
    "metaDescription": "How to set requests and limits without guessing.",
    "canonicalUrl": "https://example.com/blog/rightsizing-kubernetes-pods"
  }
}
```

A post that is not published — draft, archived, unpublished or unknown — answers `404`
with the standard error envelope (a malformed UUID on the id route answers
`400 BAD_REQUEST` instead):

```json
{
  "errors": [
    {
      "errorCode": "NOT_FOUND",
      "message": "Blog not found",
      "correlationId": "3f9c0f9a2b7e4a5f"
    }
  ]
}
```

## Backend layout

Code is grouped by feature, then by layer:

```
io.atomity.blogcms
├── BlogCmsApplication
├── shared/            ApiResponse, ApiError, ApiException, GlobalExceptionHandler,
│                      CorrelationFilter, PageMeta, Slugs, Timestamps
├── auth/              config/ (SecurityConfig, AuthProperties) controller/ dto/ service/
├── post/              controller/ dto/ entity/ repository/ service/ (ContentAnalyzer)
├── blog/              controller/ dto/ service/ (public, read-only view of published posts)
├── author/ tag/       controller/ dto/ entity/ repository/ service/
├── media/             … plus storage/ (StorageService, local-disk implementation)
└── markdown/          controller/ dto/ entity/ repository/ service/ (files stored via StorageService)
```

Conventions: constructor injection, Java `record`s for DTOs, UUID primary
keys, timestamps as UTC `Instant` / `TIMESTAMPTZ`, REST paths under
`/api/v1/<plural-resource>`.

## Frontend layout

```
src/
├── app/          Routes (pages render a module canvas) and route handlers: api/auth/*, api/v1/[...path] proxy
├── modules/      One folder per feature (posts, authors, tags, media, auth):
│                 *Canvas.tsx screens + api.ts, the feature's only data access
├── components/   Shared UI: ui/ (primitives) and shell/ (sidebar, top bar, navigation guard)
├── hooks/        useAsync, useDebouncedValue, useUnsavedChangesWarning
├── lib/          api-client (browser → /api/v1 proxy), backend + session (server), slug, content, format
├── styles/       tokens.css (design tokens), base, component classes, editor typography
├── types/        Domain types mirroring the backend's JSON (Post, Author, Tag, Media, Page)
└── proxy.ts      Route gate: no session cookie → /login
```

### Data access

Screens never fetch directly; they call functions in `modules/<feature>/api.ts`, one per
backend endpoint. Those go through `lib/api-client.ts` to this app's own `/api/v1/*`
route (`app/api/v1/[...path]/route.ts`), which:

- forwards the request to `${BACKEND_API_URL}/api/v1/*`, streaming bodies both ways
  (image uploads and downloads included);
- turns the httpOnly `blogcms_session` cookie into `Authorization: Bearer <token>`;
- clears the cookie when the backend answers 401, and the client then sends the browser
  to `/login?next=…`.

`/api/auth/login` exchanges the admin credentials for the backend's JWT and stores it in
the cookie (expiring with the token); `/api/auth/logout` deletes it. `proxy.ts` only
checks that the cookie exists — the backend validates the token on every call.

Post bodies are Tiptap (ProseMirror) **JSON**, never HTML. Images inside a body
carry `attrs.mediaId`, so the backend can tell which library images a post uses.

## Health

Served by Spring Boot Actuator:

- `GET /health/liveness` — the process is running.
- `GET /health/readiness` — the process is running **and** the database answers.
- `GET /metrics` — Prometheus metrics.

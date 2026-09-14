# `src/` — the URL shortener (test subject)

**This directory is currently empty by design.** The greenfield run is what populates
it — that is the demonstration. See `scenarios/greenfield.md`.

The shortener is the **test subject**, not the deliverable. It must be genuinely good
code (the assignment grades output realism), but its purpose is to give the
orchestrator real software to build, break, fix and extend.

---

## Stack (DEC-0002, superseded by DEC-0012 for language)

| Concern | Choice | Why |
|---|---|---|
| Language | Java 21 LTS | Same language as the orchestrator — one stack for a reviewer (DEC-0012) |
| Framework | Spring Boot 3.3+ (`spring-boot-starter-web`) | Mature, well-understood web stack; virtual threads enabled via `spring.threads.virtual.enabled` |
| API docs | springdoc-openapi-starter-webmvc-ui | **Generated OpenAPI** from the controller annotations satisfies the API/schema requirement without a hand-written spec that can drift |
| Storage | SQLite via `org.xerial:sqlite-jdbc`, **plain JDBC, no JPA/Hibernate** | Zero setup; single file makes the brownfield migration concrete and inspectable — an ORM's auto-DDL would hide exactly the schema change the human gate exists to review |
| Cache | In-process TTL + LRU (hand-rolled, `ConcurrentHashMap`-backed) | No external dependency; enough to demonstrate the hot-path pattern |
| Validation | `spring-boot-starter-validation` (Jakarta Bean Validation) | Request/response schema validation |
| Tests | JUnit 5 + AssertJ + Mockito + `spring-boot-starter-test` | Standard |

Rejected: Node/Express + Postgres (second language, server setup), Go (third
language relative to the rest of the toolchain), Kotlin (less universally
reviewable — DEC-0012), Django-style heavyweight framework equivalents (would
obscure the schema-change gate we specifically want visible).

---

## What Claude Code should scaffold first

Build in this order — it is dependency-ordered, and each layer is testable before the
next depends on it. Each module exists because it has its own failure mode and its
own test. Package root: `com.sdlc.shortener`.

### 1. `model/Link.java`
Plain record/class: `code`, `targetUrl`, `createdAt`, `expiresAt`, `status`,
`clickCount`. No behaviour — shapes only.

### 2. `store/LinkStore.java` — **behind an interface**
```java
public interface LinkStore {
    Link create(Link link);
    Optional<Link> get(String code);
    void disable(String code);
}
```
with `SqliteLinkStore` as the implementation, using try-with-resources on every
`Connection`/`PreparedStatement` and parameterized queries only. The interface is
the seam that makes "swap to Postgres" a swap rather than a rewrite — and
SQLite's single-writer limit is a *stated, accepted* trade-off (DEC-0002), so the
seam is where it gets addressed.

Schema (`resources/db/migration/V1__init.sql`):
```sql
CREATE TABLE links (
  code        TEXT PRIMARY KEY,
  target_url  TEXT NOT NULL,
  created_at  TEXT NOT NULL,
  expires_at  TEXT,
  status      TEXT NOT NULL DEFAULT 'active',
  click_count INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_links_status ON links(status);
```

### 3. `service/CodecService.java` — short-code generation *(AC5)*
Random base62 from **`java.security.SecureRandom`, never `java.util.Random`**
(DEC-0010), 7 chars, bounded collision retry (max 5, then throw) — consistent
with the orchestrator's own bounded-retry principle.

The counter approach was rejected because sequential codes are enumerable: anyone can
walk the space and harvest every "unlisted" link. Unpredictability is a security
property that cannot be retrofitted.

### 4. `service/ValidationService.java` — URL safety *(AC4)*
**The most security-sensitive class in the service.** A shortener is an open
redirector by design — it launders a malicious destination behind a trusted domain,
which is why shorteners are a standing phishing vector.

Per OWASP SSRF guidance (research-notes §6.6):

- **Scheme allowlist** — `http`/`https` only. Reject `file:`, `javascript:`,
  `data:`, `gopher:`.
- **Block internal ranges** — loopback `127.0.0.0/8`, `::1/128`, `0.0.0.0/8`; RFC1918
  `10/8`, `172.16/12`, `192.168/16`; link-local, and specifically the cloud metadata
  endpoint **`169.254.169.254`**. Use `InetAddress.isLoopbackAddress()`,
  `isSiteLocalAddress()`, `isLinkLocalAddress()`, plus an explicit metadata-IP check.
- **Resolve DNS via `InetAddress.getAllByName()` and validate every resolved IP** —
  not just the hostname string. A public hostname resolving to `127.0.0.1`
  otherwise walks straight through.
- **Do not follow redirects** during validation — a permitted URL can 302 into a
  blocked one.
- **Reserved-word list** for custom aliases (`api`, `admin`, `login`, `health`,
  `static`) — otherwise a custom alias shadows a real route.
- **Self-shortening prevention.**

Residual risk (DNS rebinding, reputation drift) is **stated, not claimed solved**.

### 5. `service/CacheService.java`
TTL + LRU over `code → targetUrl`, cache-aside. Must invalidate on disable/expire —
a stale cache entry would keep redirecting a link that was taken down.

**Cache the URL only, never the click count.** Caching an aggregate would silently
serve wrong numbers.

### 6. `web/LinkController.java`, `web/RedirectController.java`, `web/HealthController.java` *(AC1–AC3)*

| Endpoint | Behaviour |
|---|---|
| `POST /api/v1/links` | Create. Honours `Idempotency-Key`. 201 + code. |
| `GET /{code}` | **302** + `Location`, `Cache-Control: no-store` (DEC-0009) |
| `GET /api/v1/links/{code}` | Metadata |
| `DELETE /api/v1/links/{code}` | Soft delete — set status, never hard-delete |
| `GET /health` | Liveness/readiness |

**302, not 301.** A 301 is browser-cached, so subsequent clicks never reach the
origin — which silently destroys click analytics *and* makes a disabled link keep
redirecting. Expired/disabled links return **410 Gone**, not 404: the resource
existed and was deliberately retired.

### 7. `config/AppConfig.java`, `config/RateLimitFilter.java`
Bean wiring, startup schema creation, and a servlet `Filter` implementing a token
bucket per API key/IP — stricter on create than on redirect; `429` + `Retry-After`.

### 8. `ShortenerApplication.java`
Spring Boot entry point.

---

## Added by the brownfield scenario

Not built in greenfield — `scenarios/brownfield.md` adds them, and that addition is
what fires the schema-change gate:

- `ClickEvent` model; `click_events` table + index; `links.click_count` column.
- `GET /api/v1/links/{code}/stats`.
- **Fire-and-forget click recording on the redirect path** — dispatched on a
  virtual thread after the response is prepared, never awaited on the request
  thread. This is what makes "no latency regression" and "analytics failure
  cannot fail a redirect" achievable rather than aspirational.
- **No raw IP is persisted.** Country is derived at write time and the IP truncated
  (GDPR — research-notes §6.3).

---

## Explicitly out of scope (DEC-0008)

Custom domains, user accounts, a web UI, QR codes, geographic sharding, real-time
dashboards. Named so the boundary is a decision rather than an omission.

---

## Running (once implemented)

```bash
mvn spring-boot:run
# OpenAPI docs: http://localhost:8080/swagger-ui.html
```

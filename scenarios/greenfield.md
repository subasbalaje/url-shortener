# Scenario 1 — Greenfield: build the URL shortener core

**Scenario type:** new system from nothing.
**Per DEC-0001,** this is the "from scratch" half of the assignment. It produces the
codebase that `brownfield.md` and `ambiguous.md` then operate on — so this scenario
must run first for the other two to be meaningful.

**The request** (well-specified, deliberately):

> *"Build a URL shortener service with an API to create short links and a redirect
> endpoint. Include storage, tests, and documentation."*

**What this scenario demonstrates:** the full graph traversal with **every node
executing at least once**, the parallel wave, and a gate that **correctly does not
fire** because no high-impact action is present. It is the baseline against which
the other two scenarios' differences are visible.

---

## 1. Requirement understanding

The request is well-specified in intent but silent on several details. The
Requirements Agent normalises and **names what it assumed** rather than deciding
quietly.

**Normalised problem.** An HTTP service mapping short codes to target URLs, with a
creation API, a redirect endpoint, and persistent storage surviving restart.

**Acceptance criteria** (each with a stated verification method — the exit gate
rejects any criterion that lacks one):

| id | Criterion | Verifiable by |
|---|---|---|
| AC1 | `POST /api/v1/links` returns a 7-char code and HTTP 201 | integration test |
| AC2 | `GET /{code}` returns 302 with a `Location` header | integration test |
| AC3 | Unknown code returns 404 | integration test |
| AC4 | Target URL validated: scheme allowlist + internal-range denylist | unit test |
| AC5 | Codes are unpredictable (CSPRNG), not sequential | unit test (statistical) |
| AC6 | Link mapping survives process restart | integration test |

**Ambiguities — named, resolved, and recorded:**

| id | Ambiguity | Resolution | Why |
|---|---|---|---|
| AMB1 | "Short links" — length unspecified | 7 chars, base62 | 62⁷ ≈ 3.5×10¹² is ample at any plausible volume (research-notes §6.1) |
| AMB2 | Custom aliases not mentioned | Optional field, not in core AC | Cheap now; avoids a schema change later (DEC-0008) |

**Assumptions:** single-tenant, no user accounts (DEC-0008 scopes auth out);
analytics deferred to the brownfield scenario (DEC-0001).

These are minor ambiguities resolved with stated assumptions — legitimate under the
exit gate, which requires only that they be *named*. Contrast `ambiguous.md`, where
the ambiguity is severe enough that resolution-by-assumption is **not** acceptable
and the gate escalates instead.

---

## 2. Task decomposition

The request decomposes onto the graph as follows. Dependencies are real
data-dependencies, not narrative ordering:

| Node | Produces | Depends on | Why that dependency |
|---|---|---|---|
| `requirements` | `requirement_spec`, `ambiguity_report` | — | root |
| `design` | `design_doc`, `api_schema`, `impact_analysis`, `migration_plan` | `requirements` | cannot design an unnormalised requirement |
| `apply_migration` | `migration_applied` | `design` | **skipped here** (§4) |
| `implementation` | `source_code` | `design`, `apply_migration` | needs the contract *and* a settled schema state |
| `testing` | `test_suite`, `test_results` | `implementation` | needs code to test |
| `documentation` | `documentation` | `implementation` | needs code to document |
| `release_readiness` | `readiness_report` | `testing`, `documentation` | join: needs both |

**Sub-task decomposition inside `implementation`** (what the agent actually builds):

```
src/
  models.py        Link record: code, target_url, created_at, expires_at, status
  storage.py       SQLite persistence behind an interface (swap-to-Postgres seam)
  codec.py         base62 CSPRNG generation + bounded collision retry  [AC5]
  validation.py    scheme allowlist, internal-range denylist, DNS resolve [AC4]
  cache.py         TTL+LRU for the redirect hot path
  api.py           FastAPI routes; OpenAPI schema generated from them  [AC1-AC3]
  main.py          app wiring
```

The decomposition is driven by the acceptance criteria: AC4 and AC5 each get their
own module precisely because each has its own unit test and its own failure mode.

---

## 3. Orchestration

Execution waves, **derived from the edge set** (verified against the graph, not
asserted):

```
wave 0: [requirements]
wave 1: [design]
wave 2: [apply_migration]              -> SKIPPED
wave 3: [implementation]
wave 4: [documentation, testing]       <-- PARALLEL
wave 5: [release_readiness]            <-- JOIN BARRIER (all_success)
```

**Sequential section (waves 0–3).** Genuinely sequential: each stage consumes the
previous stage's artifact. No parallelism is possible here and none is claimed.

**Parallel section (wave 4).** `testing` and `documentation` both depend only on
`implementation` and on nothing from each other, so Kahn's frontier contains both.
They run under `asyncio.gather(return_exceptions=True)`. Wall-clock for the wave is
`max(42.1s, 28.7s) = 42.1s`, not the 70.8s a linear chain would cost.

`return_exceptions=True` matters: a testing failure must not cancel documentation
mid-write and discard completed work.

**Join barrier (wave 5).** `release_readiness` fires only when **both** branches are
`COMPLETED`. If either failed terminally it inherits `UPSTREAM_FAILED` and does not
run — a readiness verdict computed from partial inputs is worse than no verdict.

**Full trace:** `docs/example-run.md`.

---

## 4. Gates: what fired, and what correctly did not

| Node | Gate | Fired? | Why |
|---|---|---|---|
| `apply_migration` | human approval | **No — node skipped** | `requires_schema_change == false`. Greenfield *creates* the initial schema as first-time setup; it does not migrate an existing one. No DEC-0006 criterion met. |
| `release_readiness` | human approval | **Yes** | DEC-0006 criterion 4. Only a human may declare software fit to ship. |

**One human gate in this scenario.** This is the point of risk-tiering: gating
everything produces approval fatigue and rubber-stamping. Compare `brownfield.md`,
where the same graph produces **two** gates because a real schema migration is
present. The gate count varies with actual risk, from the same graph definition —
which is how you can tell the policy is doing work rather than decorating.

**Exit gates are where most of the work happens.** In the worked run the
`implementation` exit gate caught an API contract mismatch — code that compiled
cleanly but violated its own declared schema. `code_imports_cleanly` passed;
`api_contract_conformance` did not. That is the critic layer earning its place.

---

## 5. Validation approach

**Per-node validation** is the exit gate — declared in YAML, machine-checkable,
identical every run. The full condition sets are in `docs/orchestration-graph.yaml`;
the ones that do the most work here:

- `requirements`: every acceptance criterion carries a `verifiable_by`; ambiguities
  are named rather than silently resolved.
- `implementation`: code imports; **API conformance against the declared schema**;
  no secrets in source; no unapproved high-impact action executed.
- `testing`: tests actually executed; zero failures; **every acceptance criterion is
  covered by a test**; coverage ≥ 70%; **no test weakening**.
- `documentation`: documented endpoints exist in code; no contradictions with the
  schema.
- `release_readiness`: recommendation present and **consistent with the evidence** —
  no "go" alongside failing tests.

**System-level validation for this scenario:**

| Risk | Validation |
|---|---|
| Codes are guessable → link harvesting | AC5 statistical unit test over a sample of generated codes; `codec.py` uses `secrets`, never `random` |
| Open-redirect abuse (the standing shortener risk) | AC4 unit tests for scheme allowlist, loopback, RFC1918, link-local, `169.254.169.254`; validation resolves DNS and checks the **resolved IPs**, not just the hostname |
| Data loss on restart | AC6 integration test: create, restart, redirect |
| Redirect returns stale target after disable | 302 + `Cache-Control: no-store` (DEC-0009); cache invalidation test |
| Silent contract drift | `api_contract_conformance` exit gate, plus generated OpenAPI |

**Residual risks carried into the readiness report** (listed, not hidden): SQLite
single-writer concurrency; no rate limiting yet (DEC-0008 defers it); DNS-rebinding
window in URL validation (research-notes §6.6).

---

## 6. What this scenario proves

1. The graph executes a full SDLC end to end from a single request.
2. Parallelism is **derived from dependencies**, not declared — and verified.
3. A human gate fires **only** where policy says risk exists; the migration gate
   correctly stays silent.
4. Exit gates catch real defects (the contract mismatch) that compilation does not.
5. The run produces a codebase that `brownfield.md` can then genuinely reason about
   — the impact analysis there is performed against code that actually exists.

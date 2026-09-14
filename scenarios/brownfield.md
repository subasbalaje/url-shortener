# Scenario 2 — Brownfield: add click analytics to the existing service

**Scenario type:** enhancement to an existing codebase.
**Per DEC-0001,** this is the "complete and improve it" half. It operates on the real
code produced by `greenfield.md` — so the impact analysis below is performed against
a codebase that actually exists, not a hypothetical one.

**The request** (candidate #1 from research-notes §7, selected against criteria):

> *"Add click analytics — we want to see where clicks are coming from."*

**Why this request was chosen.** It is the only candidate that simultaneously hits a
**database migration** (firing the schema-change gate), **modifies the hot redirect
path** (forcing genuine impact analysis), and **requires back-compat reasoning** for
links created before the feature existed. Candidates #2, #3, #5 and #6 are recorded
in research-notes §7 as a backlog.

**What makes this scenario structurally different from greenfield** — not just a
different story:

| | Greenfield | Brownfield |
|---|---|---|
| `apply_migration` | SKIPPED | **RUNS, and pauses for a human** |
| Human gates | 1 | **2** |
| `impact_analysis` | empty, gate condition skipped | **mandatory, gate enforces it** |
| Starting state | empty `src/` | existing modules with existing behaviour |
| Primary risk | building the wrong thing | **breaking what already works** |

---

## 1. Requirement understanding

The request is *moderately* specified: the goal is clear, the scope is not.

**Normalised problem.** Capture per-click event data on the redirect path, persist it,
and expose an aggregate query API — **without degrading redirect latency or breaking
existing links**.

**Ambiguities named and resolved:**

| id | Ambiguity | Resolution | Why |
|---|---|---|---|
| AMB1 | "Where clicks come from" — referrer, geography, or both? | Both: referrer header + country derived from IP | Both are standard; "where from" most commonly means both (research-notes §6.3) |
| AMB2 | Raw events or aggregate counters? | Both: raw events with retention, plus a maintained `click_count` | Aggregates answer "how many" in O(1); raw events answer time-series and breakdown questions |
| AMB3 | Retention period unspecified | 90 days, configurable | Unbounded event growth is a real operational hazard; a default must exist |

**Assumption A1 (privacy — stated, not assumed silently).** Raw IP addresses are
**not** stored. Country is derived at write time and the IP is truncated. Rationale:
IPs are personal data under GDPR (research-notes §6.3); storing them to satisfy a
vaguely-worded analytics request would be a privacy decision made by default rather
than deliberately.

**Acceptance criteria:**

| id | Criterion | Verifiable by |
|---|---|---|
| AC1 | Each redirect records an event: timestamp, referrer, country, code | integration test |
| AC2 | `GET /api/v1/links/{code}/stats` returns totals, per-day series, top referrers | integration test |
| AC3 | **Redirect p95 latency does not regress by more than 5ms** | performance test |
| AC4 | Analytics write failure **does not fail the redirect** | fault-injection test |
| AC5 | Links created before the migration still redirect and report zero clicks | integration test |
| AC6 | No raw IP is persisted anywhere | unit test + schema inspection |

AC3 and AC4 exist because this is brownfield: the dominant risk is not "does the new
feature work" but **"did we break the thing that already worked."**

---

## 2. Codebase reasoning — impact analysis

This is Requirement 3, and it is the section that has no greenfield equivalent. The
Design Agent's exit gate **enforces** that this analysis exists in brownfield mode
(`conditional_required` on `impact_analysis.impacted_modules`).

**Impacted modules, traced through the actual greenfield codebase:**

| Module | Impact | Nature of change | Risk |
|---|---|---|---|
| `src/models.py` | **Modified** | New `ClickEvent` model; `Link` gains `click_count` | Low — additive |
| `src/storage.py` | **Modified** | `record_click()`, `get_stats()`; migration applied | **High — schema change** |
| `src/api.py` | **Modified** | New `/stats` route; redirect handler now emits an event | **High — hot path** |
| `src/cache.py` | **Reviewed** | Caches `code → url`; click counts must **not** be cached stale | Medium |
| `src/codec.py` | **Untouched** | No relationship to analytics | None |
| `src/validation.py` | **Untouched** | Creation-path only | None |
| `tests/` | **Extended** | New suites; existing redirect tests must still pass | Medium |

**Data flow — before and after:**

```
BEFORE:  GET /{code} -> cache lookup -> [miss] -> storage.get_link()
                     -> 302 Location

AFTER:   GET /{code} -> cache lookup -> [miss] -> storage.get_link()
                     -> 302 Location                      <-- returned FIRST
                     └─▶ fire-and-forget: storage.record_click(...)
                                                          <-- off the critical path
```

**The load-bearing design decision.** The click write is dispatched **after** the
response is prepared and is never awaited by the redirect handler. This is what makes
AC3 (no latency regression) and AC4 (analytics failure cannot fail a redirect)
achievable rather than aspirational. A synchronous write would put a second database
operation on the hottest path in the system and couple a redirect's success to an
analytics table's health.

**API surface change:** one added endpoint, `GET /api/v1/links/{code}/stats`. No
existing endpoint changes signature — deliberately, so no existing client breaks.

**Back-compat (AC5).** Pre-existing links have no click events. `click_count` is added
with `DEFAULT 0`, so old rows are valid immediately and report zero rather than
erroring or being excluded.

---

## 3. Task decomposition

**The graph is the same; the traversal is not.** The key difference is that
`apply_migration` becomes live.

| Node | Status vs. greenfield | Note |
|---|---|---|
| `requirements` | runs | narrower scope; prior `requirement_spec` is context |
| `design` | runs | **`impact_analysis` now mandatory**; produces `migration_plan` |
| `apply_migration` | **RUNS (was SKIPPED)** | `requires_schema_change == true` → **human gate** |
| `implementation` | runs | modifies 3 existing modules; does not rewrite untouched ones |
| `testing` | runs | new suites **plus** the full existing suite as regression |
| `documentation` | runs | documents the new endpoint; updates the redirect description |
| `release_readiness` | runs | **human gate** |

**Sub-tasks inside `implementation`**, scoped by the impact analysis — the agent
changes only the three modules named, which is the practical value of doing the
analysis first:

```
src/models.py     + ClickEvent; Link.click_count
src/storage.py    + record_click(), get_stats(); indexes on (code, clicked_at)
src/api.py        + GET /{code}/stats; redirect emits fire-and-forget event
migrations/002_add_click_analytics.sql   (written, NOT executed)
```

---

## 4. Orchestration — and where it differs

```
wave 0: [requirements]
wave 1: [design]
wave 2: [apply_migration]     <-- ⏸ HUMAN GATE #1  (was SKIPPED in greenfield)
wave 3: [implementation]
wave 4: [documentation, testing]
wave 5: [release_readiness]   <-- ⏸ HUMAN GATE #2
```

### The migration gate

```
[..] node=apply_migration
  condition: design_doc.requires_schema_change == true  ->  TRUE  (ran, not skipped)
  entry_gate:
    artifact_present(migration_plan)          PASS
    field_non_empty(migration_plan.rollback_sql)  PASS
    field_non_empty(migration_plan.forward_sql)   PASS

  [GATE] requires_human_approval = true
         reason: DEC-0006 criterion 2 (schema change) + criterion 1
                 (irreversible in practice once writes occur)
  node -> BLOCKED_ON_GATE ; run -> AWAITING_APPROVAL
  write pending_approval.json ; persist state.json ; EXIT
```

`pending_approval.json` shows the human exactly what will run:

```json
{
  "node_id": "apply_migration",
  "reason": "DEC-0006 criterion 2 (schema/data-shape change) and criterion 1
             (irreversibility: a migration against data cannot always be cleanly
             reversed once writes have occurred).",
  "payload": {
    "forward_sql": [
      "CREATE TABLE click_events (id INTEGER PRIMARY KEY, code TEXT NOT NULL,
         clicked_at TEXT NOT NULL, referrer TEXT, country TEXT,
         FOREIGN KEY(code) REFERENCES links(code));",
      "CREATE INDEX idx_click_events_code_time ON click_events(code, clicked_at);",
      "ALTER TABLE links ADD COLUMN click_count INTEGER NOT NULL DEFAULT 0;"
    ],
    "rollback_sql": [
      "DROP INDEX IF EXISTS idx_click_events_code_time;",
      "DROP TABLE IF EXISTS click_events;"
    ],
    "impacted_modules": ["src/models.py", "src/storage.py", "src/api.py"],
    "high_impact_actions": [
      { "action": "CREATE TABLE click_events", "criteria": ["schema_change"] },
      { "action": "ALTER TABLE links ADD COLUMN", "criteria": ["schema_change",
        "irreversible"],
        "note": "SQLite cannot DROP COLUMN before 3.35; reversal may require a
                 table rebuild. Flagged explicitly rather than assumed reversible." }
    ]
  },
  "artifact_ref": { "migration_plan": "c7d8e9f0", "design_doc": "1a2b3c4d" }
}
```

**Note the honesty in the payload.** The `ALTER TABLE` is flagged as potentially
irreversible with the specific reason (SQLite's `DROP COLUMN` limitation), and the
`rollback_sql` deliberately does **not** claim to reverse it. A rollback script that
silently fails to reverse something is worse than one that admits the limit — the
human approving this can see exactly what is and is not undoable.

**Retry policy is deliberately `max_attempts: 1` with `non_retryable_errors: ["*"]`.**
Blind-retrying a partially-applied DDL can compound damage. A failed migration goes
straight to rollback, and if rollback fails, to `safe_stop_and_page` — the executor
does not improvise around a broken schema.

### Why the rest of the run is cheaper than greenfield

`implementation` modifies three modules rather than creating seven. `testing` runs the
existing suite as regression plus new suites. Nothing forces a rewrite of
`codec.py` or `validation.py`, because the impact analysis established they are
untouched — decomposition **scoped by analysis** rather than by default.

---

## 5. Re-planning in this scenario (a realistic mid-run change)

Suppose the human approves the migration, then during `implementation` the
stakeholder adds: *"also break down clicks by device type."*

```
$ python -m orchestrator replan --run-id r-... \
    --changed-artifact requirement_spec --reason "Added device-type breakdown"

[replan] transitive descendants: [design, apply_migration, implementation,
                                  testing, documentation, release_readiness]
[replan] intersect with declared re_plan_triggers:
           design            triggers on requirement_spec      -> STALE
           implementation    triggers on design_doc/api_schema -> STALE (cascade)
           testing           triggers on requirement_spec      -> STALE
           documentation     triggers on source_code           -> STALE (cascade)
           release_readiness triggers on source_code/test_results -> STALE (cascade)
           apply_migration   triggers on migration_plan ONLY   -> NOT staled
[replan] approvals: apply_migration @ c7d8e9f0 -> STILL VALID
                    (migration_plan unchanged; device_type is derived from the
                     existing user-agent header, no schema change needed)
[replan] lineage preserved for: requirements(partial), apply_migration
```

**This is the governance-preserving property doing visible work.** The already-approved
and already-applied migration is **not** re-run and its approval is **not** invalidated,
because `migration_plan` did not change. A naive "restart from the changed node"
would have re-run a destructive DDL that a human had already reviewed — asking for
approval twice for the same change, or worse, applying it twice.

Had the new requirement needed a *new* column, `migration_plan` would have changed,
the approval would have been invalidated, and the human would have been re-prompted
with the new SQL. Both behaviours fall out of binding approval to `artifact_ref`.

---

## 6. Validation approach

Brownfield validation is dominated by **regression**, which is its structural
difference from greenfield.

| Risk | Validation | Why it matters here specifically |
|---|---|---|
| **Redirect latency regression** | AC3 perf test: p95 before vs. after, ≤5ms delta | The hot path was modified; this is the change most likely to hurt |
| **Analytics failure breaks redirects** | AC4 fault injection: force `record_click` to raise, assert 302 still returned | Fire-and-forget must be genuinely decoupled, not nominally |
| **Migration destroys data** | Rollback SQL reviewed by a human *before* execution; post-migration schema verified against the plan | DEC-0006 criterion 2 |
| **Old links break** | AC5: create link, migrate, assert redirect works and stats show zero | The classic brownfield failure |
| **Privacy regression** | AC6: schema inspection asserting no IP column; unit test on truncation | The assumption A1 must be enforced, not just stated |
| **Existing tests break** | Full greenfield suite runs as regression inside `testing` | `no_test_weakening` blocks "fixing" failures by deleting them |
| **Stale cached counts** | Cache holds `code → url` only; stats read through to storage | Caching an aggregate would silently serve wrong numbers |

**The anti-gaming gate matters most here.** In brownfield there is now a *previous
run's* test suite to compare against, so `no_test_weakening` has real teeth: if the
agent makes a failing regression test pass by deleting it, the condition fires. In
greenfield there was no prior suite to compare to.

---

## 7. What this scenario proves

1. **Codebase reasoning is real** (Req 3): specific modules, specific data-flow
   change, specific back-compat handling — enforced by a gate, not left to good
   intentions.
2. **The gate policy varies with actual risk**: 2 gates here vs. 1 in greenfield,
   from the same graph definition.
3. **Controlled autonomy is visible**: the agent *writes* the migration and *stops*;
   a human reads the SQL and its stated irreversibility before any DDL runs.
4. **Re-planning preserves governance**: an approved, applied migration survives an
   unrelated requirement change instead of being re-executed.
5. **The traversal genuinely differs from greenfield** — different nodes active,
   different gates, different validation emphasis.

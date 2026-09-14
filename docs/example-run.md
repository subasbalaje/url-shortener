# Worked Example: Greenfield Run, End to End

**Purpose.** A concrete target shape for the implementation. Every state
transition, file write and metric below is what the system **should** produce —
this is the specification an implementer codes against and a test asserts on, not
a transcript of something that already ran.

> **This is a designed trace, not a captured log.** The orchestrator is currently
> a skeleton (`orchestrator/README.md` says exactly what is and is not
> implemented). The graph topology, execution order, parallel wave and staleness
> sets below are **not** invented — they were computed from
> `docs/orchestration-graph.yaml` (see §1). The timings are illustrative.

**Scenario.** Greenfield build of the URL shortener core: create + redirect +
storage + tests + docs. Request:

> *"Build a URL shortener service with an API to create short links and a
> redirect endpoint. Include storage, tests, and documentation."*

**Run:** `run_id = r-20260913-0741-a3f9`, `mode = greenfield`

---

## 1. Load and validation (before anythgit ing executes)

```
$ java -jar target/orchestrator-cli.jar run --graph docs/orchestration-graph.yaml \
    --request "Build a URL shortener service with an API to create short links..." \
    --mode greenfield

[load] schema_version 1.0 — OK
[load] 7 nodes, 7 edges — node ids unique — OK
[load] all depends_on resolve — OK
[load] cycle check (Kahn): 7 of 7 nodes emitted — ACYCLIC — OK
[load] artifact registry: all produces + re_plan_triggers resolve — OK
[load] parallelism assertions: testing || documentation (both wave 4) — OK
[load] policy cross-check (DEC-0006): declared gates {apply_migration,
       release_readiness} == classified high-impact — OK
[load] rollback coverage: all schema-changing nodes declare rollback_action — OK

Topological order:
  1 requirements   2 design      3 apply_migration   4 implementation
  5 documentation  6 testing     7 release_readiness

Execution waves (parallelism derived from the edge set):
  wave 0: [requirements]
  wave 1: [design]
  wave 2: [apply_migration]
  wave 3: [implementation]
  wave 4: [documentation, testing]     <-- parallel
  wave 5: [release_readiness]          <-- join barrier

Run r-20260913-0741-a3f9 initialised.
```

Validation is **fail-closed and complete before execution**: a graph that does
not validate never runs a single node. Because the graph *is* the governance
artifact, a partially-honoured one would be worse than none — it would still
look enforced.

---

## 2. Timeline

Legend: `✓` completed · `⊘` skipped · `⏸` gate pause · `↻` retry · `⤺` rollback

```
t=0s     ┌─ requirements ──────────────────────────────────── ✓  (14.2s)
t=14s    ├─ design ────────────────────────────────────────── ✓  (31.8s)
t=46s    ├─ apply_migration ─────────────────────────────── ⊘ SKIPPED
t=46s    ├─ implementation ──────────────────────────────┐
         │                                    ↻ attempt 1 FAILED (exit gate)
         │                                    ↻ backoff 2s
         │                                    ✓ attempt 2 OK      (68.4s total)
t=114s   ├─ testing ──────────┐  documentation ──────────┤  ← PARALLEL (wave 4)
         │   ✓ (42.1s)        │   ✓ (28.7s)              │
t=156s   ├─ release_readiness ┴──────────────────────────┘  ← JOIN (all_success)
         │   ⏸ BLOCKED_ON_GATE  ── process exits ──
         │      ... human wait: 38.5s ...
         │   ✓ resumed, approved                            (9.1s)
t=204s   └─ RUN COMPLETED
```

---

## 3. Node-by-node

### 3.1 `requirements` — wave 0

```
[r-20260913-0741-a3f9] node=requirements attempt=1
  PENDING -> READY        deps: [] (root node)
  entry_gate: input_present(raw_request) -> PASS
  READY -> RUNNING        agent=requirements_agent
  ... 14.2s ...
  exit_gate (4 conditions, all evaluated):
    field_non_empty(requirement_spec.acceptance_criteria)     PASS (6 criteria)
    all_items_have_field(acceptance_criteria, verifiable_by)  PASS
    ambiguities_resolved_or_escalated(ambiguity_report)       PASS (2 named)
    field_non_empty(requirement_spec.assumptions)             PASS (2 assumptions)
  RUNNING -> COMPLETED
  artifacts: requirement_spec (sha a1b2c3d4), ambiguity_report (sha e5f6a7b8)
```

Note the gate evaluates **all four** conditions rather than short-circuiting on
the first pass/fail, so a failing agent gets a complete list to fix in one retry
instead of discovering problems serially.

Produced `requirement_spec` (abridged):

```json
{
  "normalised_problem": "HTTP service mapping short codes to target URLs, with
                         creation API, redirect endpoint, persistent storage.",
  "acceptance_criteria": [
    { "id": "AC1", "text": "POST /api/v1/links returns a 7-char code and 201",
      "verifiable_by": "integration test" },
    { "id": "AC2", "text": "GET /{code} returns 302 with Location header",
      "verifiable_by": "integration test" },
    { "id": "AC3", "text": "Unknown code returns 404",
      "verifiable_by": "integration test" },
    { "id": "AC4", "text": "Target URL validated: scheme allowlist + internal-range denylist",
      "verifiable_by": "unit test" },
    { "id": "AC5", "text": "Codes unpredictable (CSPRNG), not sequential",
      "verifiable_by": "unit test (statistical)" },
    { "id": "AC6", "text": "Link mapping survives restart",
      "verifiable_by": "integration test" }
  ],
  "assumptions": [
    { "id": "A1", "text": "Single-tenant; no user accounts in scope.",
      "rationale": "Not mentioned in the request; DEC-0008 scopes auth out." },
    { "id": "A2", "text": "Analytics deferred to the brownfield scenario.",
      "rationale": "Per DEC-0001, greenfield builds core; analytics is the
                    brownfield enhancement." }
  ]
}
```

And `ambiguity_report` — note both ambiguities are **named and resolved with a
stated assumption**, which is what the exit gate requires:

```json
{
  "ambiguities": [
    { "id": "AMB1", "text": "'Short links' — length unspecified.",
      "resolution": "7 characters, base62",
      "assumption": "7 chars gives ~3.5e12 codes, ample at any plausible volume",
      "rationale": "research-notes.md §6.1", "escalated_to_human": false },
    { "id": "AMB2", "text": "Custom aliases not mentioned; include or not?",
      "resolution": "Include as optional field, out of scope for core AC",
      "assumption": "Cheap to support now; avoids a schema change later",
      "rationale": "DEC-0008 lists custom_alias in the create API",
      "escalated_to_human": false }
  ]
}
```

### 3.2 `design` — wave 1

```
[..] node=design attempt=1
  PENDING -> READY        deps: [requirements] all COMPLETED
  entry_gate: artifact_present(requirement_spec) PASS
              upstream_state(requirements == COMPLETED) PASS
  READY -> RUNNING        agent=design_agent
  ... 31.8s ...
  exit_gate (5 conditions):
    field_non_empty(design_doc.components)                    PASS (4 components)
    field_non_empty(api_schema)                               PASS (OpenAPI 3.1)
    conditional_required(mode=='brownfield' -> impact_analysis) SKIPPED (greenfield)
    conditional_required(requires_schema_change -> rollback_sql) SKIPPED (false)
    high_impact_actions_flagged(design_doc.high_impact_actions) PASS (none declared;
                                                PolicyEngine classification agrees)
  RUNNING -> COMPLETED
  artifacts: design_doc (sha 9c0d1e2f), api_schema (sha 3a4b5c6d),
             impact_analysis (empty), migration_plan (null)
```

Two `conditional_required` conditions **skip** here — the same graph serves all
three scenarios without branching. In `scenarios/brownfield.md` the first of them
fires and is mandatory.

`design_doc.requires_schema_change = false`: greenfield creates the initial schema
as part of first-time setup rather than migrating an existing one.

### 3.3 `apply_migration` — wave 2, SKIPPED

```
[..] node=apply_migration
  condition: design_doc.requires_schema_change == true  ->  FALSE
  PENDING -> SKIPPED
  lineage: node_skipped (reason: no schema change required in greenfield)
```

**This is the governance point of the whole trace.** The human gate on this node
did *not* fire — because there was nothing high-impact to approve. The gate is
conditional on real risk, not decoration. In `scenarios/brownfield.md` the same
node runs and **does** pause for a human. Same graph, different risk, different
gate behaviour.

`SKIPPED` counts as terminal-success for join purposes, so `implementation` is
not blocked.

### 3.4 `implementation` — wave 3, with a retry ↻

```
[..] node=implementation attempt=1
  PENDING -> READY   deps: design=COMPLETED, apply_migration=SKIPPED (both satisfy join)
  entry_gate: artifact_present(design_doc) PASS
              artifact_present(api_schema) PASS
              upstream_state_in(apply_migration, [COMPLETED, SKIPPED]) PASS
  READY -> RUNNING   agent=implementation_agent
  ... 44.1s ...
  exit_gate (4 conditions):
    code_imports_cleanly(src/)                         PASS
    api_contract_conformance(api_schema, src/)         FAIL
        -> "Implemented endpoints do not match the declared API schema:
            src/ exposes DELETE /api/v1/links/{code} returning 204;
            api_schema declares 200 with a body."
    no_secrets_in_source(src/)                         PASS
    no_unapproved_high_impact()                        PASS
  exit_gate FAILED (1 of 4)
  RUNNING -> FAILED       error_type=GateFailure
  MTTR clock starts: first_failed_at = 2026-09-13T07:42:19Z

  [failure ladder]
    non-retryable? GateFailure not in [ValidationError, PolicyViolationError,
                                        HumanRejectedError]  -> retryable
    attempts remaining? 1 of 3 used -> YES
  FAILED -> RETRYING      backoff = 2.0s  (initial=2, coefficient=2, attempt 2)

[..] node=implementation attempt=2
  RETRYING -> RUNNING     (gate failure passed back as agent feedback)
  ... 22.3s ...
  exit_gate: all 4 conditions PASS
  RUNNING -> COMPLETED
  MTTR clock stops: recovered_at = 2026-09-13T07:42:43Z  -> recovery 24.3s
  artifacts: source_code (sha 7e8f9a0b)
```

Three things this demonstrates:

1. **The exit gate did real work.** It caught a genuine contract mismatch that
   `code_imports_cleanly` would never see — the code was valid Python that
   violated its own API contract.
2. **Retry is a self-loop, not an edge.** `attempt` incremented on `NodeRuntime`;
   the DAG was untouched and remains acyclic.
3. **The failure message became the retry's input.** Attempt 2 fixed the specific
   named problem rather than regenerating blindly.

### 3.5 `testing` ∥ `documentation` — wave 4, parallel

```
[..] frontier = [testing, documentation]  -> asyncio.gather(return_exceptions=True)

  ┌── node=testing attempt=1 ─────────────────────────────────────────┐
  │  entry_gate: artifact_present(source_code) PASS                    │
  │              field_non_empty(acceptance_criteria) PASS             │
  │  READY -> RUNNING   agent=testing_agent                            │
  │  ... 42.1s ...  (18 tests: 12 unit, 6 integration)                 │
  │  exit_gate (5 conditions):                                         │
  │    tests_executed                              PASS (18 executed)  │
  │    all_tests_pass                              PASS (0 failures)   │
  │    acceptance_criteria_covered                 PASS (AC1-AC6 all)  │
  │    no_test_weakening                           PASS (no prior run) │
  │    coverage_threshold(min=70)                  PASS (83.4%)        │
  │  RUNNING -> COMPLETED                                              │
  └────────────────────────────────────────────────────────────────────┘
  ┌── node=documentation attempt=1 ───────────────────────────────────┐
  │  entry_gate: artifact_present(source_code) PASS                    │
  │              artifact_present(api_schema) PASS                     │
  │  READY -> RUNNING   agent=documentation_agent                      │
  │  ... 28.7s ...                                                     │
  │  exit_gate (3 conditions):                                         │
  │    documented_endpoints_exist                  PASS (5 of 5)       │
  │    setup_instructions_present                  PASS                │
  │    no_contradictions                           PASS                │
  │  RUNNING -> COMPLETED                                              │
  └────────────────────────────────────────────────────────────────────┘

  wave elapsed: 42.1s  (not 70.8s — the two ran concurrently)
```

`return_exceptions=True` means a failure in one branch would not cancel the
other: documentation work already completed is not discarded because testing
failed. Each result is routed to success/failure handling individually.

### 3.6 `release_readiness` — wave 5, JOIN + HUMAN GATE ⏸

```
[..] node=release_readiness
  join_policy: all_success
    testing       = COMPLETED  ✓
    documentation = COMPLETED  ✓
  -> barrier satisfied, node becomes READY

  entry_gate: upstream_state(testing == COMPLETED) PASS
              upstream_state(documentation == COMPLETED) PASS
              all_tests_pass(test_results.failures empty) PASS
  READY -> RUNNING    agent=release_readiness_agent
  ... 9.1s ...
  exit_gate (4 conditions):
    field_present(recommendation in [go, no_go, go_with_conditions])  PASS ("go")
    field_non_empty(residual_risks)                                   PASS (3)
    field_non_empty(rationale)                                        PASS
    recommendation_consistent_with_evidence                           PASS
  RUNNING -> ... exit gate passed, but requires_human_approval = true
```

**The gate fires:**

```
  [GATE] node=release_readiness requires human approval
         reason: DEC-0006 criterion 4 (release readiness)
  node  -> BLOCKED_ON_GATE
  run   -> AWAITING_APPROVAL
  write runs/r-20260913-0741-a3f9/pending_approval.json
  write runs/r-20260913-0741-a3f9/state.json   (atomic: tmp + os.replace)
  append lineage: approval_requested

  Run paused awaiting approval.
    Review:  runs/r-20260913-0741-a3f9/pending_approval.json
    Respond: write approval.json in the same directory
    Resume:  python -m orchestrator resume --run-id r-20260913-0741-a3f9

$ echo $?
0
```

**The process has exited.** Nothing is sleeping or polling. This is
persist-and-exit (DEC-0007), and it is what makes the gate provable: you can
inspect it, reboot the machine, and still resume.

`pending_approval.json`:

```json
{
  "run_id": "r-20260913-0741-a3f9",
  "node_id": "release_readiness",
  "node_name": "Release readiness assessment",
  "reason": "DEC-0006 criterion 4 (release readiness). The agent may assess and
             recommend; only a human may declare software fit to ship.",
  "requested_at": "2026-09-13T07:44:31Z",
  "payload": {
    "readiness_report": {
      "recommendation": "go",
      "rationale": "All 6 acceptance criteria are covered by executed tests
                    (18 passing, 83.4% coverage). API matches the declared
                    OpenAPI schema. URL validation implements the scheme
                    allowlist and internal-range denylist per AC4.",
      "residual_risks": [
        "SQLite single-writer limits write concurrency (DEC-0002, accepted)",
        "No rate limiting yet — DEC-0008 scopes it to a later iteration",
        "DNS-rebinding window remains in URL validation (research-notes §6.6)"
      ]
    },
    "test_results_summary": { "total": 18, "passed": 18, "failed": 0,
                              "coverage_percent": 83.4 },
    "acceptance_criteria": ["AC1","AC2","AC3","AC4","AC5","AC6"]
  },
  "artifact_ref": {
    "readiness_report": "b2c3d4e5",
    "source_code":      "7e8f9a0b",
    "test_results":     "1f2a3b4c"
  },
  "respond_by": "write approval.json in this directory",
  "response_schema": {
    "approver": "str",
    "decision": "approved | rejected | approved_with_conditions",
    "comment":  "str (required — the rationale is the audit-relevant part)"
  }
}
```

`artifact_ref` binds the decision to **exact content hashes**. If `source_code`
changes after this approval, the approval is void — it approved different
content.

**Human responds** (`approval.json`):

```json
{
  "approver": "mukesh",
  "decision": "approved",
  "comment": "Residual risks are acknowledged and acceptable for a prototype.
              Rate limiting must land before any public exposure."
}
```

**Resume:**

```
$ java -jar target/orchestrator-cli.jar resume --run-id r-20260913-0741-a3f9

[resume] load state.json — run r-20260913-0741-a3f9 (AWAITING_APPROVAL)
[resume] read approval.json — decision=approved by mukesh
[resume] verify artifact_ref hashes against current artifacts:
           readiness_report b2c3d4e5 == b2c3d4e5  OK
           source_code      7e8f9a0b == 7e8f9a0b  OK
           test_results     1f2a3b4c == 1f2a3b4c  OK
         approval still valid.
[resume] append lineage: approval_granted
  node release_readiness: BLOCKED_ON_GATE -> COMPLETED
  human wait: 38.5s (computed from persisted timestamps, not an in-memory timer)
  remove pending_approval.json
  run -> COMPLETED
```

The hash verification in step 3 is the safeguard: had an artifact changed while
the run was paused, the approval would be **rejected as stale** and the node
re-gated rather than proceeding on an approval granted for something else.

---

## 4. Resulting `metrics.json`

```json
{
  "run_id": "r-20260913-0741-a3f9",
  "graph_id": "sdlc-core",
  "mode": "greenfield",
  "final_state": "COMPLETED",
  "started_at": "2026-09-13T07:41:02Z",
  "ended_at":   "2026-09-13T07:44:26Z",

  "nodes_total": 7,
  "nodes_attempted": 6,
  "nodes_completed": 6,
  "nodes_skipped": 1,
  "nodes_failed": 0,
  "nodes_upstream_failed": 0,
  "nodes_stale_replanned": 0,

  "success_rate": 1.0,
  "retry_frequency": 0.1667,
  "rollback_frequency": 0.0,
  "total_retry_attempts": 1,
  "rollbacks_executed": 0,
  "gate_failures_total": 1,

  "failure_episodes": 1,
  "mttr_seconds": 24.3,

  "end_to_end_seconds": 204.3,
  "time_running_seconds": 161.5,
  "time_blocked_on_gate_seconds": 38.5,
  "time_retrying_seconds": 2.0,

  "critical_path": ["requirements","design","implementation","testing","release_readiness"],
  "critical_path_seconds": 165.6,
  "parallel_efficiency": 0.81,

  "human_gates_encountered": 1,
  "approvals_granted": 1,
  "approvals_rejected": 0,
  "approvals_invalidated_by_replan": 0,
  "replan_cycles": 0,

  "nodes": {
    "requirements":      { "final_state": "COMPLETED", "attempts": 1, "retries": 0,
                           "time_running_seconds": 14.2, "total_seconds": 14.2 },
    "design":            { "final_state": "COMPLETED", "attempts": 1, "retries": 0,
                           "time_running_seconds": 31.8, "total_seconds": 31.8 },
    "apply_migration":   { "final_state": "SKIPPED",   "attempts": 0, "retries": 0,
                           "total_seconds": 0.0 },
    "implementation":    { "final_state": "COMPLETED", "attempts": 2, "retries": 1,
                           "gate_failures": 1, "time_running_seconds": 66.4,
                           "time_retrying_seconds": 2.0, "total_seconds": 68.4,
                           "first_failed_at": "2026-09-13T07:42:19Z",
                           "recovered_at":    "2026-09-13T07:42:43Z",
                           "recovery_seconds": 24.3 },
    "testing":           { "final_state": "COMPLETED", "attempts": 1, "retries": 0,
                           "time_running_seconds": 42.1, "total_seconds": 42.1 },
    "documentation":     { "final_state": "COMPLETED", "attempts": 1, "retries": 0,
                           "time_running_seconds": 28.7, "total_seconds": 28.7 },
    "release_readiness": { "final_state": "COMPLETED", "attempts": 1, "retries": 0,
                           "time_running_seconds": 9.1, "time_blocked_seconds": 38.5,
                           "total_seconds": 47.6 }
  }
}
```

**How to read these numbers:**

- `success_rate = 1.0` is `6/6` **attempted**. The skipped node is excluded from
  both numerator and denominator — it neither succeeded nor failed, and counting
  it either way would be a lie in a different direction.
- `mttr_seconds = 24.3` — mean time to **recovery**, one failure episode
  (implementation attempt 1 → attempt 2 success). Not "repair", "resolve" or
  "respond"; the definition is fixed in `metrics.py`.
- `parallel_efficiency = 0.81` — the critical path is 165.6s against 204.3s
  wall-clock. The 38.7s gap is almost exactly the human gate wait, which is the
  correct interpretation: the run was as fast as its dependency structure
  allowed, and the remainder was a person thinking. Had latency been reported as
  one number, this run would look 19% "slow" for no engineering reason.
- `time_blocked_on_gate` is broken out precisely so governance never masquerades
  as poor performance.

---

## 5. Resulting `lineage.jsonl` (excerpt)

Append-only, one JSON object per line. Abridged — a full run emits ~40 entries.

```jsonl
{"event":"run_started","run_id":"r-20260913-0741-a3f9","timestamp":"2026-09-13T07:41:02Z","actor":"orchestrator","details":{"mode":"greenfield","graph_id":"sdlc-core","nodes":7}}
{"event":"node_started","run_id":"r-20260913-0741-a3f9","node_id":"requirements","attempt":1,"timestamp":"2026-09-13T07:41:02Z","actor":"requirements_agent","from_state":"READY","to_state":"RUNNING"}
{"event":"decision_recorded","run_id":"r-20260913-0741-a3f9","node_id":"requirements","timestamp":"2026-09-13T07:41:14Z","actor":"requirements_agent","decision":"Short code length = 7 characters, base62","rationale":"~3.5e12 combinations; ample at any plausible volume for this service. See research-notes.md §6.1.","details":{"ambiguity_id":"AMB1"}}
{"event":"gate_exit_passed","run_id":"r-20260913-0741-a3f9","node_id":"requirements","attempt":1,"timestamp":"2026-09-13T07:41:16Z","details":{"conditions_evaluated":4,"conditions_passed":4}}
{"event":"node_completed","run_id":"r-20260913-0741-a3f9","node_id":"requirements","attempt":1,"timestamp":"2026-09-13T07:41:16Z","from_state":"RUNNING","to_state":"COMPLETED","artifact_refs":{"requirement_spec":"a1b2c3d4","ambiguity_report":"e5f6a7b8"}}
{"event":"node_skipped","run_id":"r-20260913-0741-a3f9","node_id":"apply_migration","timestamp":"2026-09-13T07:41:48Z","rationale":"condition design_doc.requires_schema_change == true evaluated false; greenfield creates the initial schema rather than migrating.","to_state":"SKIPPED"}
{"event":"gate_exit_failed","run_id":"r-20260913-0741-a3f9","node_id":"implementation","attempt":1,"timestamp":"2026-09-13T07:42:19Z","details":{"conditions_evaluated":4,"conditions_failed":1,"failures":["api_contract_conformance: src/ exposes DELETE /api/v1/links/{code} returning 204; api_schema declares 200 with a body."]}}
{"event":"node_failed","run_id":"r-20260913-0741-a3f9","node_id":"implementation","attempt":1,"timestamp":"2026-09-13T07:42:19Z","from_state":"RUNNING","to_state":"FAILED","details":{"error_type":"GateFailure"}}
{"event":"retry_scheduled","run_id":"r-20260913-0741-a3f9","node_id":"implementation","attempt":2,"timestamp":"2026-09-13T07:42:19Z","rationale":"GateFailure is retryable under policy; 1 of 3 attempts used.","details":{"backoff_seconds":2.0}}
{"event":"node_completed","run_id":"r-20260913-0741-a3f9","node_id":"implementation","attempt":2,"timestamp":"2026-09-13T07:42:43Z","from_state":"RUNNING","to_state":"COMPLETED","artifact_refs":{"source_code":"7e8f9a0b"},"details":{"recovery_seconds":24.3}}
{"event":"node_started","run_id":"r-20260913-0741-a3f9","node_id":"testing","attempt":1,"timestamp":"2026-09-13T07:42:43Z","actor":"testing_agent","details":{"parallel_with":["documentation"],"wave":4}}
{"event":"node_started","run_id":"r-20260913-0741-a3f9","node_id":"documentation","attempt":1,"timestamp":"2026-09-13T07:42:43Z","actor":"documentation_agent","details":{"parallel_with":["testing"],"wave":4}}
{"event":"approval_requested","run_id":"r-20260913-0741-a3f9","node_id":"release_readiness","timestamp":"2026-09-13T07:44:31Z","actor":"orchestrator","rationale":"DEC-0006 criterion 4 (release readiness): only a human may declare software fit to ship.","artifact_refs":{"readiness_report":"b2c3d4e5","source_code":"7e8f9a0b","test_results":"1f2a3b4c"}}
{"event":"approval_granted","run_id":"r-20260913-0741-a3f9","node_id":"release_readiness","timestamp":"2026-09-13T07:45:09Z","actor":"mukesh","decision":"approved","rationale":"Residual risks are acknowledged and acceptable for a prototype. Rate limiting must land before any public exposure.","artifact_refs":{"readiness_report":"b2c3d4e5","source_code":"7e8f9a0b","test_results":"1f2a3b4c"}}
{"event":"run_completed","run_id":"r-20260913-0741-a3f9","timestamp":"2026-09-13T07:44:26Z","actor":"orchestrator","details":{"success_rate":1.0,"mttr_seconds":24.3,"end_to_end_seconds":204.3}}
```

Every entry carries the `run_id` / `node_id` / `attempt` correlation triple, so
"everything that happened to `implementation` on attempt 1" is a filter, not a
grep.

---

## 6. Resulting `decision-log.md` entries

Appended by `decision_log.py` — narrative entries for reviewer-relevant
decisions, not every transition:

```markdown
## RUN-r-20260913-0741-a3f9-001 — Short code length and alphabet
- **Date:** 2026-09-13T07:41:14Z
- **Actor:** requirements_agent (node: requirements)
- **Decision:** 7-character codes over the base62 alphabet.
- **Rationale:** Resolves ambiguity AMB1 ("short links", length unspecified).
  62^7 ≈ 3.5e12 combinations is ample at any plausible volume for this service.
  See research-notes.md §6.1.

## RUN-r-20260913-0741-a3f9-002 — Migration node skipped
- **Date:** 2026-09-13T07:41:48Z
- **Actor:** orchestrator (node: apply_migration)
- **Decision:** Skip apply_migration; no human gate fired.
- **Rationale:** `design_doc.requires_schema_change == false`. Greenfield creates
  the initial schema as first-time setup rather than migrating an existing one,
  so no DEC-0006 criterion is met and no approval is required. The gate is
  conditional on actual risk.

## RUN-r-20260913-0741-a3f9-003 — API contract mismatch caught at exit gate
- **Date:** 2026-09-13T07:42:19Z
- **Actor:** orchestrator (node: implementation, attempt 1)
- **Decision:** Reject implementation output; retry with gate feedback.
- **Rationale:** `api_contract_conformance` found DELETE /api/v1/links/{code}
  returning 204 where api_schema declares 200 with a body. The code compiled and
  imported cleanly — only the contract check caught it. Attempt 2 corrected the
  specific mismatch and passed all four conditions.

## RUN-r-20260913-0741-a3f9-004 — Release approved by human
- **Date:** 2026-09-13T07:45:09Z
- **Actor:** mukesh (node: release_readiness)
- **Decision:** approved
- **Rationale:** "Residual risks are acknowledged and acceptable for a prototype.
  Rate limiting must land before any public exposure."
- **Artifacts approved:** readiness_report@b2c3d4e5, source_code@7e8f9a0b,
  test_results@1f2a3b4c
```

---

## 7. Variant: failure ladder to rollback and safe-stop

The run above recovered on retry. This variant shows the ladder running to
completion — the path that must be **exercised, not merely declared**.

Scenario: `implementation` fails all three attempts.

```
[..] node=implementation attempt=1  exit_gate FAILED (code_imports_cleanly)
     FAILED -> RETRYING  backoff 2.0s        first_failed_at=07:42:19Z
[..] node=implementation attempt=2  exit_gate FAILED (code_imports_cleanly)
     FAILED -> RETRYING  backoff 4.0s        (initial=2 × coefficient=2)
[..] node=implementation attempt=3  exit_gate FAILED (code_imports_cleanly)
     FAILED

  [failure ladder]
    non-retryable? no
    attempts remaining? 3 of 3 used -> NO
    rollback_action defined? YES (revert_filetree)
  FAILED -> rollback
  [ROLLBACK] revert_filetree: restore src/ to pre-node snapshot
             verify: snapshot hash matches -> OK
  -> ROLLED_BACK                              recovered_at=07:43:58Z
  append lineage: rollback_completed

  [join] testing: dependency implementation is terminally failed
         -> testing = UPSTREAM_FAILED (not FAILED — one root cause, counted once)
  [join] documentation -> UPSTREAM_FAILED
  [join] release_readiness: join_policy all_success NOT satisfied
         -> UPSTREAM_FAILED, on_upstream_failure=propagate_upstream_failed
         Refusing to assess readiness from partial inputs.

  [SAFE-STOP] reason: "implementation exhausted retry budget (3 attempts);
                       rollback succeeded; no path forward without intervention"
    persist state.json
    append lineage: run_safe_stopped
    finalise metrics.json
    write safe_stop_report.md
  run -> SAFE_STOPPED
$ echo $?
1
```

Resulting metrics for the failed run:

```json
{
  "final_state": "SAFE_STOPPED",
  "nodes_attempted": 4, "nodes_completed": 2, "nodes_failed": 1,
  "nodes_upstream_failed": 3, "nodes_skipped": 1,
  "success_rate": 0.5,
  "retry_frequency": 0.5, "rollback_frequency": 0.25,
  "total_retry_attempts": 2, "rollbacks_executed": 1,
  "failure_episodes": 1, "mttr_seconds": 99.0,
  "human_gates_encountered": 0,
  "safe_stop_reason": "implementation exhausted retry budget (3 attempts); rollback succeeded"
}
```

Note `success_rate = 0.5` (`2/4`), not `2/7`: the three `UPSTREAM_FAILED` nodes
are excluded from `nodes_attempted`. One root cause is counted once. Counting
them as failures would report 29% success for a single broken node and make the
metric actively misleading.

**`release_readiness` never ran, and never reached a human.** A verdict computed
from a half-built system is worse than no verdict, so the join barrier refused to
fire. That refusal is the system working correctly.

---

## 8. Variant: dynamic re-planning

Scenario: after the run completes, the requirement changes — expiry is now
required.

```
$ java -jar target/orchestrator-cli.jar replan --run-id r-20260913-0741-a3f9 \
    --changed-artifact requirement_spec \
    --reason "Stakeholder added expiry requirement (AC7)"

[replan] requirement_spec: a1b2c3d4 -> f9e8d7c6 (changed)
[replan] producer: requirements
[replan] transitive descendants:
           [design, apply_migration, implementation, testing, documentation,
            release_readiness]
[replan] intersect with declared re_plan_triggers:
           design            triggers on requirement_spec   -> STALE
           testing           triggers on requirement_spec   -> STALE
           implementation    triggers on design_doc/api_schema -> STALE (cascade)
           documentation     triggers on source_code/api_schema -> STALE (cascade)
           release_readiness triggers on source_code/test_results -> STALE (cascade)
           apply_migration   triggers on migration_plan only -> NOT staled
[replan] approvals invalidated:
           release_readiness @ b2c3d4e5 -> VOID (readiness_report will change)
[replan] lineage preserved for: requirements (unchanged), apply_migration
[replan] replan_cycles: 1 of 3

  5 nodes marked STALE. 2 nodes retain state and lineage.
  Run resumes from the earliest stale node.
```

Four properties this demonstrates, each a stated requirement:

1. **Staleness, not restart.** `requirements` keeps `COMPLETED` and its full
   lineage; only genuinely-invalidated work re-runs.
2. **Triggers filter the blast radius.** `apply_migration` is a descendant but
   declares no trigger on `requirement_spec`, so it is *not* staled. Blanket
   invalidation of all descendants would have discarded valid work.
3. **Approval invalidated.** The prior release approval is void because the
   artifact it bound to will change. Without this, the re-planned run would
   silently inherit an approval granted for different content — the exact
   governance hole `artifact_ref` exists to close.
4. **Lineage preserved.** `preserved_nodes` is recorded explicitly, as positive
   evidence that re-planning maintained governance rather than resetting it.

`lineage.jsonl` gains:

```jsonl
{"event":"replan_triggered","run_id":"r-20260913-0741-a3f9","timestamp":"2026-09-13T08:12:04Z","actor":"human:mukesh","rationale":"Stakeholder added expiry requirement (AC7)","details":{"changed_artifact":"requirement_spec","old_hash":"a1b2c3d4","new_hash":"f9e8d7c6","staled_nodes":["design","implementation","testing","documentation","release_readiness"],"preserved_nodes":["requirements","apply_migration"],"replan_cycle":1}}
{"event":"approval_invalidated","run_id":"r-20260913-0741-a3f9","node_id":"release_readiness","timestamp":"2026-09-13T08:12:04Z","rationale":"Approved artifact readiness_report will change under re-plan; approval bound to hash b2c3d4e5 is void.","artifact_refs":{"readiness_report":"b2c3d4e5"}}
```

---

## 9. What an implementer should assert

This document doubles as a test specification. `tests/` should assert:

| # | Assertion | Source |
|---|---|---|
| 1 | Graph loads, validates, and is acyclic (7 nodes) | §1 |
| 2 | Topological order matches the computed order | §1 |
| 3 | `testing` and `documentation` occupy the same wave | §1, §3.5 |
| 4 | `apply_migration` skips when `requires_schema_change == false` | §3.3 |
| 5 | `SKIPPED` satisfies a downstream join | §3.4 |
| 6 | Exit-gate failure triggers retry with correct backoff (2s, 4s) | §3.4, §7 |
| 7 | Retry increments `attempt` without adding a graph edge | §3.4 |
| 8 | Gate writes `pending_approval.json` and the process exits 0 | §3.6 |
| 9 | `resume` continues from persisted state after process death | §3.6 |
| 10 | Changed artifact hash invalidates an approval on resume | §3.6 |
| 11 | Retry exhaustion → rollback → safe-stop, exit 1 | §7 |
| 12 | Terminal dependency failure yields `UPSTREAM_FAILED`, not `FAILED` | §7 |
| 13 | Join refuses to fire on partial success | §7 |
| 14 | `success_rate` excludes skipped and upstream-failed from the denominator | §4, §7 |
| 15 | MTTR measures first-failure → recovery, not last attempt | §4 |
| 16 | Re-plan stales only descendants with matching triggers | §8 |
| 17 | Re-plan preserves lineage for unaffected nodes | §8 |
| 18 | `lineage.jsonl` is append-only (prior lines never change) | §5 |

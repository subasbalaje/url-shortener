# `tests/` — testing strategy

**Currently empty as a top-level directory.** Following standard Maven layout, tests
live under `src/test/java/com/sdlc/...` (mirroring `src/main/java/com/sdlc/...`) and
fixtures under `src/test/resources/`. This file states the strategy; the physical
JUnit files live in the Maven module.

Tests are written alongside each module per the build order in
`orchestrator/README.md` — not retrofitted at the end.

The governing principle: **the orchestrator's correctness claims must be executable.**
"Bounded retry", "gates pause execution", "re-planning preserves lineage" and
"lineage is append-only" are either asserted in a test or they are marketing.

---

## Layout

```
src/test/java/com/sdlc/
  orchestrator/
    model/        RetryPolicyTest NodeStateTest ArtifactHashTest ...
    graph/        GraphLoaderTest GraphValidatorTest GraphAlgorithmsTest
    gates/        every checker: pass and fail, CheckerRegistryTest, PolicyEngineTest
    lineage/      DecisionLoggerTest (append-only property, JSONL integrity)
    metrics/      MetricsCollectorTest (the four metric definitions)
    exec/         StructuredScopeTest GraphExecutorTest FailureLadderTest
                  GatePauseResumeIT ReplanningIT
  shortener/
    service/      CodecServiceTest ValidationServiceTest CacheServiceTest
    store/        SqliteLinkStoreTest
    web/          LinkControllerTest RedirectControllerTest (MockMvc / @SpringBootTest)
src/test/resources/
  graphs/         valid.yaml cyclic.yaml dangling-dep.yaml
                  bad-parallel-claim.yaml policy-drift.yaml
  runs/           pre-built run states for resume tests
```

---

## Unit tests — the orchestrator

### Graph algorithms (`GraphAlgorithmsTest`) — **highest value**

This is where hand-rolled executors break, and it is pure-function territory: no
concurrency, no I/O, no mocking.

- Topological order is valid (every edge respected) and matches the known order.
- **Cycle detection**: a cyclic fixture throws `GraphValidationException` **naming
  the nodes in the cycle**. "Graph has a cycle" without the node list is not
  actionable.
- **Bounded retry is not a cycle**: a graph whose node has `max_attempts: 5` still
  validates as acyclic. This test encodes the project's central structural argument —
  retry is a self-loop in the node state machine, not an edge in the DAG.
- Frontiers: `testing` and `documentation` share wave 4; `release_readiness` is alone
  in wave 5.
- `validateParallelismClaims` **rejects** a fixture where two nodes claim
  parallelism but one depends on the other — the dangerous edit DEC-0011 exists to
  catch.
- Critical path returns the expected node sequence and sum.
- Transitive descendants of `implementation` == `{testing, documentation,
  release_readiness}`.

### Validation (`GraphLoaderTest` / `GraphValidatorTest`)

Fail-closed behaviour, one test per check: dangling `depends_on`, duplicate ids,
unregistered artifact in `produces`, unknown `re_plan_triggers.artifact`, wrong
`schema_version`. Plus: **all failures are reported at once**, not just the first.

### Gates (per-checker tests under `gates/`)

Each of the 20 checkers, passing and failing. The ones that carry real weight:

- `ambiguitiesResolvedOrEscalated` — **fails** on an ambiguity with neither a
  stated assumption nor an escalation. This is the check that makes
  `scenarios/ambiguous.md` work; if it cannot fail, that scenario is theatre.
- `noTestWeakening` — fails when test or assertion count drops with no requirement
  change. (Honest limit: mitigation, not proof — see architecture §7 risk 4.)
- `recommendationConsistentWithEvidence` — fails on `"go"` with failing tests.
- `acceptanceCriteriaCovered` — fails when a criterion has no mapped test.
- An **unknown condition type throws** rather than passing vacuously
  (`CheckerRegistryTest`).

### Policy (`PolicyEngineTest`)

- Each DEC-0006 criterion classifies correctly (DDL → `schema_change`, deletion →
  `deletion`, etc.).
- Graph-declaration drift is **caught**: a fixture where `apply_migration`
  declares `requires_human_approval: false` must fail to load. This closes the silent
  governance hole (architecture §7 risk 9).

### Lineage (`DecisionLoggerTest`)

- **Append-only is verified, not assumed**: snapshot the file, append, assert the
  snapshot is a strict prefix. Any divergence means history was rewritten.
- JSONL integrity: every line parses; no embedded newlines.
- A torn final line (simulated crash) is skipped on read rather than failing the file.

### Metrics (`MetricsCollectorTest`)

Each definition, because each is easy to compute in a way that quietly misleads:

- `successRate` **excludes skipped and upstream-failed** from the denominator. Fixture
  from `example-run.md` §7: 2 completed, 1 failed, 3 upstream-failed, 1 skipped →
  `0.5`, not `2/7`.
- MTTR uses **first** failure → recovery, not last attempt.
- `mttrSeconds` is absent/`null` (not `0.0`) when there were no failures.
- Latency breakdown sums correctly and gate wait is separated from run time.
- Percentiles are withheld below the 10-run threshold.

---

## Integration tests

### `GatePauseResumeIT` — **the governance-critical test**

1. Run until the gate; assert `pending_approval.json` exists and **exit code is 0**.
2. Assert the node is `BLOCKED_ON_GATE` and the run is `AWAITING_APPROVAL` on disk.
3. **Discard the `GraphExecutor` instance entirely** (simulating process death).
4. Write `approval.json`; construct a **fresh** `GraphExecutor` and call `resume()`.
5. Assert the run completes and the approval appears in lineage with all five fields.
6. **Stale-approval test:** mutate an approved artifact between pause and resume;
   assert the approval is **rejected** and the node re-gated.

Step 3 is the point. Without it this test would pass for a `Thread.sleep()`.

### `FailureLadderIT`

- Retry with correct backoff intervals (2.0s, 4.0s) and `attempt` incrementing.
- A `non_retryable_errors` match skips retry entirely and goes straight to rollback.
- Exhaustion → rollback → `SAFE_STOPPED`, **exit code 1**.
- Terminal dependency failure → dependents are `UPSTREAM_FAILED`, **not** `FAILED`.
- The join **refuses to fire** on partial success — `release_readiness` never runs.
- `on_rollback_failure` → safe-stop, and the executor does **not** retry the rollback.

### `ReplanningIT`

- Changing `requirement_spec` stales the descendants that declare a matching trigger.
- **`apply_migration` is NOT staled** by a `requirement_spec` change — it declares a
  trigger on `migration_plan` only. This asserts that triggers filter the blast
  radius instead of blanket-invalidating.
- Lineage for unaffected nodes is **preserved** (byte-identical prefix).
- An approval bound to a changed artifact is invalidated; one bound to an unchanged
  artifact survives.
- `max_replan_cycles` exceeded → safe-stop.

---

## The URL shortener

Shortener tests cover AC1–AC6 from `scenarios/greenfield.md`, plus:

- **Security (highest priority):** scheme allowlist rejects `file:`/`javascript:`;
  internal ranges rejected including `169.254.169.254`; **a public hostname resolving
  to a private IP is rejected** (the DNS-resolution check, not just string matching);
  reserved aliases rejected; self-shortening rejected.
- **Code generation:** codes are not sequential across a sample (AC5); collision retry
  is bounded and throws rather than looping.
- **Redirect semantics:** 302 not 301; `Cache-Control: no-store`; expired/disabled →
  **410**, unknown → 404.
- **Idempotency:** replayed `Idempotency-Key` returns the original, does not create a
  second link.
- **Persistence:** mapping survives restart (AC6).

Brownfield additions: latency does not regress (AC3); **forced click-recording
failure still returns 302** (AC4); pre-migration links still work (AC5); **no IP
column exists in the schema** (AC6).

---

## Running

```bash
mvn test                                  # everything
mvn test -Dtest="com.sdlc.orchestrator.**"
mvn test -Dgroups="!slow"                 # skip perf tests (JUnit 5 tagging)
mvn verify                                # runs jacoco coverage check as part of the build
```

Coverage target **70%** on the `orchestrator` packages (`jacoco-maven-plugin`,
matching the `coverage_threshold` gate). Coverage is a floor, not a goal — the 18
assertions in `docs/example-run.md` §9 matter more than the percentage.

---

## What is deliberately not tested

Stated so the gaps are choices rather than oversights:

- **LLM agent output quality.** Non-deterministic; exit gates constrain output *shape
  and validity*, and tests assert the gates work — not that a model writes good code.
- **Real concurrency races beyond what `stateLock` is meant to prevent.** Virtual
  threads are genuinely concurrent (DEC-0013), so this is now a real hazard rather
  than a structurally impossible one, and it is bounded by code review of every
  `Run` mutation site plus targeted concurrent-completion tests — not exhaustively
  proven.
- **Load/scale.** SQLite's single-writer limit is a known, accepted trade-off; testing
  it would measure a constraint we already documented.
- **Crash-durability mid-node.** Explicitly not supported (README limitation 2). Only
  gate-boundary durability is tested, because only that is claimed.

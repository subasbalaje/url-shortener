# `orchestrator/` — implementation status and build order

**This is the deliverable.** As of DEC-0012 it is implemented in **Java 21**
under `src/main/java/com/sdlc/orchestrator/` (Maven module root at the repo's
`pom.xml`). Everything below is currently a **structural skeleton**: real
classes, real method signatures, real Javadoc, and key control flow sketched
in comments — with bodies marked `TODO(impl)`.

This file is the honest account of what exists. Update the status table in the
same change that implements something.

---

## Status by package/class

| Class | Structure | Implementation | Notes |
|---|---|---|---|
| `model/NodeState` | ✅ | ✅ implemented | 11 states; `isTerminalSuccess`/`isTerminalFailure`/`isActive` — 7 tests |
| `model/RetryPolicy` | ✅ | ✅ implemented | Backoff + non-retryable matching, incl. `"*"` wildcard — 8 tests |
| `model/RunState`, `GateType`, `Decision` | ✅ | ✅ implemented | Vocabulary tests only (no behaviour) — 3 tests |
| `model/GateCondition`, `Gate`, `RollbackAction`, `RePlanTrigger` | ✅ | ✅ implemented | Plain records, construction/defaults — 6 tests |
| `model/NodeSpec` | ✅ | ✅ implemented | Builder (many optional fields); `isHighImpact()` — 3 tests |
| `model/GraphSpec` | ✅ | ✅ implemented | `node`, `nodeIds`, `dependentsOf`, `transitiveDependentsOf`, `producerOf` — 6 tests |
| `model/Artifact` | ✅ | ✅ implemented | SHA-256 over canonical (sorted-key) JSON, 16 hex chars — 6 tests |
| `model/ApprovalRecord` | ✅ | ✅ implemented | `permitsContinuation()` — 4 tests |
| `model/AttemptRecord`, `NodeRuntime` | ✅ | ✅ implemented | MTTR clock (`firstFailedAt`/`recoveredAt`) set-once semantics — 9 tests |
| `model/Run` | ✅ | 🟡 partial | Data shape + mutation done; `save()`/`load()` atomic persistence deferred to step 8 (`GateManager`), throw `UnsupportedOperationException` until then — 5 tests on what's implemented |
| `graph/GraphAlgorithms` | ✅ | ✅ implemented | `topologicalOrder` (deterministic — full TreeSet frontier, not just the initial seed), `parallelFrontiers`, `validateParallelismClaims`, `criticalPath` — 9 tests against the real 7-node graph shape |
| `graph/GraphValidationException` | ✅ | ✅ implemented | Carries every failure, not just the first |
| `graph/GraphValidator` | ✅ | ✅ implemented | All 9 checks, fail-closed, all failures collected — 13 tests. Check 8 (`requires_human_approval` vs DEC-0006) is a **documented narrower slice**: only `schema_change` and `release_readiness` are structurally classified today (see DEC-0014); the full 5-criteria classifier belongs in `gates.PolicyEngine` (step 6) |
| `graph/GraphLoader` | ✅ | ✅ implemented | YAML (snakeyaml) → model, delegates to `GraphValidator`. Loads and validates the real `docs/orchestration-graph.yaml` end to end — 12 tests incl. 5 fixture graphs (`valid`/`cyclic`/`dangling-dep`/`bad-parallel-claim`/`policy-drift`). Known simplification: does not reject unrecognised YAML keys (narrative-only fields like `retry_rationale` aren't modelled) — documented in its class Javadoc |
| `gates/CheckResult`, `ConditionChecker`, `CheckerRegistry`, `UnknownCheckerException` | ✅ | ✅ implemented | Registry dispatch; unknown type throws — 4 tests |
| `gates/RunFieldResolver`, `ExpressionEvaluator` | ✅ | ✅ implemented | Package-private helpers. Dotted field-path lookup into `Run` artifacts + `raw_request`/`run_context.*`; restricted `<field> ==/!= <literal>` grammar only (booleans, quoted strings, `[]`) — deliberately **not** a scripting engine, so a graph edit cannot execute arbitrary code (see class Javadoc) — 12 tests |
| `gates/GenericCheckers` | ✅ | ✅ implemented | `input_present`, `artifact_present`, `field_non_empty` (incl. `allow_empty_if`), `upstream_state`, `upstream_state_in`, `conditional_required` — 18 tests |
| `gates/ArtifactCheckers` | ✅ | ✅ implemented | 14 pure artifact-content checkers: `all_items_have_field`, `ambiguities_resolved_or_escalated`, `all_tests_pass`, `coverage_threshold`, `field_present`, `high_impact_actions_flagged`, `no_contradictions`, `no_test_weakening`, `no_unapproved_high_impact`, `recommendation_consistent_with_evidence`, `schema_matches_expectation`, `setup_instructions_present`, `tests_executed`, `acceptance_criteria_covered` — 28 tests. Each documents the artifact shape it expects in its Javadoc (the contract stage agents must honour once built) |
| `gates/SourceCodeCheckers` | ✅ | ✅ implemented | 4 filesystem/subprocess checkers: `code_imports_cleanly` (shells out to `javac`, **never in-process**), `no_secrets_in_source` (regex denylist — a mitigation, not a proof of absence), `documented_endpoints_exist`, `api_contract_conformance` (regex-scans Spring Mapping annotations; presence-only in both directions, does **not** diff response codes/bodies — stated scope limit in its Javadoc) — 10 tests via `@TempDir` |
| `gates/GateCheckers` | ✅ | ✅ implemented | `newFullyRegisteredRegistry()` wires all three checker groups together |
| `gates/PolicyEngine` | ✅ | ✅ implemented | DEC-0006 classification — same narrow 2-of-5-criteria scope as DEC-0014, now the single source of truth; `GraphValidator` delegates to it rather than duplicating the logic — 9 tests |
| `gates/GateEvaluator` | ✅ | ✅ implemented | AND-evaluation over a `Gate`'s conditions, never short-circuiting; unregistered type propagates rather than passing vacuously; prefers the condition's declared `error` over a checker's generic fallback — 5 tests, plus 3 end-to-end against real `design`/`requirements` gates from `docs/orchestration-graph.yaml` |
| `gates/GateManager` | ⬜ not yet created | ⬜ `TODO(impl)` | Persist-and-exit, `verifyApprovalStillValid` (build-order step 8) |
| `lineage/DecisionLogger` | ⬜ not yet created | ⬜ `TODO(impl)` | Append-only lineage writer |
| `metrics/*` | ⬜ not yet created | ⬜ `TODO(impl)` | `RunMetrics`/`NodeMetrics` shapes must match `docs/example-run.md` §4 |
| `exec/StructuredScope` | ⬜ not yet created | ⬜ `TODO(impl)` | Virtual-thread fan-out wrapper (DEC-0013) |
| `exec/GraphExecutor` | ⬜ not yet created | ⬜ `TODO(impl)` | Main loop, dispatch, gates, failure ladder |
| `gates/GateManager` | ⬜ not yet created | ⬜ `TODO(impl)` | Persist-and-exit, `resume()` |
| `agents/*` | ⬜ not yet created | ⬜ `TODO(impl)` | Deterministic stub implementations first (§5 build order) |
| `cli/OrchestratorCli` | ✅ | ✅ implemented | `run`, `resume`, `replan`, `status`, `validate`, `metrics`; packaged as `target/orchestrator-cli.jar` |

189 tests, all passing (`mvn test`); `mvn verify` also passes the 70% jacoco
coverage gate on `com/sdlc/orchestrator/**` (trivially, at this stage — most of
what exists is logic-bearing model/algorithm code, not yet the harder-to-cover
executor plumbing).

**Verified about `gates/`**: every one of the 24 distinct condition `type` values
actually used across `docs/orchestration-graph.yaml`'s entry/exit gates has a
registered checker (`AllCheckersRegisteredTest` loads the real graph and asserts
this) — no node in the real graph would hit `UnknownCheckerException` at gate
evaluation time.

**Verified about the graph** (computed by `GraphLoader`/`GraphAlgorithms` from the
**real** `docs/orchestration-graph.yaml` — `GraphLoaderTest.theRealOrchestrationGraphYamlLoadsAndValidates`
loads it end to end — and unchanged by the language port, since the graph is data
and nothing in it is Python-specific):

- 7 nodes, 7 edges, **acyclic** — Kahn emits all 7.
- Topological order: `requirements → design → apply_migration → implementation →
  documentation → testing → release_readiness`.
- Execution waves: `[requirements] [design] [apply_migration] [implementation]
  [documentation, testing] [release_readiness]`.
- The `can_run_parallel` assertions hold: `testing` and `documentation` share wave 4.
- Human-gated nodes: `apply_migration`, `release_readiness`.
- Backoff for `implementation`'s default retry policy: attempt 2 → 2.0s,
  attempt 3 → 4.0s, attempt 4 → 8.0s.

---

## Build order

Dependency-ordered. Each step is testable before the next begins, which matters
because the early steps are the ones everything else trusts. Write tests
alongside each step (JUnit 5), not at the end.

### 1. `pom.xml`, package skeleton, `.gitignore`

Java 21, Spring Boot 3.3+, no `--enable-preview` anywhere (DEC-0013). *Done
when:* `mvn compile` is green with the package layout in place and no code yet.

### 2. `model/` — records and enums

Pure data, no behaviour. `NodeState` (11 states — see the design in
`CLAUDE-CODE-PROMPT.md` §3.1), `RetryPolicy` (Temporal's five fields — §3.2),
`Gate`/`GateCondition`, `RollbackAction`, `RePlanTrigger`, `NodeSpec`,
`GraphSpec`, `Artifact` (SHA-256 content hash — §3.9), `ApprovalRecord`,
`AttemptRecord`, `NodeRuntime`, `Run`.

*Done when:* every record/enum is constructible and unit-tested in isolation —
`RetryPolicy.backoffFor()` reproduces 2.0s/4.0s/8.0s, `NodeState` predicates
(`isTerminalSuccess`/`isTerminalFailure`/`isActive`) are correct, `Artifact`
hashing is stable under key-order-independent input.

### 3. `graph/GraphLoader` + `GraphValidator` — *start here for graph correctness*

Parse YAML (snakeyaml) → model objects, then run all nine validation checks:

1. `schema_version` matches the expected value.
2. Node ids are unique.
3. Every `depends_on` names an existing node.
4. The edge set is **acyclic** (Kahn; see `GraphAlgorithms.topologicalOrder`).
5. Every `produces` key exists in the artifact registry.
6. Every `re_plan_triggers.artifact` names a registered artifact.
7. `can_run_parallel` claims agree with the computed frontier (DEC-0011).
8. `requires_human_approval` agrees with the DEC-0006 policy criteria — a node
   meeting a high-impact criterion may not declare `false`.
9. Every node with a schema-changing action declares a `rollback_action`.

**Fail-closed**: collect *every* failure and throw once, rather than stopping
at the first — fixing a graph one error per run is needless friction.

*Done when:* `GraphValidator` rejects a graph with a cycle, a dangling
`depends_on`, an unregistered artifact, a false `requires_human_approval` on a
high-impact node, and a bogus `can_run_parallel` claim — each named in the
thrown `GraphValidationException`'s message list.

### 4. `graph/GraphAlgorithms`

`topologicalOrder()`, `parallelFrontiers()`, `validateParallelismClaims()`,
`criticalPath()`. **Pure functions — no executor, no I/O, no concurrency.**
Unit-test them hard; this is where hand-rolled orchestrators actually break.

*Done when:* the four computed results match the "Verified about the graph"
list above, and a deliberately cyclic fixture raises `GraphValidationException`
naming the cycle.

### 5. `gates/` — registry and generic checkers

Implement `CheckerRegistry` dispatch plus the generic checkers: `input_present`,
`artifact_present`, `field_non_empty`, `upstream_state`, `upstream_state_in`,
`conditional_required`.

⚠️ **`conditional_required` must use a restricted expression evaluator, never
a scripting engine (Nashorn, Groovy, full SpEL).** The graph is a data file
defining a sandbox; arbitrary code execution from it would let a graph edit
escape the sandbox the graph exists to define.

*Done when:* each checker passes and fails correctly against a synthetic `Run`,
and an unknown condition type throws rather than silently passing.

### 6. `lineage/DecisionLogger` and `metrics/`

Instrumentation before the loop that calls it, so the executor can be observed
from its first run.

`append()`: open with `StandardOpenOption.APPEND`, write one line, flush. Per-
entry flush is deliberate — buffered lineage lost in a crash would be missing
exactly the entries explaining the crash.

*Done when:* an append-only property test passes across multiple appends, and a
metrics fixture reproduces the numbers in `docs/example-run.md` §4 — including
`successRate` excluding skipped/upstream-failed nodes from the denominator.

### 7. `exec/StructuredScope`, then `exec/GraphExecutor` main loop, then the failure ladder

`runGraph()`, `_compute_ready()`-equivalent, `dispatchFrontier()`,
`executeNode()`, `checkGate()`, `handleFailure()`, `rollback()`, `safeStop()`.

Order within this step: the ready-computation first (join semantics are the
subtle part), then node execution, then the failure ladder.

⚠️ `dispatchFrontier` must route each `StructuredScope.Outcome` independently —
one branch failing must not discard a sibling's completed work (the Java
equivalent of `asyncio.gather(return_exceptions=True)`).

*Done when:* the greenfield trace in `docs/example-run.md` §3 reproduces,
including the retry at `implementation`, and the §7 variant reaches rollback →
safe-stop with `UPSTREAM_FAILED` propagating correctly.

### 8. `gates/GateManager` persist-and-exit + `GraphExecutor.resume()`

The governance-critical step. `writePending()`, `recordDecision()`,
`verifyApprovalStillValid()`, `enterGate()`, `resume()`.

*Done when:* a run pauses, the **process exits 0**, you can reboot, and
`resume` continues from disk — and when mutating an approved artifact between
pause and resume causes the approval to be rejected as stale.

### 9. Re-planning — `markStaleFrom()`

Transitive descendants ∩ declared triggers; invalidate approvals bound to
changed artifacts; **preserve lineage for untouched nodes**.

*Done when:* the §8 variant in `docs/example-run.md` reproduces —
`apply_migration` is *not* staled by a `requirement_spec` change (it declares no
trigger on it), and `preservedNodes` appears in the lineage entry.

### 10. `cli/OrchestratorCli` — implemented

`run`, `resume`, `replan`, `status`, `validate`, `metrics`.

### 11. `agents/` — the six stage agents

**Start with deterministic stub implementations** that produce well-formed
artifacts, so the orchestrator is provably correct before any model call is
introduced. This matters: if agents are stubs, a failing test means the
*orchestrator* is wrong, which is what is actually being built here.

### 12. `shortener/` — the URL shortener

Driven by the orchestrator. See `src/README.md`.

---

## Implementation rules

1. **Virtual threads only for fan-out — no `StructuredTaskScope`, no
   `--enable-preview`** (DEC-0013, superseding DEC-0004's `asyncio`-only rule).
   Fan-out goes through `exec.StructuredScope`, the single point of change if
   `StructuredTaskScope` finalizes.
2. **The executor is the only writer of run state**, and (per DEC-0013) all
   mutation of `Run` happens under one `ReentrantLock`. Agents return
   immutable artifacts; they never touch `Run`. Single-writer is what makes
   the audit trail trustworthy, and on genuinely concurrent virtual threads it
   must now be enforced with a lock rather than assumed from single-threadedness.
3. **Write lineage before state.** An extra lineage entry for a transition
   that did not complete is harmless and self-evident; a missing entry for one
   that did is a hole in the audit trail. Lineage writes are serialized
   through one synchronized appender (DEC-0013).
4. **Atomic state writes** — temp file + `Files.move(..., ATOMIC_MOVE,
   REPLACE_EXISTING)`. A torn `state.json` makes a paused run unresumable,
   losing exactly the state the gate exists to protect.
5. **Never short-circuit gate evaluation.** Evaluate all conditions and report
   all failures, so a retrying agent fixes everything in one pass.
6. **Fail closed.** Unknown condition type, unparseable graph, missing checker
   → throw. A gate that silently skips what it does not understand is worse
   than no gate, because it still looks enforced.
7. **Never execute agent-written code in-process.** Compile/import checks run
   in a subprocess — otherwise the thing being governed runs inside its
   governor.

---

## Where the design lives

| Question | Answer |
|---|---|
| What does a node look like? | `docs/orchestration-graph.yaml` |
| Why a custom executor? | `docs/decision-log.md` DEC-0003 |
| Why gates at node boundaries? | DEC-0007 |
| Why virtual threads, not `asyncio`/threads/`StructuredTaskScope`? | DEC-0013 (supersedes DEC-0004) |
| Why Java, not Python? | DEC-0012 (supersedes DEC-0002) |
| What counts as high-impact? | DEC-0006 |
| What should a run produce? | `docs/example-run.md` |
| What must tests assert? | `docs/example-run.md` §9 (18 assertions) |
| What are the known weaknesses? | `docs/architecture.md` §7–8 |

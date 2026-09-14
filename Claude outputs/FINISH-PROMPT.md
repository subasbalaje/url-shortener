# Claude Code — Finish The Build (steps 7–14)

> Paste everything below "PROMPT BEGINS" into your existing Claude Code session.

---

## PROMPT BEGINS

Steps 1–6 are done: scaffold, model, graph engine, and all 24 gate checkers, 189 tests
green. Now finish the entire project. **Do not stop to ask "shall I continue?" between
steps** — work straight through steps 7–14 below. Stop only for (a) a real gate under
`CLAUDE.md` §2, or (b) a genuine blocker you cannot resolve by stating an assumption.

Re-read `docs/example-run.md` before you start. It is the specification for everything
that follows — §3 is the happy path, §7 is the failure ladder, §8 is re-planning, and §9
is your 18-assertion acceptance list. Every number in it was computed from the graph, not
invented; your implementation must reproduce them.

**Definition of done for this whole run:** `mvn clean verify` passes from a clean checkout,
all 18 assertions from §9 are covered by real tests, `docs/example-run.md`'s greenfield
trace reproduces when actually executed, the URL shortener runs and serves requests, and
no file in the repo claims something works that does not. When you hit that, write the
Phase 9 engineering summary (step 14) and tell me the project is complete.

---

## STEP 7 — Lineage and metrics

### 7a. `lineage/DecisionLogger`

Append-only, enforced **structurally**: always open with `StandardOpenOption.APPEND`, and
provide no update or delete method at all. The class simply offers no way to rewrite history.

- `synchronized` (or a single lock) around the write — single-writer must hold under virtual
  threads, which are genuinely concurrent unlike the asyncio model this replaced (DEC-0013).
- Flush per entry. Deliberate durability-over-throughput trade: buffered lineage lost in a
  crash would be missing exactly the entries explaining the crash.
- Port the full `EventType` vocabulary from the deleted `decision_log.py` — it is in git
  history and reproduced in `docs/example-run.md` §5. It is deliberately finite and closed:
  a fixed vocabulary makes the trail queryable ("show every rollback across all runs") in a
  way free-text logging never is. Include `APPROVAL_INVALIDATED` — that event is the visible
  proof that approval binds to content rather than to a node name.
- Every entry carries the `runId` / `nodeId` / `attempt` correlation triple (W3C Trace
  Context *shape*, not HTTP propagation — we are single-process).
- `logDecision(decision, rationale, ...)` **requires a non-empty rationale**. Throw if it is
  blank. A decision logged without *why* satisfies the letter of an audit trail while losing
  its entire purpose.
- `logReplan(...)` records `preservedNodes` **as well as** `staledNodes` — that is the
  positive evidence that re-planning preserved governance rather than resetting the run.
- `verifyAppendOnly(path, previousSnapshot)` — returns whether the snapshot is still a strict
  prefix. This makes "append-only" a **testable property** rather than a docstring claim.
- `readLineage` tolerates a torn final line (crash mid-append) by skipping it. Recoverable
  partial evidence beats an unreadable file.
- Also append narrative entries to `docs/decision-log.md` for reviewer-relevant decisions
  only — not every state transition. A decision log nobody reads is as useless as none.

### 7b. `metrics/MetricsCollector`

The four definitions, each of which is easy to compute in a way that quietly misleads:

- **successRate** = `nodesCompleted / nodesAttempted`. `nodesAttempted` **excludes** SKIPPED
  and UPSTREAM_FAILED. Test with the §7 fixture: 2 completed, 1 failed, 3 upstream-failed,
  1 skipped → **0.5**, *not* 2/7. Counting upstream-failed nodes as failures would multiply
  one root cause into several and make the metric actively lie.
- **retryFrequency** / **rollbackFrequency** — rates over `nodesAttempted`, not raw counts,
  so runs of different sizes stay comparable.
- **mttrSeconds** — mean time to **recovery** specifically. Clock starts on the **first**
  entry to FAILED (never reset by later attempts — time-to-recovery measures the whole
  episode) and stops on COMPLETED-via-retry or rollback completion. `null` when there were
  no failures, **not `0.0`** — zero would falsely imply instant recovery from failures that
  never happened. Javadoc must name which of the four MTTRs this is.
- **endToEndSeconds** with the breakdown: `timeRunning` / `timeBlockedOnGate` /
  `timeRetrying`. Gate wait must never be merged into run time, or the orchestrator looks
  slow because a human went to lunch.
- `parallelEfficiency` = criticalPath / wallClock.
- `updateCumulative` — withhold percentiles below 10 runs. Publishing a p99 from six samples
  invites confident conclusions from noise.

`on_gate_resumed` spans a **process exit**, so the blocked duration must be computed from
persisted timestamps, not an in-memory timer. The process that started the wait is gone.

Finalisation runs on **both** success and failure paths (Argo's exit-handler pattern).
Observability that only works when things go well is absent exactly when needed.

---

## STEP 8 — StructuredScope and the executor main loop

`exec/StructuredScope` per the snippet you already have: virtual threads, failures do **not**
cancel siblings (the Java equivalent of `return_exceptions=True`). A testing failure must not
discard completed documentation work.

`GraphExecutor`:

- All mutation of `run` under `stateLock`. The executor is the **only** writer of run state —
  single-writer is what makes the audit trail trustworthy. Agents return immutable results;
  they never touch `Run`.
- `computeReady()` — join semantics, and this is where hand-rolled executors actually break.
  Two rules: **no partial-input starts** (a node consuming three artifacts never begins having
  seen two), and **failure propagates rather than hangs** (a dependency terminally failed →
  UPSTREAM_FAILED, not an infinite wait). SKIPPED counts as satisfied.
- `executeNode()` — condition → entry gate → RUNNING → agent under timeout → **exit gate** →
  commit → COMPLETED. Exit-gate failure is a *node* failure and retries. That is what makes
  COMPLETED mean "passed its gate" rather than "the agent returned something".
- `checkGate()` — evaluate **all** conditions, never short-circuit, so a retrying agent gets
  the complete list and fixes everything in one pass.
- `FailureLadder` — non-retryable? → rollback. Attempts left? → RETRYING with backoff.
  Rollback defined? → compensate. Else → safe-stop. **If rollback itself fails, stop.** Do
  not retry it or improvise: compensation failing means our model of system state is wrong,
  and acting further on a wrong model is how a small incident becomes a large one.
- `onPersistentFailure` for the testing node — persistent test failure usually means the
  *code* is wrong, not that tests are flaky. Mark `implementation` STALE and push work
  upstream instead of burning retries on a node correctly reporting a real problem. Bounded
  by `maxFeedbackLoops`.
- `safeStop()` — persist, lineage, finalise metrics, write `safe_stop_report.md`, exit 1.
  Deliberately **not** "continue without it": proceeding past an unrecoverable node would
  produce a readiness verdict based on work that never happened.

---

## STEP 9 — Gate persist-and-exit and resume

The governance-critical step. Get this right and the whole project's thesis holds.

`GateManager.writePending()` writes a file **for a human to read**: what am I approving, why
is approval required, what exactly will happen, how do I respond. Include `artifactRef` —
the content hashes being approved.

`enterGate()` → BLOCKED_ON_GATE, run → AWAITING_APPROVAL, write `pending_approval.json`,
persist `state.json`, **return control so the process exits 0**. Nothing sleeps or polls.

`Run.save()` is **atomic**: temp file then `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)`.
A torn `state.json` makes a paused run unresumable — losing exactly the state the gate exists
to protect.

`resume()`: load state → read `approval.json` → **verify every `artifactRef` hash still
matches** → on mismatch the approval is **void** and the node is re-gated → on rejection,
record and safe-stop (a rejected gate is a legitimate terminal outcome, not an error) → on
approval, continue the main loop.

The hash verification is what makes approval binding meaningful. Without it a re-planned
artifact silently inherits approval granted for its predecessor.

Require a non-empty `comment` even on approval. The rationale is the audit-relevant part; an
approval with no reasoning is a click, not a decision.

---

## STEP 10 — Dynamic re-planning

`markStaleFrom(changedArtifact)`:

1. Find the producing node.
2. Take **transitive descendants**.
3. **Intersect** with nodes declaring a matching `re_plan_triggers` entry. Not every
   downstream node cares about every upstream change; blanket invalidation discards valid work.
4. Mark that set STALE.
5. **Invalidate approvals whose `artifactRef` covers the changed artifact.**
6. **Preserve lineage for every untouched node.**

Steps 5 and 6 are the governance requirements — "re-plan *while maintaining governance*" is
the exact assignment wording. Bounded by `maxReplanCycles` (3) → safe-stop.

The test that proves this works: a `requirement_spec` change must **not** stale
`apply_migration`, because it declares a trigger on `migration_plan` only. Get that wrong and
you re-run an already-approved destructive migration.

---

## STEP 11 — CLI

`cli/OrchestratorCli` with `run`, `resume`, `replan`, `status`, `validate`, `metrics`.
Exit codes: **0** on success *and on pausing at a gate* (a pause is not a failure), **1** on
safe-stop, **2** on invalid graph.

Match the output format in `docs/example-run.md` §1 and §3.6 — the `[load]` validation lines,
the pause message naming the three next actions. Reviewers will compare.

---

## STEP 12 — The six stage agents

`agents/StageAgent` interface + six implementations.

**Build deterministic stub agents first.** They read their inputs and emit well-formed
artifacts satisfying their exit gates — no model calls. This is not a shortcut, it is the
correct order: if the agents are deterministic, a failing test means the **orchestrator** is
wrong, which is the thing you are actually building. Non-deterministic agents would make
every orchestrator bug unreproducible.

Each agent's contract, including what gets it **rejected**, is in `docs/architecture.md` §6.
Honour the autonomy boundaries: the design agent may *propose* a migration but never execute
one; the implementation agent *writes* migration SQL but never runs it.

Make the stubs configurable enough to drive the test scenarios — a mode where
`implementation` fails its exit gate on attempt 1 and passes on attempt 2 (the worked trace),
and a mode where `requirements` emits a silently-resolved spec that its gate rejects (the
ambiguous scenario).

If you wire a real model-backed agent, put it behind the same interface and keep the stubs as
the default for tests. Never let tests depend on a live model call.

---

## STEP 13 — The URL shortener

Full spec in `src/README.md`. Non-negotiables:

- `SecureRandom`, never `java.util.Random`. 7-char base62, UNIQUE constraint, bounded
  collision retry (5, then throw). Random over a counter because sequential codes are
  enumerable and unpredictability cannot be retrofitted.
- **302, not 301**, with `Cache-Control: no-store`. A cached 301 means later clicks never
  reach the origin — destroying click analytics *and* keeping a disabled link alive.
  Expired/disabled → **410 Gone**; unknown → 404.
- URL validation: scheme allowlist; block loopback, RFC1918, link-local, and specifically
  `169.254.169.254`; **resolve DNS via `InetAddress.getAllByName()` and validate every
  resolved IP** — a public hostname resolving to `127.0.0.1` otherwise walks straight
  through; no redirect-following during validation; reserved aliases; self-shortening
  prevention.
- Plain JDBC behind `LinkStore`. Try-with-resources everywhere, parameterised queries only.
- Rate limiting: token bucket per key/IP, stricter on create than redirect, 429 +
  `Retry-After`.
- `Idempotency-Key` on create — replay returns the original, never a second link.
- springdoc OpenAPI generated from the code.

Then **run the three scenarios for real**:

- **Greenfield** — full graph, `apply_migration` SKIPPED, 1 human gate, produces the
  shortener. Must reproduce `docs/example-run.md` §3.
- **Brownfield** — add click analytics. `apply_migration` **runs and gates**, 2 gates total,
  impact analysis mandatory. Click recording is **fire-and-forget**: dispatched after the
  response is prepared, never awaited, so analytics failure cannot fail a redirect. **No raw
  IP persisted** — derive country at write time, truncate the address.
- **Ambiguous** — "make it better, faster, safer". The `requirements` exit gate must
  **actually reject attempt 1**, then escalate. Per `scenarios/ambiguous.md`.

Capture the real `runs/<run_id>/` output from each. If reality differs from
`docs/example-run.md`, **update the doc to match reality and say so** — never fudge the run
to match the doc.

---

## STEP 14 — Finish and verify

1. **`mvn clean verify` from scratch.** All green, jacoco ≥70% on orchestrator packages.
2. **All 18 assertions** from `docs/example-run.md` §9 covered by real tests. The ones most
   easily faked, so do them properly:
   - **#9** — discard the executor instance **entirely** before resuming, or the test would
     pass for a `Thread.sleep()`.
   - **#10** — mutate an approved artifact between pause and resume; approval must be rejected.
   - **#14** — the 0.5 fixture, not 2/7.
   - **#16/#17** — `apply_migration` not staled by a `requirement_spec` change; lineage
     preserved byte-identically for untouched nodes.
   - **#18** — snapshot, append, assert strict prefix.
3. **Docs match reality.** Sweep `README.md`, `orchestrator/README.md`, `src/README.md`,
   `tests/README.md`, `docs/architecture.md`, `docs/example-run.md` and all three scenarios.
   Remove every "TODO", "not yet implemented", "skeleton" and "specified and scaffolded" that
   is no longer true — and **leave the ones that still are**. Update
   `orchestrator/README.md`'s status table to reflect actual state.
4. **Verify no false claims.** Grep for "implemented", "working", "complete". Every such
   claim must be true. This is `CLAUDE.md` §6 and it is the single easiest way to lose
   credibility with a reviewer.
5. **Append final decision-log entries** for anything nontrivial decided during steps 7–14.
6. **Write `docs/engineering-summary.md`** — the assignment's Requirement 8, and a graded
   deliverable. Cover:
   - **Plan and rationale** — what was built and why, the orchestration model in graph terms.
   - **Artifacts** — every file, one line each.
   - **How each of the 8 core requirements is met**, pointing at specific files and tests.
     Req 4 is the critical differentiator; be concrete about where the graph, gates,
     parallelism, retries, lineage, metrics and re-planning actually live.
   - **Risks, trade-offs and failure modes** — carry `docs/architecture.md` §7 forward,
     updated for what you learned building it.
   - **Validation approach** — test strategy, what the 18 assertions prove, and **what is
     deliberately not tested** (agent output quality, load/scale, mid-node crash durability).
   - **Assumptions** — every one, starting with DEC-0001 (the "from scratch" vs "complete and
     improve" contradiction) and DEC-0012/0013 (the Java switch and the concurrency-argument
     replacement).
   - **Limitations** — honestly. No crash-durability mid-node, single process, no auth on
     approvals, metrics thin until enough runs, exit gates catch structural not subtle
     failures, `api_contract_conformance` is presence-only.
7. **Do not commit or push.** Stage nothing. Report back with: `mvn verify` output summary,
   test count, coverage, the 18 assertions mapped to test names, the three scenario run
   outputs, anything you could not finish and why.

---

## RULES (unchanged, and they still bind)

- **`docs/decision-log.md` is append-only.** Never edit an entry; reverse by appending.
- **Never weaken a test to make the suite pass.** No `@Disabled` for green, no relaxed
  assertions. You are implementing a `noTestWeakening` gate — hold yourself to it.
- **Never claim something works when it is a stub.**
- **Fail closed** — unknown gate type, unparseable graph, missing checker → throw.
- **Never execute agent-written code in-process** — subprocess only.
- **Stop and ask** before deletions, schema changes, commits, pushes, or anything
  irreversible.
- If you hit something genuinely ambiguous, **name the assumption in the decision log and
  keep going** rather than stopping.

Work straight through. Report at the end.

## PROMPT ENDS

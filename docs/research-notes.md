# Research Notes

**Purpose.** This document is the evidence base for the architecture decisions in
`docs/architecture.md` and the graph design in `docs/orchestration-graph.yaml`. It is
deliberately *not* a literature survey. Every section ends with **"What we borrow"** —
the specific, named pattern that survives into our build, and what we explicitly reject.

The bias throughout: we are building a **solo, explainable-in-an-interview** orchestrator.
A pattern is only worth borrowing if it can be implemented in a few hundred lines of
owned Python and defended line-by-line under questioning. Patterns that are excellent but
only make sense at distributed-systems scale are recorded as *rejected with reason* —
because knowing why you didn't use Temporal is itself a defensible engineering position.

Researched: 2026-09-13.

---

## 1. Real DAG / workflow orchestration engines

### 1.1 Temporal — durable execution

**Model.** Temporal separates *Workflows* (deterministic orchestration code) from
*Activities* (non-deterministic side-effecting work). Durability comes from **event
sourcing**: the workflow's every decision is appended to an event history, and after a
crash the workflow is replayed from that history to reconstruct in-memory state. The
workflow code is not "resumed" so much as *re-derived*.

**Retries.** Temporal's retry policy is the most complete model surveyed, and is worth
copying almost verbatim as a schema:

| Field | Default | Role |
|---|---|---|
| `initial_interval` | 1s | delay before first retry |
| `backoff_coefficient` | 2.0 | multiplier per attempt (exponential) |
| `maximum_interval` | 100× initial | ceiling, stops unbounded backoff growth |
| `maximum_attempts` | unlimited | total tries; `1` = no retry |
| `non_retryable_error_types` | — | error types that skip retry entirely |

The critical conceptual contribution is the **three-way failure taxonomy**: *transient*
and *intermittent* failures are worth retrying; *permanent* failures are not, and retrying
them burns time and obscures the real problem. Non-retryable error types make that
distinction **declarative rather than implicit**.

**Rollback.** Temporal has no built-in rollback primitive. Compensation is implemented as
the **Saga pattern** — the workflow records a compensating action for each completed step
and, on failure, invokes them in reverse order. Rollback is ordinary workflow code, not
engine magic.

**Human approval.** Implemented with **Signals** — an external event delivered into a
running workflow. The workflow blocks on a condition awaiting the signal; because the
workflow is durable, it can wait days or months at zero cost. Combined with timers, this
yields "wait for approval, or escalate after 48h."

**What we borrow:** the retry policy *schema* (all five fields), the transient/permanent
failure taxonomy, and Saga-style **explicit per-node compensating actions** declared as
data. **What we reject:** event-sourcing/replay determinism — it is the right answer for
distributed durability and the wrong answer for a single-process interview artifact, where
it would add a large, hard-to-explain constraint (every workflow function must be
deterministic) for benefit we do not need.

### 1.2 Apache Airflow — scheduler-driven DAGs

**Model.** A DAG of Tasks; the scheduler repeatedly asks "whose dependencies are now
satisfied?" and queues those. This is the **ready-queue** model, and it is exactly the
executor loop we want.

**Task states** (the richest state machine surveyed, and near-directly reusable):

`none` (deps unmet) → `scheduled` (deps met) → `queued` → `running` → `success` / `failed`;
plus `up_for_retry` (failed, attempts remain), `upstream_failed` (a dependency failed and
the trigger rule required it), `skipped`, `deferred`, `up_for_reschedule`, `restarting`,
`removed` (vanished from the DAG mid-run), and — notably — **`awaiting_input`: "The task
is a Human-in-the-loop task waiting for a human response."**

That last state is significant: a mainstream production scheduler models *waiting on a
human* as a **first-class task state**, not as a side-channel. This directly validates
making `BLOCKED_ON_GATE` a real state in our node state enum rather than a boolean flag.

**Trigger rules.** Airflow decouples "are my dependencies *done*" from "are my dependencies
*successful*" (`all_success`, `all_done`, `one_failed`, `none_failed_min_one_success`, …).
This is the general form of join semantics.

**What we borrow:** the ready-queue scheduler loop; the state enum shape, including a
dedicated human-wait state and a distinct `upstream_failed`-style propagation concept.
**What we reject:** trigger-rule generality — we need exactly one join rule
(`all_success`) for the SDLC graph, and shipping a configurable trigger-rule engine would
be unused surface area. Recorded here so the omission is a *choice*, not an oversight.

### 1.3 Dagster — assets and staleness

**Model.** Dagster's unit is the **software-defined asset** — "an object in persistent
storage… a description, in code, of an asset that should exist and how to produce and
update it" — rather than a task. The documented distinction is sharp: *"asset definitions
know about their dependencies, while ops do not."* Running an asset's function and
persisting the result is **materialization**.

**Why this matters to us.** Because assets know their dependencies and each materialization
is recorded, Dagster can reason about **staleness**: when an upstream asset is
re-materialized, downstream assets are known to be out of date and can be
**re-materialized selectively**.

This is precisely the mental model for the assignment's hardest requirement — *"dynamically
re-plan when upstream outputs change"*. The insight we take: **re-planning is a staleness
problem, not a restart problem.** If a node is modeled as "produces this artifact," then an
upstream change invalidates a computable *sub-graph* — the transitive descendants — and
everything else keeps its completed state and its recorded lineage.

**What we borrow:** artifact-producing nodes, and **staleness propagation over the
transitive closure of descendants** as the re-planning mechanism (our `STALE` state).
**What we reject:** Dagster's full asset catalog / IO-manager abstraction.

### 1.4 Prefect — dynamic flows

Tasks are decorated Python functions composed at runtime, so the graph can be shaped by
data. Retries and caching are task decorator parameters. Prefect makes the trade explicit:
**runtime-dynamic graphs are expressive but cannot be statically inspected** before running.

For us that trade resolves decisively toward **static**: the assignment demands an
*"explicit dependency graph"* that is inspectable and auditable, and a graph declared as
YAML can be read, diffed, reviewed and verified before a single node executes. A graph that
only exists once Python has run cannot be reviewed in advance. **What we borrow:** the
argument itself, as justification for a declarative YAML graph.

### 1.5 LangGraph — stateful LLM agent graphs

Closest in spirit to our system, since our nodes are LLM-driven. Model: a `StateGraph`
where nodes are functions mutating typed shared state, edges may be conditional, and a
**checkpointer** persists state per `thread_id`.

**Human-in-the-loop** uses `interrupt()`. Mechanics, from the docs: *"When you call
`interrupt` within a node, LangGraph saves the current graph state and waits for you to
resume execution with input."* It *"saves the graph state using its persistence layer and
waits indefinitely."* Resume happens by re-invoking with `Command(resume=...)`, and the
resume value becomes the return value of the `interrupt()` call.

**The critical gotcha**, stated plainly in the docs: *"the node restarts from the beginning
of the node where the `interrupt` was called when resumed, so any code before the
`interrupt` runs again."* Hence operations before an interrupt must be **idempotent**.

**What we borrow:** persist-state-then-block-then-resume-by-external-input; and a design
rule the gotcha forces — **our gates sit on node boundaries (between nodes), never
mid-node.** That single decision means a node never partially executes before pausing, so
we get no re-execution hazard and need no idempotency caveat. This is a case where reading
another system's documented sharp edge let us design the edge away entirely.

### 1.6 Camunda / BPMN — user tasks and change control

BPMN's contribution is vocabulary: a **User Task** is a first-class node type that pauses
the process and lands in a human worklist with an assignee, form and audit record; gateways
model parallel split/join explicitly; a **Compensation Event** attaches undo semantics to
an already-completed activity.

**What we borrow:** a gate is **a property of a node**, and an approval record is a
first-class artifact (who, when, decision, comment), not a log line. **What we reject:** the
BPMN XML spec surface.

### 1.7 Argo Workflows — DAG on Kubernetes

Steps are containers; dependencies declared via `depends`/`dependencies`; retry via
`retryStrategy` with backoff; `exit handlers` run on completion regardless of outcome.
Nodes are pods, so state lives in the cluster. **What we borrow:** the exit-handler idea —
a hook that always runs — which we reuse as the guaranteed metrics/lineage write on both
success and failure paths. **What we reject:** everything container-scheduling-related.

### 1.8 AWS Step Functions — declarative state machine

A JSON/ASL state machine, and the most **declarative** error model surveyed.

`Retry` is an array of *retriers* (`ErrorEquals`, `IntervalSeconds`, `MaxAttempts`,
`BackoffRate`, `MaxDelaySeconds`, `JitterStrategy`). `Catch` is an array of *catchers*
(`ErrorEquals`, `Next`, `ResultPath`) routing to a fallback state.

**Ordering is the key lesson** — the documented interaction is: the state fails → `Retry` is
evaluated first → if retries are exhausted or none match, `Catch` is evaluated → if no
catcher matches, the execution fails. That is exactly the escalation ladder the assignment
asks for: **bounded retry → fallback/rollback → safe-stop.** Step Functions also supplies
predefined error names (`States.ALL`, `States.Timeout`, `States.TaskFailed`) and recommends
`JitterStrategy: FULL` against thundering herd.

**What we borrow:** the **retry-then-catch-then-fail ordering as our failure ladder**, and
declaring both as *data on the node* rather than as code. **What we reject:** ASL syntax.

### 1.9 The 4–5 patterns that survive into our build

1. **Declarative graph-as-data** (Step Functions, Airflow, Argo) — nodes, edges, retry,
   rollback and gates are YAML, inspectable before execution. Directly serves "explicit
   dependency graph."
2. **Ready-queue scheduler over a topological order** (Airflow) — the executor loop.
3. **Retry policy schema + failure taxonomy** (Temporal) — five fields, and
   transient-vs-permanent as a declared property.
4. **Failure ladder: retry → compensate/rollback → safe-stop** (Step Functions ordering +
   Temporal Saga compensation).
5. **Staleness propagation for re-planning** (Dagster) — upstream change marks the
   transitive descendant set `STALE`; untouched nodes keep state *and lineage*.

Plus one design rule derived from LangGraph's documented gotcha: **gates on node
boundaries, never mid-node.**

**Sources:** [Temporal retry policies](https://docs.temporal.io/encyclopedia/retry-policies) ·
[Temporal design patterns](https://dzone.com/articles/temporal-workflow-design-patterns) ·
[Airflow tasks & states](https://airflow.apache.org/docs/apache-airflow/stable/core-concepts/tasks.html) ·
[Airflow DAGs](https://airflow.apache.org/docs/apache-airflow/stable/core-concepts/dags.html) ·
[Dagster assets](https://docs.dagster.io/guides/build/assets/) ·
[LangGraph interrupts](https://docs.langchain.com/oss/python/langgraph/interrupts) ·
[LangChain HITL blog](https://www.langchain.com/blog/making-it-easier-to-build-human-in-the-loop-agents-with-interrupt) ·
[Step Functions error handling](https://docs.aws.amazon.com/step-functions/latest/dg/concepts-error-handling.html)

---

## 2. Graph theory as applied engineering

### 2.1 Topological sort = execution ordering

A topological ordering of a DAG is a linear order where every edge `u → v` places `u`
before `v`. That is the definition of a valid execution order.

**Kahn's algorithm** is the right choice here, over DFS-based sorting, for one practical
reason: it *is* the scheduler. Compute in-degree for every node; seed a queue with all
in-degree-0 nodes; pop, emit, decrement successors' in-degrees, enqueue any that reach 0.

Two properties make it the correct engineering pick:

- **Cycle detection falls out for free.** If the algorithm terminates having emitted fewer
  nodes than the graph contains, the remainder are in (or downstream of) a cycle. One
  algorithm, two requirements — validation and ordering.
- **The queue at any moment is exactly the set of runnable nodes.** Kahn's "in-degree 0"
  frontier *is* the ready-queue. Every node in it is mutually independent by construction,
  so **the frontier is the parallelism**. We don't need a separate parallelism analysis;
  the sort hands it to us.

We keep `can_run_parallel` in the YAML anyway — as a **declared expectation checked against
computed reality**, so a graph edit that silently serializes an intended-parallel pair is
caught by validation rather than discovered in a latency metric.

### 2.2 Cycle detection, and why bounded retry is not a cycle

An illegal cycle is a dependency loop (`A → B → C → A`): no topological order exists, no
node can start, the graph is unrunnable. This must be **rejected at load time**.

A **bounded retry is not a cycle.** The distinction is precise and worth stating in exactly
these terms:

- A cycle is an edge in the **dependency graph**. A retry is a repeated execution of **one
  node**, with no edge involved.
- A cycle is unbounded and has no termination argument. A retry has a **monotonically
  increasing attempt counter and a `max_attempts` ceiling**, which is a termination proof.
- Formally: retry is a **self-loop in the node's internal state machine**
  (`RUNNING → FAILED → RETRYING → RUNNING`), not an edge in the inter-node DAG. The DAG
  stays acyclic; the state machine is allowed to loop because it provably terminates.

So: cycle detection runs on the **edge set only**, and never sees retry at all. The two
live in different structures. (Same reasoning covers re-planning: `STALE` re-execution
re-runs nodes along existing edges, adding none.)

### 2.3 Fan-out / fan-in and join barriers

**Fan-out**: one node, several dependents → they become ready together. **Fan-in**: one
node depending on several → a **join barrier**, ready only when *all* named dependencies
have succeeded.

The join is where correctness usually breaks. Two rules we enforce:

- **All-success by default.** A join fires only when every dependency is `COMPLETED`. If a
  branch fails terminally, the join must not fire — it propagates `upstream_failed`
  (Airflow's term) rather than running with a missing input.
- **No partial-input starts.** A node that consumes three artifacts never starts having
  seen two. This is the concurrency bug the barrier exists to prevent.

Our concrete fan-out/fan-in: implementation completes → **testing and documentation run in
parallel** → **release-readiness is the join barrier** requiring both.

### 2.4 Critical path = latency estimate

Weight each node by expected duration; the **critical path** is the longest path from entry
to terminal. With unlimited parallelism, total duration equals the critical path — so it is
the floor on end-to-end latency, and the only place where speeding a node up actually helps.

Computed by relaxing edges **in topological order** (`earliest_finish[v] = duration[v] +
max(earliest_finish[u] for u in deps)`), which is O(V+E) on a DAG — no shortest-path
machinery needed, precisely because a topological order exists.

Two engineering uses: (1) it tells us parallelizing testing and documentation only helps if
neither is on the critical path alone; (2) it gives an **expected** latency to compare
against the **measured** end-to-end latency from §4 — a gap between them is a signal
(unexpected serialization, gate wait time, retries) rather than a number with no baseline.

**What we borrow:** Kahn's algorithm as the shared engine for ordering + cycle detection +
ready-frontier; the self-loop-vs-edge argument as our formal defense of bounded retry;
all-success join barriers; critical path as expected-latency baseline.

---

## 3. Human-in-the-loop approval gate patterns

### 3.1 How real systems pause

- **CI/CD manual approval** (GitHub Environments, GitLab `when: manual`, Jenkins `input`):
  the pipeline reaches a job that will not start until a permitted human approves.
  Crucially, the *pipeline run persists* while waiting — pausing is a normal state, not a
  timeout or a crash.
- **Change management / CAB** (ITIL): a proposed change carries risk classification, impact
  analysis, rollback plan and approver identity; **standard/pre-approved** changes are
  exempt while **normal/major** changes require board review. The pattern to steal is
  **risk-tiering**: not everything needs a human, and saying so precisely is what makes the
  gates credible rather than theatrical.
- **BPMN User Task**: pause, assign, present a form, record the outcome (§1.6).
- **Airflow**: `awaiting_input` as a task state (§1.2).
- **LangGraph**: `interrupt()` → persist → resume via `Command(resume=...)` (§1.5).

### 3.2 How pause/resume actually works

Two implementation families:

**(a) Blocking wait** — the process stays alive holding in-memory state (LangGraph's
in-memory checkpointer, a blocking `input()`). Simple; loses everything if the process dies;
can't survive a lunch break.

**(b) Persist-and-exit** — state is written to durable storage, the process exits, and a
separate invocation resumes from the persisted state. This is what Temporal (event history),
Airflow (DB-backed task state) and production LangGraph (DB checkpointer) all do.

**We choose (b).** The reason is demonstrability: an interviewer can watch execution stop,
**inspect a `pending_approval.json` file on disk, see the run state persisted, record a
decision, and restart the process to watch it resume.** A gate that only exists inside a
live process's memory is indistinguishable from a `time.sleep()`. A gate you can inspect,
kill the process, and still resume is *provably* a gate. Per the project's definition of
done — *"can you point to where in the running system a human gate actually pauses
execution"* — file-on-disk is the strongest available answer.

### 3.3 Recording the decision

Across CAB, BPMN and CI/CD, an approval record consistently carries: **who** (approver
identity), **when** (timestamp), **what** (the decision: approve / reject / approve-with-
conditions), **why** (comment/rationale), and **what was approved** (a reference to the exact
artifact version — so approval cannot be silently transferred to different content).

That last field is the one most often missed and the most important for audit: approving
"the design" is meaningless unless it names *which* design. We therefore bind each approval
to the artifact hash/id it approved.

**What we borrow:** persist-and-exit pause; approval as a structured artifact with all five
fields including artifact binding; risk-tiering so gates are justified per node rather than
sprinkled everywhere.

---

## 4. Observability, audit trails, and reliability metrics

### 4.1 Structured logging and correlation

The baseline pattern: emit **machine-parseable events** (JSON), not prose, with a stable set
of keys. Every event in one run carries the same **`run_id`**; every event from one node
also carries **`node_id`** and **`attempt`**. That triple makes any event locatable and makes
"show me everything that happened to node X on attempt 2" a filter rather than a grep.

**W3C Trace Context** is the standard worth mirroring: `traceparent` =
`version-trace_id-parent_id-trace_flags`; trace-id is 16 bytes / 32 hex chars, span-id 8
bytes / 16 hex chars, **all-zero values are explicitly invalid**, and vendors must reject
malformed ids. We adopt the *shape* (a run-level id ≈ trace id, a node-attempt-level id ≈
span id, parent linkage for causality) without adopting HTTP header propagation, since we
are single-process. Adopting the shape now means a later move to real OpenTelemetry is a
mapping exercise, not a redesign.

### 4.2 Audit trail vs. application log — a distinction worth keeping

They are different artifacts with different rules, and conflating them is a common design
error:

| | Application log | Audit / decision trail |
|---|---|---|
| Answers | "what did the system do" | "**why** was this decided, and by whom" |
| Volume | high, sampled/rotated | low, retained |
| Mutability | rotatable, disposable | **append-only, never edited** |
| Audience | debugging engineer | reviewer, auditor, next session |

The assignment asks for **decision lineage** — that is the right column. Hence the
**append-only** rule on `docs/decision-log.md`: an entry that can be edited after the fact is
not evidence. A superseded decision is corrected by *appending a new entry that references
the old one*, never by rewriting history.

### 4.3 How the four required metrics are actually defined

**Success rate.** Google SRE defines availability as *"the fraction of well-formed requests
that succeed."* Mapped to us: `nodes_completed / nodes_attempted` per run, and runs
completed / runs started cumulatively. The word *well-formed* matters — a node that never
became ready because an upstream branch failed shouldn't be counted as a failure of *that*
node; it is `upstream_failed`, a distinct outcome. Counting it as a failure would triple-count
a single root cause and make the metric lie.

**Retry / rollback frequency.** `total_retry_attempts / nodes_attempted` and
`rollbacks_executed / nodes_attempted`. Deliberately a *rate*, not a raw count, so it stays
comparable across runs of different sizes. A rising retry rate with a flat success rate is
the classic signature of a flaky dependency — the system still works but is working harder,
which is exactly what a reliability metric should surface before it becomes an outage.

**MTTR — and its ambiguity.** MTTR is genuinely four different metrics: mean time to
**repair** (repair start → functional), to **recovery/restore** (failure → operational), to
**resolve** (detection → long-term fix incl. prevention), and to **respond** (alert →
functional). They have different clock starts and are routinely conflated.

Since the assignment just says "MTTR," we must **name which one we mean** rather than pick
silently. We adopt **mean time to recovery**: clock starts when a node first enters `FAILED`,
stops when that node reaches `COMPLETED` (via retry) or its rollback completes and the run
reaches a safe stop. Mean over all failure episodes in the run. Rationale: it is the only one
of the four fully observable from inside the orchestrator — we see the failure and the
recovery, but we cannot see human detection or long-term preventative fixes.

**End-to-end latency.** Wall-clock from run start to terminal state. Reported with a
**breakdown**, because one number hides the story: time in `RUNNING` (real work), time in
`BLOCKED_ON_GATE` (human wait), time in `RETRYING` (backoff). Human wait time can dominate
and is not a system performance problem — merging them would make the orchestrator look slow
because a human went to lunch. Per SRE guidance, **distributions beat averages** (*"Most
metrics are better thought of as distributions rather than averages"*; the classic failure
is a 50 ms typical request where *"5% of requests are 20 times slower"*). With few nodes per
run, percentiles are noisy, so we record **per-node durations** and report `p50`/`max`
cumulatively once enough runs exist — rather than computing a p99 from six samples and
pretending it means something.

**What we borrow:** run/node/attempt correlation triple in W3C-trace-context *shape*;
strict audit-trail-vs-log separation with append-only lineage; the four metric definitions
above **including explicitly naming which MTTR**; latency broken out by state.

**Sources:** [Google SRE — SLOs](https://sre.google/sre-book/service-level-objectives/) ·
[W3C Trace Context](https://www.w3.org/TR/trace-context/) ·
[Atlassian incident metrics](https://www.atlassian.com/incident-management/kpis/common-metrics)

---

## 5. Multi-agent / planner–executor patterns for LLM agents

Our nodes are LLM-driven, so generic workflow-engine patterns are necessary but not
sufficient. The relevant literature separates three concerns:

**Planner.** Decomposes a goal into steps with dependencies. Key distinction: **plan-and-
execute** (plan fully up front, then run) vs **ReAct-style interleaving** (think one step,
act, re-think). Plan-first is auditable and cheap but brittle to surprises; interleaving
adapts but produces no inspectable plan, and an unpredictable trajectory is very hard to
govern. **Hybrid**, which we adopt: plan the graph statically, but allow re-planning at
**declared trigger points**. Adaptivity that is itself governed.

**Executor.** Carries out one step with tools. The consistent lesson: **executors should be
narrow** — a step with one clear job, defined inputs and a checkable output is far more
reliable than an agent told to "build the feature." Our SDLC-stage-per-node granularity
follows this.

**Critic / evaluator.** A separate evaluation step judging the executor's output before it is
accepted — reflection, self-critique, LLM-as-judge. The important structural finding is that
**separating generation from evaluation beats asking one call to do both**: a generator
asked to grade itself is systematically generous.

**The mapping onto our architecture is direct, and is the reason exit gates exist:**

- Planner → the **graph definition** + the re-planner (`STALE` propagation).
- Executor → the **stage agent** at each node.
- Critic → the **exit gate**, which is a *machine-checkable condition on the node's output*.

This is the load-bearing idea of the whole design: **an exit gate is the critic layer
expressed as data.** "Design doc must name every impacted module" is a validation predicate,
not a vibe. It executes the same way every time, it is visible in the YAML, and it is why
a node completing means something. Without exit gates, "COMPLETED" would mean only "the LLM
returned some text."

**Known failure modes we design against:** error compounding across steps (each stage's
sloppiness feeds the next → mitigated by exit gates at every boundary, so errors are caught
at the node that produced them); unbounded autonomy (→ bounded retries + human gates on
high-impact actions); context loss between stages (→ explicit artifact passing + persisted
run state rather than relying on a conversation window).

---

## 6. URL shortener architecture fundamentals

### 6.1 Short-code generation — the three candidates

**(a) Counter + base62.** Monotonic counter, base62-encoded (`[0-9a-zA-Z]`, 62 chars).
*Pros:* collision-free **by construction** — no check, no retry loop, O(1); short codes;
7 chars ≈ 3.5 trillion combinations (at 1,000 URLs/sec that is ~100 years of capacity).
*Cons:* codes are **sequential and therefore enumerable** — anyone can walk `aaaaab`,
`aaaaac` and harvest every link, which is a real privacy leak for "unlisted" URLs. Also
needs coordination if sharded (range allocation via Zookeeper/DynamoDB).

**(b) Hash + truncate.** Hash the long URL (MD5/SHA), base62-encode, truncate to ~7 chars.
*Pros:* deterministic — the same long URL always yields the same code, giving natural
deduplication and idempotency. *Cons:* **truncation reintroduces collisions** (the full
128-bit digest is collision-resistant; its first 7 base62 chars are not), so a
check-and-handle path is still required — you pay the collision cost *and* lose
unpredictability, since the mapping is reproducible by anyone. Determinism is also
sometimes *wrong*: two users shortening the same URL may need distinct codes for separate
analytics.

**(c) Random + collision check.** Draw random base62 (CSPRNG), check for existence, retry on
collision. *Pros:* **unpredictable** (no enumeration), no coordination, trivially shardable.
*Cons:* needs a uniqueness check per insert, and retry probability grows as the space fills
— a birthday-problem effect, though at 3.5e12 capacity and prototype volumes it is
negligible (expected retries ≈ 0).

**Trade-off summary that drives our choice:** the counter's advantage is guaranteed
uniqueness; its cost is enumerability. Random's cost is a uniqueness check that a single
`UNIQUE` index makes nearly free at our scale; its advantage is unpredictability, which is a
*security* property we cannot retrofit later. Hashing's determinism is a feature only if
dedup is desired, and it still needs collision handling.

### 6.2 Redirect path

**301 vs 302 is an analytics decision, not just an SEO one.** A 301 (permanent) is cached by
the browser by default — and the documented consequence is decisive: *"redirection requests
do not reach the server because of the cache on the client side. If the redirection requests
do not reach the server, analytics data cannot be collected."* A 301 therefore silently
destroys click analytics after the first visit per browser, and also makes a link
un-revocable in practice (a cached 301 keeps redirecting after the link is disabled).
**302** (temporary, non-cached by default) preserves both analytics and revocability, at the
cost of an origin hit per click. For a service whose product features are *analytics* and
*disable/expire*, 302 is correct; 307 is the method-preserving equivalent.

**Performance.** Redirect is the hot read path and should be a single indexed lookup with
everything else off the critical path. Standard shape: cache-aside (check cache → miss →
DB → populate), LRU with TTL, exploiting the ~80/20 access skew; analytics writes must be
**asynchronous/fire-and-forget** so recording a click never delays the redirect.

### 6.3 Analytics data modeling

Two shapes: **aggregate counters** (a `click_count` column; cheap, O(1), but answers only
"how many") versus an **event table** (one row per click with timestamp, referrer, user-agent,
country; answers time-series and breakdown questions, but grows unboundedly and needs
retention/rollup). Standard production answer is both: raw events with a retention window,
rolled up into aggregates. **Privacy note:** raw IPs are personal data under GDPR — truncate
or hash them, and derive country at write time rather than storing the address.

### 6.4 Rate limiting, caching, idempotency

**Rate limiting.** Token bucket (allows bursts, smooth refill) or sliding window; applied per
API key/IP on *creation* (the expensive, abusable path) more aggressively than on redirects.
Return **429** with `Retry-After`.

**Idempotency.** Retried POSTs must not create duplicate links. Standard: an
`Idempotency-Key` header, stored with the response, so a replay returns the original result
rather than creating a second short code.

**Expiry / disable.** Expiry is `expires_at` checked on read (return **410 Gone**, which
semantically means "was here, deliberately gone", not 404). Disable is a status flag for
takedowns. Both require the redirect to be uncached — see the 302 argument above.

### 6.5 Custom aliases

User-chosen codes need: a **uniqueness check against generated codes** (one namespace, so a
reservation strategy is needed to prevent a custom alias colliding with a future random
code), **charset/length validation**, a **reserved-word list** (`api`, `admin`, `login`,
`health`, `static` — otherwise a custom alias can shadow a real route), and **profanity /
impersonation screening**.

### 6.6 Abuse prevention

A URL shortener is an **open redirector by design** — it launders a malicious destination
behind a trusted-looking domain, which is why shorteners are a standing phishing vector.
This is a *core* requirement, not a nice-to-have.

OWASP's SSRF guidance applies directly to validating the submitted target URL:

- **Scheme allowlist** — accept only `http`/`https`; reject `file:`, `javascript:`, `data:`,
  `gopher:`.
- **Block internal ranges** — loopback `127.0.0.0/8`, `::1/128`, `0.0.0.0/8`; RFC1918
  `10/8`, `172.16/12`, `192.168/16`; link-local, and specifically the **cloud metadata
  endpoint `169.254.169.254`** (plus `metadata.google.internal`), the classic SSRF target.
- **Resolve DNS and validate the resulting IPs**, not just the hostname string — otherwise a
  public hostname resolving to `127.0.0.1` walks straight through, and beware **DNS
  rebinding** (validate-then-fetch races).
- **Don't follow redirects** during validation — a permitted URL can 302 into a blocked one.
- OWASP is explicit that allowlists beat denylists (*"Do not accept complete URLs from the
  user because URLs are difficult to validate"*); for an open shortener a full allowlist is
  impossible, so we use a **denylist plus resolved-IP validation** and record the residual
  risk honestly rather than claiming it is solved.
- Plus: **self-referential loop prevention** (shortening our own short domain), and optional
  reputation feeds (Google Safe Browsing) as a future hook.

**Sources:** [URL shortening system design](https://systemdesign.one/url-shortening-system-design/) ·
[OWASP SSRF Prevention](https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html) ·
[301 vs 302 in shorteners](https://url-shortening.com/blog/301-vs-302-redirects-in-shorteners-speed-seo-and-caching)

---

## 7. Candidate feature requests for the brownfield / ambiguous scenarios

Six realistic asks a hiring panel might plausibly hand over, scored for how well each
*exercises the orchestrator* — which is the actual selection criterion, since the shortener
is only the test subject.

| # | Request | Type | Exercises | Verdict |
|---|---|---|---|---|
| 1 | "Add click analytics — we want to see where clicks come from." | Brownfield, moderately specified | New table + migration (**schema-change gate**), touches redirect hot path, back-compat on existing links | **Strong** |
| 2 | "Support custom domains for enterprise customers." | Brownfield, large | Routing, DNS, TLS, multi-tenancy — large blast radius, arguably too big for a prototype | Good but heavy |
| 3 | "This one link is suddenly getting 10× traffic and the service is struggling." | Brownfield, incident-flavoured | Caching, rate limiting, hot-key handling; performance validation rather than functional | Strong, but validation is load-testing |
| 4 | **"Make it better / faster / safer."** | **Ambiguous** | Forces the Requirements Agent to surface ambiguity, enumerate interpretations, and get a human decision *before* any decomposition | **Strongest ambiguous candidate** |
| 5 | "Add an expiry feature so links can be temporary." | Brownfield, well-specified | Schema change, read-path check, 410-vs-404 semantics — clean but small | Moderate |
| 6 | "We need to be able to take down a link if it's reported as malicious." | Brownfield, compliance-flavoured | Status flag, admin path, **policy guardrail + human approval on takedown**, interacts with caching/301 | Strong |

**Selections for Phase 6.**

- **Brownfield → #1, "add click analytics."** Chosen because it is the only candidate that
  hits a **database migration** (triggering the schema-change human gate — Req 7 visible in
  the running system), **modifies the hot redirect path** (forcing real impact analysis
  across modules — Req 3), and **needs back-compat reasoning** for links created before the
  feature. It also demonstrates the orchestrator's most interesting behaviour: a brownfield
  run **skips or short-circuits nodes** that a greenfield run must execute, so the two
  scenarios produce visibly different graph traversals rather than the same story retold.

- **Ambiguous → #4, "make it better/faster/safer."** Chosen because it is genuinely
  under-specified along **three different axes at once** ("better" = UX? reliability?;
  "faster" = redirect latency? creation throughput?; "safer" = abuse? authn? data
  protection?), with no way to proceed without either a human decision or an explicitly
  stated assumption. That is precisely the behaviour Req 1 grades, and it lets the
  Requirements Agent's exit gate **reject** an under-specified output — demonstrating that
  exit gates actually bite.

- **Greenfield** is the core service build (create + redirect + storage + tests + docs),
  covered in `scenarios/greenfield.md`.

Candidates #2, #3, #5 and #6 are recorded here as a **backlog** — useful both as future
demonstrations and as evidence that the chosen scenarios were *selected against criteria*
rather than being the only ones we thought of.

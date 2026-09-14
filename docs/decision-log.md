# Decision Log

**This file is append-only.** Entries are never edited or deleted once written. If a
decision is later reversed or refined, a **new entry** is appended that references the
superseded entry by id. A log that can be rewritten after the fact is not an audit trail —
it is a draft. This rule is what makes the file evidence rather than documentation.

Entries written by a human during design carry `actor: human+claude (design session)`.
Entries written by the orchestrator at runtime are appended by `orchestrator/decision_log.py`
and carry the node id and run id that produced them.

**Format.** Each entry: id, date, actor, decision, alternatives considered, rationale, and
(where relevant) consequences and status.

---

## DEC-0001 — Resolve the assignment's "from scratch" vs "complete and improve" contradiction

- **Date:** 2026-09-13
- **Actor:** human+claude (design session)
- **Status:** Accepted
- **Type:** Assumption resolving ambiguity in the assignment itself

**Context.** The assignment PDF §2 states: *"You will build a URL shortener service from
scratch with core APIs, analytics, and reliability features. Your task is to complete and
improve it over 2-3 days."* These two sentences conflict: "from scratch" implies greenfield
with no starting codebase, while "complete and improve **it**" implies a pre-existing
codebase handed to the candidate. No starter repository was provided with the assignment.

**Decision.** Treat the two clauses as describing **two different phases of the same
engagement**, not a contradiction to be resolved in favour of one:

- **"From scratch"** governs the **greenfield** scenario — the orchestrator builds the core
  URL shortener (create + redirect + storage + tests + docs) starting from an empty `src/`.
- **"Complete and improve it"** governs the **brownfield** and **ambiguous** scenarios —
  they enhance, refactor and extend the codebase that the greenfield run produced.

**Alternatives considered.**

1. *Assume a starter repo was meant to be supplied and ask for it.* Rejected: it blocks all
   progress on an unanswerable question, and no such repo exists.
2. *Treat it as purely greenfield and ignore "complete and improve".* Rejected: it would
   leave the brownfield scenario (Req 3, Codebase Reasoning) with no codebase to reason
   about, gutting a graded requirement.
3. *Treat it as purely brownfield by writing a deliberately incomplete codebase first.*
   Rejected: manufacturing fake legacy code is wasted effort and less honest than building
   real code and then genuinely extending it.
4. **Chosen:** sequence them — greenfield produces the codebase; brownfield/ambiguous
   operate on that real output.

**Rationale.** This reading satisfies both clauses without discarding either, and it makes
the three required scenarios *genuinely distinct* rather than three retellings of one story
— which the project's definition-of-done explicitly requires. It also produces a stronger
brownfield demonstration: the impact analysis is performed against code that actually
exists and that the system itself wrote, so the codebase reasoning is verifiable rather
than hypothetical.

**Consequences.** Scenario ordering is now a real dependency: `greenfield` must be runnable
before `brownfield` and `ambiguous` are meaningful. This is documented in
`scenarios/README`-level ordering and in the Phase 9 handoff list.

---

## DEC-0002 — URL shortener stack: Python + FastAPI + SQLite (+ in-process cache)

- **Date:** 2026-09-13
- **Actor:** human+claude (design session)
- **Status:** Accepted

**Decision.** Python 3.11+, FastAPI (Uvicorn), SQLite via the stdlib `sqlite3` module, with a
small in-process TTL+LRU cache for the redirect hot path. Pydantic for request/response
schemas. `pytest` for tests.

**Alternatives considered.**

| Option | Pros | Cons | Verdict |
|---|---|---|---|
| **Python + FastAPI + SQLite** | One language for orchestrator *and* service; auto-generated OpenAPI schema satisfies the "API/schema definitions" requirement for free; SQLite is zero-setup and file-based; excellent async story | Not the highest-throughput option | **Chosen** |
| Node/TypeScript + Express + Postgres | Strong ecosystem; Postgres is production-realistic | Second language in the repo; Postgres needs a running server → setup friction for a reviewer | Rejected |
| Go + net/http + Postgres | Best raw redirect performance | Third language; slowest to write solo; orchestrator would still be Python | Rejected |
| Python + Django | Batteries included, admin UI free | Heavyweight for ~6 endpoints; ORM/migration machinery obscures the schema-change gate we *want* to be visible | Rejected |

**Rationale.** The decisive factor is **one language across both layers**: the orchestrator
is Python, so a Python service means a reviewer reads one stack, and the orchestrator can
invoke and test the service in-process without cross-language tooling. FastAPI's automatic
OpenAPI generation directly satisfies Req 5's "API/schema definitions" without hand-writing a
spec that could drift from the code. SQLite's single-file nature makes the **database
migration in the brownfield scenario concrete and inspectable** — the schema change that
trips the human gate is a real, visible DDL statement against a real file, not an
abstraction. Per the assignment's own framing, the shortener is the *test subject*; optimising
it for reviewer comprehension and setup speed beats optimising it for throughput.

**Consequences.** SQLite's single-writer model is a genuine scaling limit; recorded as a
known trade-off in `docs/architecture.md` §Risks with the migration path to Postgres named.

---

## DEC-0003 — Build a custom graph executor rather than adopt a workflow framework

- **Date:** 2026-09-13
- **Actor:** human+claude (design session)
- **Status:** Accepted
- **Type:** Core architectural decision (Req 4)

**Decision.** Implement a small, fully-owned graph executor in `orchestrator/`. Borrow
*patterns* from production engines; adopt **no** engine as a dependency.

**Alternatives considered.**

1. **Temporal.** Genuinely solves durability via event sourcing and has the best retry model.
   Rejected: requires a running server (or dev cluster) plus workers — the reviewer must
   stand up infrastructure before seeing anything run; and the determinism constraint on
   workflow code is a large conceptual surface to defend for a feature (crash-durability
   across machines) this prototype does not need.
2. **Airflow.** Mature scheduler, real DAGs, good state model. Rejected: scheduler-centric
   and batch/cron-oriented; heavy install; the DAG model is built around scheduled data
   pipelines rather than a single interactive run with human gates, and most of the system
   would be unused weight.
3. **LangGraph.** Closest fit — built for LLM agent graphs, has `interrupt()` for HITL and
   checkpointers for state. Rejected, and this was the closest call: adopting it would mean
   the single most-graded component (Req 4, "the critical differentiator") is *someone
   else's* implementation. In an interview the question "how does your gate actually pause
   execution?" must be answerable with our own code, not "the framework handles it." We
   instead borrow its design and its documented sharp edges (see DEC-0007).
4. **Prefect / Dagster / Argo / Step Functions.** Each rejected for a variant of the same
   reasons — external infrastructure (Argo needs Kubernetes; Step Functions needs AWS), or a
   model aimed at data assets rather than SDLC stages with human gates.
5. **Chosen: custom executor.**

**Rationale.** Three reasons, in order of weight.

**(a) Defensibility.** The assignment grades "clarity and defensibility of decisions" and
names orchestration the critical differentiator. Every line of the executor must be
explainable under questioning. A custom ~500-line executor is fully explainable; a framework
integration means the interesting behaviour lives in a dependency.

**(b) Fit.** Our requirements are unusual for a workflow engine: SDLC-stage granularity,
LLM-driven nodes, exit gates as *content-validation predicates*, decision lineage as a
graded artifact. General-purpose engines provide none of these natively; we would be writing
that layer anyway, on top of an engine whose remaining 90% we do not use.

**(c) Demonstrability.** A file-based gate that a reviewer can inspect, kill the process,
and resume (DEC-0007) is more convincing than an engine-internal signal.

**This is an explicitly bounded claim.** We are *not* claiming a hand-rolled executor beats
Temporal for production durable execution — it does not, and the architecture doc says so.
The claim is narrower and defensible: **for a single-process, solo-built, interview-graded
artifact whose value is in being explainable, owning the code beats importing it.** Where an
engine's design is better than what we would invent — Temporal's retry schema, Step
Functions' failure ordering, Dagster's staleness model, Airflow's state enum — we borrow the
*pattern* and attribute it in `docs/research-notes.md`.

**Consequences.** We own the correctness of topological sorting, cycle detection, the join
barrier, and concurrency. These are exactly the areas that must carry unit tests
(`tests/README.md`). No crash-durability mid-node: a process killed while a node is running
loses that node's progress (gates are the durable checkpoints). Recorded as a limitation.

---

## DEC-0004 — Concurrency model: `asyncio` (single-threaded event loop)

- **Date:** 2026-09-13
- **Actor:** human+claude (design session)
- **Status:** Accepted

**Decision.** Run parallel graph branches on `asyncio`, dispatching ready nodes with
`asyncio.gather`/`TaskGroup` over the ready-frontier.

**Alternatives considered.**

| Option | Pros | Cons | Verdict |
|---|---|---|---|
| **asyncio** | Node work is I/O-bound (LLM/API calls, file I/O) — the ideal async case; single-threaded, so **no locks around shared run state**; deterministic, readable interleaving; native timeout/cancellation for safe-stop | Requires async discipline throughout; a blocking call stalls the loop | **Chosen** |
| `threading` / `ThreadPoolExecutor` | Trivially wraps blocking SDKs; familiar | Shared mutable run state now needs locking → the classic source of subtle, non-reproducible bugs in exactly the component that must be trustworthy; harder to reason about in review | Rejected |
| `multiprocessing` worker pool | True CPU parallelism; process isolation | Our work is not CPU-bound; state must be serialized across process boundaries; heavyweight and complicates gates/lineage | Rejected |

**Rationale.** Two decisive points. First, **the workload is I/O-bound** — nodes wait on
model calls, subprocesses and file writes — which is precisely where async wins and where
threads buy nothing but risk. Second, and more important for this project, **single-threaded
concurrency removes an entire class of bug from the component that most needs to be
correct.** The executor mutates shared run state (node states, ready queue, metrics, lineage)
on every transition; under threads that requires disciplined locking, and a race in the
orchestrator would undermine the audit trail the assignment grades. With a single event loop,
state transitions are atomic between `await` points by construction. `asyncio.wait_for` also
gives safe-stop timeouts and cancellation natively.

**Consequences.** Any blocking library call inside a node must be wrapped
(`asyncio.to_thread`) or it stalls the loop — a real footgun, noted in `orchestrator/README.md`
as an implementation rule for Claude Code. CPU-bound node work would not parallelise; none is
expected.

---

## DEC-0005 — Persistence: JSON run-state snapshot + JSONL append-only lineage + JSON metrics

- **Date:** 2026-09-13
- **Actor:** human+claude (design session)
- **Status:** Accepted

**Decision.** Three distinct files per run under `runs/<run_id>/`, chosen per artifact
according to its access pattern and mutability rules:

| File | Format | Mutability | Why |
|---|---|---|---|
| `state.json` | JSON snapshot | rewritten atomically on transition | Must be *read whole* to resume after a gate; small; human-inspectable |
| `lineage.jsonl` | JSON Lines | **append-only** | Audit trail: append-only is enforced by the access pattern itself |
| `metrics.json` | JSON | rewritten at run end (+ cumulative `metrics/cumulative.json`) | Computed aggregate, not a log |
| `pending_approval.json` | JSON | created/removed by gate manager | The visible gate artifact (DEC-0007) |

Plus a human-readable `docs/decision-log.md` (this file), to which the logger appends
narrative entries mirroring `lineage.jsonl`.

**Alternatives considered.**

1. **SQLite for orchestrator state.** Genuinely tempting (we already depend on SQLite for the
   service, it gives ACID and queryability). Rejected for one reason that outweighs those:
   **inspectability**. The gate and the lineage are *evidence to be shown to a reviewer*; a
   reviewer can `cat` a JSON file mid-run, but must open a DB client to see a table. When the
   artifact's purpose is demonstrating governance, plain text wins over queryability.
2. **A single combined file.** Rejected: it would force the append-only audit trail and the
   mutable state snapshot into one file with contradictory mutability rules — the audit trail
   would inherit rewrite semantics and stop being an audit trail.
3. **In-memory only.** Rejected outright: cannot survive the persist-and-exit gate, which is
   the entire point of DEC-0007.
4. **Chosen:** three files, separated by mutability contract.

**Rationale.** The separation is the decision. `state.json` must be *mutable and read whole*;
`lineage.jsonl` must be *immutable and appended*. JSONL is the right audit format because
appending is the only natural operation — the format itself discourages rewriting history,
and a partially-written final line is detectable. Atomic writes (`write temp → os.replace`)
prevent a torn `state.json` if the process dies mid-write.

**Consequences.** No transactional guarantee *across* the three files: a crash between writing
lineage and updating state could leave them briefly inconsistent. Mitigation: write lineage
first (an extra lineage entry is harmless; a missing one is not), and make state reconstruction
tolerant. Recorded as a limitation. No concurrent-run isolation — runs are namespaced by
`run_id` but there is no lock preventing two processes touching one run.

---

## DEC-0006 — Definition of a "high-impact action" requiring human approval

- **Date:** 2026-09-13
- **Actor:** human+claude (design session)
- **Status:** Accepted
- **Type:** Policy guardrail (Req 4, Req 7)

**Decision.** An action is **high-impact** — and therefore requires a human approval gate
before execution — if it meets **any** of these five criteria:

1. **Irreversibility.** It cannot be automatically undone by a rollback action
   (destructive/lossy operations, anything with external side effects).
2. **Schema/data-shape change.** Any DDL: migrations, table/column creation or alteration,
   index changes. Includes the brownfield analytics table.
3. **Deletion.** Removal of any file, table, record or artifact.
4. **Release readiness.** Anything asserting the software is fit to ship — final sign-off,
   tagging, publishing, merge-to-main.
5. **Production/config surface.** Changes to production configuration, secrets, credentials,
   network exposure, or dependency additions that enlarge the trust boundary.

Everything **not** meeting these criteria is **pre-approved** (autonomous): reading code,
writing new code in `src/`, writing tests, running tests in a sandbox, generating docs,
producing analyses. Agents act without asking; humans review outcomes.

**Alternatives considered.**

1. *Gate every node.* Rejected: approval fatigue turns review into rubber-stamping — a gate a
   human clicks through without reading provides governance theatre, not governance. It would
   also make "controlled autonomy" indistinguishable from "no autonomy."
2. *Gate only the final release.* Rejected: too permissive — a destructive migration would run
   unreviewed, and the single end-gate would be reviewing work already done rather than
   preventing harm.
3. *Let the LLM judge impact per action.* Rejected as **unsound governance**: it makes the
   guardrail depend on the judgement of the component being guarded. A policy that the agent
   can reason its way out of is not a policy.
4. **Chosen:** a fixed, declarative, criteria-based policy, evaluated by the executor, with
   the flag declared per node in the YAML.

**Rationale.** Borrowed directly from ITIL change management's **risk-tiering** (see
`docs/research-notes.md` §3.1): standard/pre-approved changes proceed freely, normal/major
changes require review. Tiering is what makes gates credible — a human who is asked to approve
only genuinely consequential things reads them carefully. The criteria are deliberately
**structural** (does it touch schema? does it delete? is it irreversible?) rather than
judgement-based, so the classification is reproducible and auditable. Under this policy the
greenfield graph has 2 gates and the brownfield graph has 3 (the extra one being the
migration) — so the policy demonstrably *varies with actual risk* rather than being a fixed
decoration.

**Consequences.** The policy is enforced in two places that must agree: `requires_human_approval`
declared per node in `docs/orchestration-graph.yaml`, and the criteria encoded in
`orchestrator/gates.py`. A validation check at graph load flags nodes whose declared flag
disagrees with the policy classification, so drift between the two is caught rather than
silently trusted.

---

## DEC-0007 — Gates pause via persist-and-exit at node boundaries, never mid-node

- **Date:** 2026-09-13
- **Actor:** human+claude (design session)
- **Status:** Accepted

**Decision.** When a node requires approval, the executor writes `pending_approval.json` and
`state.json`, then **stops**. A separate `resume` invocation reads the recorded decision and
continues. Gates are evaluated at **node boundaries** — before a node starts (entry gate) or
after it produces output (exit gate) — **never partway through a node's execution.**

**Alternatives considered.**

1. *Block in-process on stdin/`asyncio.Event` and wait.* Rejected: the pause exists only in
   live memory, so it is indistinguishable from `sleep()`, cannot survive a process restart,
   and cannot be inspected as evidence.
2. *Mid-node interrupt (LangGraph's `interrupt()` model).* Rejected on the strength of
   LangGraph's own documented behaviour: *"the node restarts from the beginning of the node
   where the `interrupt` was called when resumed, so any code before the `interrupt` runs
   again."* That forces every pre-interrupt operation to be idempotent — a permanent tax on
   every node author and a subtle source of duplicate side effects.
3. **Chosen:** persist-and-exit at node boundaries.

**Rationale.** Boundary-only gating **designs the re-execution hazard out of existence**: a
node either has not started (nothing to redo) or has finished and produced its artifact
(nothing to redo). No partial execution is ever suspended, so no idempotency requirement is
imposed on node authors — we get LangGraph's capability without inheriting its sharp edge.

And it makes the gate *provable*. Per the project's definition of done — *"can you point to
where in the running system a human gate actually pauses execution, versus where it's merely
mentioned in a comment?"* — the answer is a file on disk, a process that has exited, and a
resume that continues from recorded state. A reviewer can inspect it, kill the machine, and
still resume.

**Consequences.** Approval is a separate invocation, so the demo is a three-command sequence
(`run` → approve → `resume`) rather than one continuous process. This is a feature for
demonstration and a mild inconvenience for automation; an `--auto-approve` flag for CI is
noted as a future affordance and must be clearly non-default.

---

## DEC-0008 — Analytics and reliability feature scope for the URL shortener

- **Date:** 2026-09-13
- **Actor:** human+claude (design session)
- **Status:** Accepted

**Decision.** Concrete, named scope — not aspirations.

**Core API (greenfield).**
- `POST /api/v1/links` — create short link. Accepts `url`, optional `custom_alias`,
  optional `expires_at`. Honours `Idempotency-Key`.
- `GET /{code}` — redirect. **302** (see DEC-0009).
- `GET /api/v1/links/{code}` — metadata for one link.
- `DELETE /api/v1/links/{code}` — disable (soft delete; sets status, never hard-deletes).
- `GET /health` — liveness/readiness.

**Analytics (brownfield scenario).**
- Per-click event capture: timestamp, referrer, user-agent, derived country; **IP is
  truncated/hashed at write time, never stored raw** (GDPR — research-notes §6.3).
- Aggregate `click_count` maintained alongside raw events.
- `GET /api/v1/links/{code}/stats` — total clicks, clicks-per-day time series, top referrers.
- Click recording is **asynchronous and fire-and-forget**: it must never add latency to, or
  fail, the redirect.

**Reliability.**
- **Rate limiting** — token bucket per API key/IP; stricter on create than on redirect;
  `429` + `Retry-After`.
- **Caching** — in-process TTL+LRU on the `code → url` lookup, cache-aside; invalidated on
  disable/expire.
- **Idempotency** — `Idempotency-Key` on create; replay returns the original result.
- **Expiry/disable** — `expires_at` and a status flag, both checked on the read path;
  expired/disabled return **410 Gone** (not 404 — the resource existed and was deliberately
  retired).
- **Abuse prevention** — scheme allowlist (`http`/`https` only); denylist of private and
  internal ranges (loopback, RFC1918, link-local, and `169.254.169.254`); **DNS resolution
  with validation of the resolved IPs**, not just the hostname; no redirect-following during
  validation; reserved-word list for custom aliases; self-shortening prevention.
- **Structured logging** with request ids; `/health` endpoint.

**Explicitly out of scope:** custom domains, user accounts/auth beyond API keys, a web UI,
QR codes, geographic sharding, real-time dashboards. Named here so the boundary is a
decision rather than an omission.

**Rationale.** The list is chosen so that each feature **exercises something the orchestrator
must handle**: the analytics table forces a schema-change gate; rate limiting and caching give
the "10× traffic" backlog scenario something to act on; abuse prevention is the security
guardrail Req 6 asks for and is genuinely load-bearing for a shortener (research-notes §6.6);
idempotency matters precisely because the orchestrator retries.

---

## DEC-0009 — Redirect uses HTTP 302, not 301

- **Date:** 2026-09-13
- **Actor:** human+claude (design session)
- **Status:** Accepted

**Decision.** `GET /{code}` returns **302 Found** with explicit `Cache-Control: no-store`.

**Alternatives considered.** **301** (permanent) — better for SEO link equity and reduces
origin load, since browsers cache it. **307** — temporary and method-preserving. **302** —
temporary, not cached by default.

**Rationale.** A 301 is cached client-side, and the documented consequence is decisive:
*"redirection requests do not reach the server because of the cache on the client side. If the
redirection requests do not reach the server, analytics data cannot be collected."* Two of our
committed features (DEC-0008) depend on every click reaching the origin: **click analytics**
would silently under-count after the first visit per browser, and **disable/expire would not
take effect** for any browser holding a cached 301 — a correctness *and safety* failure, since
a link taken down for abuse would keep redirecting. 307 is equivalent for our purposes but
302 is the better-understood default for shorteners; we are not method-preserving-sensitive as
only GET is served on this path.

**Consequences.** Every click costs an origin request — accepted, and the reason the
redirect path gets a cache and an index. SEO link equity is not preserved; irrelevant for this
service's purpose and noted as a deliberate trade-off.

---

## DEC-0010 — Short-code generation: random base62 with collision check

- **Date:** 2026-09-13
- **Actor:** human+claude (design session)
- **Status:** Accepted

**Decision.** 7-character codes drawn from a CSPRNG (`secrets`) over the base62 alphabet,
inserted under a `UNIQUE` constraint, with bounded retry on collision (max 5 attempts, then
error).

**Alternatives considered.**

| Approach | Pros | Cons |
|---|---|---|
| Counter + base62 | Collision-free by construction; O(1); shortest codes | **Sequentially enumerable** — anyone can walk the space and harvest every link; needs coordination if sharded |
| Hash(URL) + truncate | Deterministic → free dedup/idempotency | Truncation reintroduces collisions (so a check is needed *anyway*); mapping is publicly reproducible; forces dedup even when distinct codes are wanted |
| **Random + collision check** | **Unpredictable**; no coordination; trivially shardable | Needs a uniqueness check per insert; retry probability rises as space fills |

**Rationale.** The deciding factor is that **enumerability is a security property that cannot
be retrofitted**, whereas the collision check is nearly free at our scale. With 62^7 ≈ 3.5×10¹²
codes and a prototype-scale corpus, the expected number of collision retries is
indistinguishable from zero, and the check is a single indexed `UNIQUE` lookup that SQLite
performs anyway on insert. The counter approach's one real advantage — guaranteed uniqueness —
buys us little, while its cost (sequential codes let anyone enumerate every "unlisted" link)
is a genuine privacy leak. Hashing keeps the collision problem *and* adds public
reproducibility.

**Consequences.** Codes are not deduplicated: shortening the same URL twice yields two codes.
This is intentional — it allows per-campaign analytics on the same destination — and `dedup`
via `Idempotency-Key` is available where a caller genuinely wants it. Collision retry is
bounded; exhausting 5 attempts raises rather than looping (consistent with the bounded-retry
principle in the orchestrator itself).

---

## DEC-0011 — Executor concurrency safety: the ready-frontier is the parallelism

- **Date:** 2026-09-13
- **Actor:** human+claude (design session)
- **Status:** Accepted

**Decision.** Parallelism is **derived** from Kahn's algorithm's in-degree-0 frontier, not
declared. The `can_run_parallel` field in the YAML is a **declared expectation that the graph
validator checks against the computed frontier**, not the source of truth for scheduling.

**Alternatives considered.**

1. *Treat `can_run_parallel` as the scheduling instruction.* Rejected: it duplicates
   information already implied by `depends_on`, and the two can disagree. If a YAML edit adds
   a dependency between two nodes still flagged as parallel, an instruction-based scheduler
   would run them concurrently **in violation of the dependency** — a correctness bug
   introduced by redundant data.
2. *Omit `can_run_parallel` entirely.* Rejected: the assignment asks for parallel capability
   to be explicit and inspectable, and an intent declaration that is *verified* has real value
   — it catches a graph edit that accidentally serialises an intended-parallel pair.
3. **Chosen:** derive scheduling from edges; use the declaration as an assertion.

**Rationale.** Single source of truth for correctness (the edge set), with the declaration
demoted to a **checked assertion**. This is the standard resolution for redundant
configuration: keep the redundancy for documentation and validation value, but never let it
drive behaviour. Mismatches become load-time validation errors instead of runtime races.

**Consequences.** `orchestrator/executor.py` must implement `validate_parallelism_claims()`,
and the graph fails to load on mismatch. Covered by unit tests.

---

## DEC-0012 — Switch implementation language from Python to Java

- **Date:** 2026-09-13
- **Actor:** human+claude (build session)
- **Status:** Accepted — **supersedes DEC-0002**
- **Type:** Implementation-language change, not a design change

**Context.** DEC-0002 chose Python + FastAPI + SQLite for the shortener, with the
decisive factor being *one language across orchestrator and service*. The build
requirement has changed: the implementation now targets Java. DEC-0002's design
reasoning (auto-generated API schema, SQLite for an inspectable brownfield
migration, one-stack-for-a-reviewer) is not wrong — it needs a Java-native
equivalent for each point, not a different design.

**Decision.** Java 21 LTS + Spring Boot 3.x + Maven, with `org.xerial:sqlite-jdbc`
(plain JDBC, no ORM) in place of SQLite via the stdlib driver, and
`springdoc-openapi-starter-webmvc-ui` in place of FastAPI's automatic OpenAPI
generation.

| DEC-0002 element | Java replacement | Why the property survives |
|---|---|---|
| Python 3.11+ | Java 21 LTS | LTS release; `--enable-preview`-free (see DEC-0013) |
| FastAPI (auto OpenAPI) | springdoc-openapi (generated from Spring MVC annotations) | Same benefit: the spec is generated from the code, so it cannot drift from what the endpoints actually do |
| stdlib `sqlite3` | `org.xerial:sqlite-jdbc`, plain JDBC, no JPA/Hibernate | Deliberate, not incidental: the brownfield migration must be *visible, inspectable SQL*. An ORM's auto-DDL would hide exactly the schema change the human gate (`apply_migration`) exists to review |
| One language spans orchestrator + service | Still true — both are Java now | This was DEC-0002's decisive factor and it still holds; the reviewer still reads one stack |
| `pytest` | JUnit 5 + AssertJ + Mockito | Equivalent role, same position in the build order (tests alongside each module) |

**Alternatives considered.**

1. *Stay on Python.* Rejected — the requirement changed; this is not a
   reconsideration of DEC-0002's original reasoning, which remains sound for a
   Python target.
2. *Kotlin on the JVM.* Rejected — less universally reviewable than Java for an
   artifact whose stated purpose is being explainable line by line to anyone
   grading it; Java has no syntax the reviewer must learn to follow the logic.
3. *Java without Spring, e.g. Javalin.* Rejected — we would then hand-write the
   OpenAPI spec ourselves, which reintroduces the exact drift risk DEC-0002 chose
   FastAPI specifically to avoid. Spring Boot + springdoc keeps "the schema is
   generated from the code" true in the new language.

**Rationale.** The port preserves every property DEC-0002 was actually chosen
for — one language, a generated (non-drifting) API schema, and a storage layer
whose schema change is visible SQL rather than ORM-managed DDL — while meeting
the changed language requirement. Nothing in `docs/orchestration-graph.yaml`
needs to change: the graph is data, and none of it is Python-specific (verified
by inspection — no node references a Python module path or tool).

**Consequences.** `orchestrator/*.py` and `orchestrator/__init__.py` are deleted
(their design intent is fully captured in the docs and in the new Java
structure; keeping a second, non-running implementation around would contradict
the project's own rule against claiming unbuilt things are built). `README.md`,
`orchestrator/README.md`, `src/README.md`, `tests/README.md` and
`docs/example-run.md` are updated to Java/Maven form in the same change
(DEC-0013 follows immediately, covering the concurrency-model consequence of
this port).

---

## DEC-0013 — Concurrency model: virtual threads (supersedes DEC-0004)

- **Date:** 2026-09-13
- **Actor:** human+claude (build session)
- **Status:** Accepted — **supersedes DEC-0004**
- **Type:** Concurrency-model change forced by DEC-0012

**Context.** DEC-0004 chose `asyncio` for one central reason: *"single-threaded
concurrency removes an entire class of bug from the component that most needs to
be correct... state transitions are atomic between `await` points by
construction."* That argument does **not** survive the port to Java. Virtual
threads (JEP 444, final in Java 21) give the same "cheap, massively-concurrent,
I/O-bound-friendly" scheduling model as `asyncio` tasks — but they are
**genuinely concurrent** on the JVM. There is no single event loop serialising
access to shared run state, so DEC-0004's no-locking-needed guarantee is simply
gone, not weakened.

**Decision.** Use **stable virtual threads** (JEP 444) for fanning out parallel
graph branches, with three explicit rules replacing the atomicity DEC-0004 got
for free:

1. **Shared run state is confined to the executor** and mutated only while
   holding a single `ReentrantLock` (or via concurrent collections with explicit
   compare-and-set) — never touched by node/agent code.
2. **Lineage writes are serialized through one synchronized appender**, so the
   single-writer property DEC-0004 relied on for a trustworthy audit trail is
   preserved structurally rather than by virtue of a single thread.
3. **Node agents receive immutable inputs and return immutable results**; they
   never hold a reference to `Run` and cannot mutate it, by construction rather
   than by convention.

Fan-out itself goes through a small `StructuredScope` helper
(`Executors.newVirtualThreadPerTaskExecutor()`, wrapped) rather than
`StructuredTaskScope` directly.

**Alternatives considered.**

1. **`StructuredTaskScope` (JEP 505/525) directly.** This is the JDK's own
   structured-concurrency primitive and the closest analogue to
   `asyncio.TaskGroup`. Rejected as the *direct* dependency because it remains a
   **preview API through JDK 26** — it requires `--enable-preview` at both
   compile and run time. A reviewer must be able to `mvn test` on a stock JDK 21
   LTS with no preview flags; forcing a preview flag on every build (and risking
   the API shifting between JDK feature releases, as preview APIs do) is not
   acceptable for a graded, reviewer-run artifact.
2. **Platform-thread `ExecutorService` + `CompletableFuture`.** Rejected —
   heavier (real OS threads, pool sizing to reason about) for I/O-bound agent
   work, which is exactly the case virtual threads are designed for; a pool
   sized for platform threads gives nothing here that virtual threads don't give
   more cheaply.
3. **Chosen: stable virtual threads (JEP 444), wrapped in `StructuredScope`.**

**Rationale.** `StructuredScope` isolates the concurrency API behind one small
class whose job is exactly the fan-out `asyncio.gather(return_exceptions=True)`
did: run every ready-frontier node concurrently, await them all, and route each
outcome to success/failure handling individually so one branch's failure never
discards a sibling's completed work. Because it is the *only* caller of
`Executors.newVirtualThreadPerTaskExecutor()`, adopting `StructuredTaskScope`
once it finalizes is a one-file change, not a redesign — the same "borrow the
pattern, own the code" posture DEC-0003 already committed to.

The three rules above are the replacement for DEC-0004's free atomicity. They
are stricter than "be careful with locks": rule 3 makes it structurally
impossible for a node agent to touch `Run` at all, so the only code that needs
to reason about concurrent mutation is the executor itself — the same
single-writer principle DEC-0004 argued for, re-derived for a genuinely
concurrent runtime instead of assumed from single-threadedness.

**Consequences.** Every mutation of `Run` must go through `stateLock`; a future
change that reads or writes run state without holding it is a bug class this
decision does not eliminate automatically (Java gives no equivalent of
"can't happen because there's only one thread") — it must be caught by code
review and by tests that exercise concurrent node completion. The
`asyncio.to_thread` implementation rule in `orchestrator/README.md` has no
Java equivalent requirement in the same form: virtual threads that block on I/O
do not stall other virtual threads (no shared OS-thread event loop to stall),
so the specific footgun DEC-0004 warned about does not recur — but a virtual
thread pinned by a blocking call inside synchronized code is a *different*
footgun, which is exactly why lineage writes (rule 2) are a short, synchronized
critical section rather than anything that does I/O while holding a broader
lock. `docs/architecture.md` §3.4 and risk 10 are updated to note that this
decision supersedes DEC-0004.

---

## DEC-0014 — GraphValidator's DEC-0006 check covers only structurally-detectable criteria

- **Date:** 2026-09-13
- **Actor:** human+claude (build session)
- **Status:** Accepted — narrows, does not supersede, DEC-0006
- **Type:** Implementation decision filling a gap the docs left open

**Context.** `orchestrator/README.md`'s build order lists check 8 of `GraphLoader`/
`GraphValidator`'s nine validation checks as: *"`requires_human_approval` agrees
with the DEC-0006 policy criteria — a node meeting a high-impact criterion may not
declare `false`."* Neither `docs/architecture.md` nor DEC-0006 itself specifies
**how** a node's declared fields map onto the five DEC-0006 criteria (irreversible,
schema_change, deletion, release_readiness, production_surface) — that mapping is
genuinely unspecified, and the full version is explicitly `gates.PolicyEngine`'s
job (build-order step 6), which does not exist yet at this point in the build.

**Decision.** Implement a narrow, honestly-scoped slice of the classification
directly in `GraphValidator`, covering only the two criteria with an unambiguous
structural signal on the current `NodeSpec` model:

- **schema_change** — a node's `produces` includes an artifact shaped like
  `migration_applied`, or its `rollback_action.type` is `execute_sql`.
- **release_readiness** — `agent_role` is `release_readiness_agent`.

The other three criteria (irreversible, deletion, production_surface) are **not**
classified at this layer — there is no field on `NodeSpec` today from which any of
them could be structurally derived without guessing, and a guessed classifier that
sometimes fires wrongly is worse than one that plainly does not cover a case yet.

**Alternatives considered.**

1. *Skip check 8 entirely until `gates.PolicyEngine` exists.* Rejected: it is the
   exact check that catches the concrete drift named in `docs/architecture.md` §7
   risk 9 (`apply_migration` declaring `requires_human_approval: false`), and
   `GraphLoader`/`GraphValidator` is supposed to be usable and fail-closed on its
   own before the executor or gates package exist.
2. *Build a full five-criteria classifier now, ahead of `gates.PolicyEngine`.*
   Rejected: three of the five criteria have no structural signal to classify from
   yet, so a "full" classifier would either hard-code node ids (defeating the
   purpose of a generalizable policy) or silently never fire for those three,
   which is indistinguishable from not implementing them but looks more complete
   than it is.
3. **Chosen: implement only what is structurally honest today; document the gap.**

**Rationale.** Consistent with `CLAUDE.md`'s honesty rule: a check that claims to
enforce all of DEC-0006 but structurally can only see two of five criteria would be
a check that looks enforced without being enforced for three of them — exactly the
failure mode `CLAUDE.md` §0 calls out as more damaging than an unfinished feature.
Stating the boundary explicitly (in this entry and in `GraphValidator`'s Javadoc)
means the gap is a documented, known limitation rather than a latent gap discovered
later by someone trusting the check to do more than it does.

**Consequences.** When `gates.PolicyEngine` is built (build-order step 6), it should
absorb this classification logic (verbatim for the two covered criteria, extended
for the other three once a structural signal for them exists on the node model —
e.g. an explicit `high_impact_criteria: [...]` field may need to be added to
`NodeSpec`/the YAML schema at that point), and `GraphValidator`'s check 8 should
delegate to it rather than keep a second, drifting copy of the same logic.

# Agentic SDLC Orchestrator

An orchestration engine that coordinates a full software development lifecycle —
requirements → design → implementation → testing → documentation → release
readiness — as an **explicit dependency graph** with entry/exit gates, parallel
execution, human approval checkpoints, bounded retry/rollback/safe-stop, decision
lineage, and reliability metrics.

A URL shortener is the test subject the engine builds and evolves.

> ## Status: specified and scaffolded, not yet running
>
> The design is complete and the code structure is in place — real dataclasses, real
> signatures, real control flow — with implementation bodies marked `TODO(impl)`.
> **The orchestrator does not execute end-to-end yet.**
>
> `orchestrator/README.md` has a file-by-file status table. Nothing in this README
> should be read as a claim that a command below currently works; the commands are
> the specified interface, and `docs/example-run.md` is the trace they must produce.

---

## Why this exists

The interesting problem is not "can an LLM write a URL shortener" — it can. The
problem is whether multi-step software work can run **under governance**: with
checkpoints a human actually controls, failures that degrade safely instead of
silently, an audit trail that survives scrutiny, and the ability to re-plan when
requirements change without losing any of that.

This repository is an attempt at that, built small enough to be explained line by
line.

---

## The two layers

Keeping these distinct is the point of the project:

| Layer | Path | What it is |
|---|---|---|
| **Orchestrator** | `orchestrator/`, `docs/orchestration-graph.yaml` | **The deliverable.** The graph engine. |
| **URL shortener** | `src/` | **The test subject.** What the engine builds. |

If you only read one thing, read `docs/orchestration-graph.yaml` — the graph is
data, so the governance model is legible without reading any code.

---

## Repository layout

```
CLAUDE.md                       operating contract for Claude Code in this repo
docs/
  architecture.md               components, orchestration model, roles, risks
  orchestration-graph.yaml      THE DAG — nodes, edges, gates, retry, rollback
  decision-log.md               append-only decision lineage (DEC-0001 …)
  research-notes.md             sourced research behind every decision
  example-run.md                worked end-to-end trace + test specification
scenarios/
  greenfield.md                 build the core service from nothing
  brownfield.md                 add click analytics to existing code
  ambiguous.md                  "make it better, faster, safer"
orchestrator/
  README.md                      status table + build order (Java classes)
src/
  main/java/com/sdlc/
    orchestrator/                model · graph · exec · gates · lineage ·
                                  metrics · agents · cli   (the deliverable)
    shortener/                   model · store · service · web · config
                                  (see src/README.md)
  test/java/com/sdlc/            unit + integration (see tests/README.md)
pom.xml                          Maven build (Java 21, Spring Boot 3.3+)
runs/<run_id>/                  per-run state, lineage, metrics  [gitignored]
```

---

## Setup

**Requires JDK 21 (LTS) and Maven 3.9+.** No `--enable-preview` flag is ever
required (DEC-0013).

```bash
git clone <repo-url>
cd agentic-sdlc-orchestrator

mvn -version      # confirm JDK 21 is picked up
mvn compile
```

Direct dependencies (declared in `pom.xml`): `spring-boot-starter-web`,
`spring-boot-starter-validation`, `springdoc-openapi-starter-webmvc-ui`,
`org.xerial:sqlite-jdbc`, `org.yaml:snakeyaml`, `com.fasterxml.jackson.core:jackson-databind`;
test-scoped `spring-boot-starter-test`, `junit-jupiter`, `assertj-core`, `mockito-core`.

### Verify the graph

Validation is the one thing meaningfully checkable before the executor exists, and
it is fail-closed: an invalid graph never runs.

```bash
java -jar target/orchestrator-cli.jar validate --graph docs/orchestration-graph.yaml
```

Expected output:

```
[load] schema_version 1.0 — OK
[load] 7 nodes, 7 edges — node ids unique — OK
[load] cycle check (Kahn): 7 of 7 nodes emitted — ACYCLIC — OK
[load] parallelism assertions: testing || documentation (both wave 4) — OK
[load] policy cross-check (DEC-0006): declared gates match classification — OK
```

> **TODO** — requires `graph.GraphLoader`/`GraphValidator` and the CLI entrypoint.

---

## Running

### A greenfield build

```bash
java -jar target/orchestrator-cli.jar run \
  --graph docs/orchestration-graph.yaml \
  --request "Build a URL shortener service with an API to create short links and a redirect endpoint. Include storage, tests, and documentation." \
  --mode greenfield
```

The run executes until it completes or **pauses at a human gate**. A pause is not a
failure — the process persists state and exits 0.

### Responding to a gate

When a run pauses:

```
Run paused awaiting approval.
  Review:  runs/r-20260913-0741-a3f9/pending_approval.json
  Respond: write approval.json in the same directory
  Resume:  java -jar target/orchestrator-cli.jar resume --run-id r-20260913-0741-a3f9
```

Read what you are approving:

```bash
cat runs/r-20260913-0741-a3f9/pending_approval.json
```

Record a decision — `comment` is required, because the rationale is the
audit-relevant part:

```bash
cat > runs/r-20260913-0741-a3f9/approval.json <<'EOF'
{
  "approver": "your-name",
  "decision": "approved",
  "comment": "Residual risks acknowledged; rate limiting must land before public exposure."
}
EOF

java -jar target/orchestrator-cli.jar resume --run-id r-20260913-0741-a3f9
```

**The pause survives process death.** You can reboot between the pause and the
resume; everything needed comes off disk. That is what makes the gate a gate rather
than a `sleep()` — see DEC-0007.

### Inspecting a run

```bash
cat runs/<run_id>/state.json      # node states, artifacts, approvals
cat runs/<run_id>/lineage.jsonl   # append-only audit trail
cat runs/<run_id>/metrics.json    # success rate, retries, MTTR, latency
```

### Other commands

```bash
java -jar target/orchestrator-cli.jar status --run-id <run_id>
java -jar target/orchestrator-cli.jar replan --run-id <run_id> \
    --changed-artifact requirement_spec --reason "Scope changed"
java -jar target/orchestrator-cli.jar metrics --cumulative
```

> **TODO** — all `run`/`resume`/`replan`/`status` commands require the executor.

---

## Testing

```bash
mvn test                                        # everything
mvn test -Dtest="com.sdlc.orchestrator.**"      # orchestrator units + integration
mvn verify                                      # test + jacoco coverage gate
```

`tests/README.md` has the strategy. `docs/example-run.md` §9 lists the 18 assertions
that constitute "the orchestrator works" — including the ones that are easy to skip
and matter most: that a gate survives process death, that retry exhaustion reaches
safe-stop, that re-planning preserves lineage, and that `lineage.jsonl` is genuinely
append-only.

> **TODO** — no tests yet; they are written alongside each module per the build
> order in `orchestrator/README.md`.

---

## The URL shortener

Built and modified by the orchestrator; see `src/README.md`.

| Endpoint | Purpose |
|---|---|
| `POST /api/v1/links` | Create a short link (optional `custom_alias`, `expires_at`) |
| `GET /{code}` | Redirect — **302**, not 301 (DEC-0009) |
| `GET /api/v1/links/{code}` | Link metadata |
| `GET /api/v1/links/{code}/stats` | Click analytics *(brownfield scenario)* |
| `DELETE /api/v1/links/{code}` | Disable (soft delete) |
| `GET /health` | Liveness/readiness |

Once implemented:

```bash
mvn spring-boot:run      # http://localhost:8080/swagger-ui.html for OpenAPI
```

> **TODO** — `src/` is empty; the greenfield run is what produces it.

---

## Reading order

For a reviewer, in this order:

1. **`docs/orchestration-graph.yaml`** — the governance model, as data.
2. **`docs/architecture.md`** — how it fits together; roles; risks; limitations.
3. **`docs/example-run.md`** — what a real run looks like, including failure and
   re-planning.
4. **`scenarios/*.md`** — three genuinely different traversals.
5. **`docs/decision-log.md`** — every decision with its alternatives and rationale.
6. **`docs/research-notes.md`** — the sourced evidence underneath.

---

## Key decisions at a glance

Full reasoning and alternatives-considered in `docs/decision-log.md`.

| # | Decision | One-line rationale |
|---|---|---|
| DEC-0001 | Greenfield builds; brownfield/ambiguous extend | Resolves the assignment's "from scratch" vs. "complete it" contradiction |
| DEC-0002 | ~~Python + FastAPI + SQLite~~ *(superseded by DEC-0012)* | One language across both layers; free OpenAPI; migration is visible |
| DEC-0003 | **Custom executor, no workflow framework** | The most-graded component must be explainable line by line |
| DEC-0004 | ~~`asyncio`, not threads~~ *(superseded by DEC-0013)* | I/O-bound work; no locks around the audit trail |
| DEC-0005 | JSON state + JSONL lineage + JSON metrics | Separated by mutability; a reviewer can `cat` the evidence |
| DEC-0006 | Five structural high-impact criteria | Risk-tiered gates; avoids approval fatigue |
| DEC-0007 | **Gates persist-and-exit at node boundaries** | Provable pause; designs out LangGraph's re-execution hazard |
| DEC-0009 | Redirect is 302, not 301 | A cached 301 breaks click analytics *and* link revocation |
| DEC-0010 | Random base62 + collision check | Enumerability is a security property you cannot retrofit |
| DEC-0012 | **Java 21 + Spring Boot, not Python** | Requirement changed; springdoc-openapi keeps the generated-spec property; plain JDBC keeps the migration visible |
| DEC-0013 | **Virtual threads, not `asyncio`/`StructuredTaskScope`** | DEC-0004's atomicity argument doesn't survive the JVM port; replaced by an explicit lock + single-writer lineage appender; avoids the preview-API flag |

---

## Limitations

Stated plainly; expanded in `docs/architecture.md` §8.

1. **Not yet implemented end-to-end** — structure is real, bodies are `TODO(impl)`.
2. **No crash-durability mid-node.** A process killed while a node runs loses that
   node's progress. Gates are the durable checkpoints. Temporal solves this properly;
   we chose not to pay its cost (DEC-0003).
3. **Single process, single machine.** No distributed execution.
4. **No authentication on approvals.** The gate trusts whoever can write the file —
   fine locally, inadequate for a real deployment.
5. **Exit gates catch structural failures, not subtle design mistakes.** A human still
   owns final quality. That is the stated principle, not a workaround.
6. **Metrics are per-run** with a thin cumulative roll-up; percentiles are withheld
   until enough runs exist to make them mean anything.

---

## Language

Originally specified for Python; ported to **Java 21 + Spring Boot** by DEC-0012,
with the concurrency model correspondingly re-derived by DEC-0013 (virtual threads,
not `asyncio`). Both decisions supersede — and reference — the entries they
replace; `docs/decision-log.md` records the full reasoning and is append-only, so
the superseded entries remain exactly as originally written.

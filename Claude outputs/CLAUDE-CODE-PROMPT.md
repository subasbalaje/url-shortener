# Claude Code Build Prompt — Agentic SDLC Orchestrator (Java)

> **How to use this.** Open the `agentic-sdlc-orchestrator` folder in VS Code, start
> Claude Code in it, and paste everything below the line as your first message. It is
> written to be handed over verbatim.

---

## PROMPT BEGINS

You are building out this repository. Before writing a single line of code, read these
files in this order — they are the complete design and they are authoritative:

1. `CLAUDE.md` — your operating contract in this repo. Autonomy boundary, gate boundary,
   lineage law, failure law. **This governs everything you do here.**
2. `docs/architecture.md` — components, orchestration model, the 8 roles, risks, limitations.
3. `docs/orchestration-graph.yaml` — **the DAG itself**. 7 nodes with gates, retry policies,
   rollback actions, re-plan triggers. This file is data and stays language-neutral.
4. `docs/decision-log.md` — DEC-0001..DEC-0011, each with alternatives and rationale.
5. `docs/example-run.md` — the worked trace. **§9 is your test specification: 18 assertions.**
6. `docs/research-notes.md` — the sourced evidence behind every decision.
7. `scenarios/greenfield.md`, `brownfield.md`, `ambiguous.md` — three distinct traversals.
8. `orchestrator/README.md` — build order and per-file status.
9. `src/README.md`, `tests/README.md` — the shortener spec and testing strategy.

Do not skim these. Every question about *what to build* is answered in them. Your job is
*how to build it in Java*, and to actually finish it.

---

## 0. THE ONE BIG CHANGE: Python → Java

The repo was designed with a Python implementation in mind. **We are switching to Java.**
The *design* is language-neutral and survives intact; the *implementation decisions* do not.

### 0.1 Before you touch any code — append two decision-log entries

`docs/decision-log.md` is **append-only** (see `CLAUDE.md` §4). Do not edit DEC-0002 or
DEC-0004. Append DEC-0012 and DEC-0013 that supersede them, following the exact format of
the existing entries (Date / Actor / Status / Decision / Alternatives considered /
Rationale / Consequences).

**DEC-0012 — Switch implementation language from Python to Java** supersedes DEC-0002.
Cover: Java 21 LTS + Spring Boot 3.x + Maven + SQLite replaces Python/FastAPI/SQLite;
springdoc-openapi replaces FastAPI's automatic OpenAPI generation (same benefit — the spec
is generated from the code, so it cannot drift); one language still spans orchestrator and
service, which was DEC-0002's decisive factor and still holds. Alternatives to record:
staying on Python (rejected — requirement changed), Kotlin (rejected — less universally
reviewable), Java without Spring e.g. Javalin (rejected — we would hand-write the OpenAPI
spec, losing the anti-drift property).

**DEC-0013 — Concurrency model: virtual threads** supersedes DEC-0004. This one needs care,
because **DEC-0004's central argument does not survive the port.** Its reasoning was that
single-threaded asyncio makes state transitions atomic between await points, so the audit
trail needs no locking. On the JVM, virtual threads are *genuinely concurrent* — that
guarantee is gone. State it plainly and say how it is replaced:

- Shared run state is confined to the executor and mutated **only** while holding a single
  `ReentrantLock`, or via concurrent collections with explicit compare-and-set.
- Lineage writes are serialized through one synchronized appender (single-writer preserved,
  which was the property that mattered).
- Node agents receive immutable inputs and return immutable results; they never touch `Run`.

Also record: `StructuredTaskScope` (JEP 505/525) is **still a preview API through JDK 26**
and requires `--enable-preview` at compile and run time. We therefore use **stable virtual
threads** (JEP 444, final in Java 21) and wrap the fan-out in our own small `StructuredScope`
helper that mirrors structured-concurrency semantics. Rationale: a reviewer must be able to
`mvn test` on a stock JDK 21 LTS with no preview flags; the wrapper isolates the API so
adopting `StructuredTaskScope` later is a one-file change.

Alternatives to record: `StructuredTaskScope` directly (rejected — preview, needs
`--enable-preview`, may break between JDKs), platform-thread `ExecutorService` +
`CompletableFuture` (rejected — heavier, and pooling gives nothing for I/O-bound agent work).

### 0.2 Then delete the Python skeletons

After the decision-log entries are appended, delete `orchestrator/*.py` and
`orchestrator/__init__.py`. Their design intent is fully captured in the docs and the new
Java code; leaving a second non-running implementation in the repo would contradict the
project's own "never claim something is built when it isn't" rule.

### 0.3 Then update the docs that reference Python

Update — do not rewrite from scratch, preserve the substance and the reasoning:

- `README.md` — setup (Maven/JDK), all run commands, the decisions table (add DEC-0012/0013).
- `orchestrator/README.md` — the status table and build order, retargeted to Java classes.
- `src/README.md` — Spring Boot stack, Java package layout, scaffold order.
- `tests/README.md` — JUnit 5 layout, `mvn test` commands, same assertions.
- `docs/example-run.md` — the CLI invocations become `java -jar ...` / `mvn` form.
  **Keep every number, state transition and metric value identical** — they were computed
  from the graph, not invented, and the trace is your test spec.
- `docs/architecture.md` — the asyncio references in §3.4 and risk 10; add a note that
  DEC-0013 supersedes DEC-0004. Leave the Mermaid diagram alone; it is language-neutral.

`docs/orchestration-graph.yaml` needs **no changes** — that is the point of the graph being
data. Verify this claim rather than assuming it: nothing in it is Python-specific.

---

## 1. TARGET LAYOUT

```
agentic-sdlc-orchestrator/
  pom.xml
  CLAUDE.md
  README.md
  docs/                      (as-is, updated per §0.3)
  scenarios/                 (as-is, updated where they name Python)
  src/
    main/
      java/com/sdlc/
        orchestrator/
          model/        GraphSpec NodeSpec Gate GateCondition RetryPolicy
                        RollbackAction RePlanTrigger Artifact ApprovalRecord
                        Run NodeRuntime AttemptRecord
                        NodeState RunState GateType Decision  (enums)
          graph/        GraphLoader GraphValidator GraphAlgorithms
          exec/         GraphExecutor StructuredScope FailureLadder AgentDispatcher
          gates/        GateEvaluator ConditionChecker CheckerRegistry
                        PolicyEngine GateManager
          lineage/      DecisionLogger LineageEntry EventType
          metrics/      MetricsCollector RunMetrics NodeMetrics
          agents/       StageAgent (iface) + 6 implementations
          cli/          OrchestratorCli
        shortener/
          model/        Link ClickEvent
          store/        LinkStore (iface) SqliteLinkStore
          service/      LinkService CodecService ValidationService CacheService
          web/          LinkController RedirectController HealthController
          config/       AppConfig RateLimitFilter
        ShortenerApplication.java
      resources/
        application.yml
        db/migration/V1__init.sql
    test/
      java/com/sdlc/...      (mirrors main)
      resources/graphs/      valid.yaml cyclic.yaml dangling-dep.yaml
                             bad-parallel-claim.yaml policy-drift.yaml
  runs/                      (gitignored)
```

Update `.gitignore` for Java: `target/`, `*.class`, `*.jar`, `.mvn/`, keep `runs/` and `*.db`.
Also delete the leftover `.probe` and `commit-message.txt` files if still present.

---

## 2. STACK

- **Java 21 LTS** (`maven.compiler.release=21`). **No `--enable-preview` anywhere.**
- **Spring Boot 3.3+**: `spring-boot-starter-web`, `-validation`, `-test`
- **springdoc-openapi-starter-webmvc-ui** — generated OpenAPI, replacing FastAPI's
- **org.xerial:sqlite-jdbc** — plain JDBC, **no JPA/Hibernate**. Deliberate: the brownfield
  migration must be visible, inspectable SQL. An ORM's auto-DDL would hide exactly the
  schema change the human gate exists to review.
- **snakeyaml** — graph parsing
- **Jackson** — JSON state/lineage/metrics
- **JUnit 5 + AssertJ + Mockito**, `spring-boot-starter-test`
- **jacoco-maven-plugin** — 70% threshold on `orchestrator` packages (matches the
  `coverage_threshold` gate in the graph)

Enable virtual threads in `application.yml`:
```yaml
spring:
  threads:
    virtual:
      enabled: true
```

---

## 3. CODE YOU MUST MATCH

These are binding on shape and semantics. Expand them; do not reinterpret them.

### 3.1 Node state enum — 11 states, exactly as designed

```java
package com.sdlc.orchestrator.model;

/**
 * Lifecycle states of a single node.
 *
 * <p>{@link #BLOCKED_ON_GATE} is a first-class state, not a boolean flag — mirroring
 * Airflow's {@code awaiting_input}. {@link #UPSTREAM_FAILED} is tracked separately from
 * {@link #FAILED} so one root cause is counted once in the success-rate metric.
 * {@link #COMPLETED} means the agent succeeded <em>and</em> the exit gate passed.
 */
public enum NodeState {
    PENDING, READY, RUNNING, BLOCKED_ON_GATE,
    FAILED, RETRYING, ROLLED_BACK,
    COMPLETED, SKIPPED, UPSTREAM_FAILED, STALE;

    /** States satisfying a dependent's join condition. SKIPPED counts: a conditional
     *  node that legitimately did not apply must not block its dependents. */
    public boolean isTerminalSuccess() { return this == COMPLETED || this == SKIPPED; }

    public boolean isTerminalFailure() { return this == ROLLED_BACK || this == UPSTREAM_FAILED; }

    public boolean isActive() { return this == RUNNING || this == RETRYING || this == BLOCKED_ON_GATE; }
}
```

### 3.2 RetryPolicy — Temporal's five fields, exact backoff

Must reproduce the documented intervals: attempt 2 → 2.0s, attempt 3 → 4.0s, attempt 4 → 8.0s.

```java
package com.sdlc.orchestrator.model;

import java.util.Set;

/**
 * Bounded retry configuration, mirroring Temporal's five-field model.
 *
 * <p>{@code maxAttempts} is <em>total tries</em>, so 1 means no retry. {@code
 * nonRetryableErrors} encodes the transient-vs-permanent taxonomy declaratively: a
 * validation error will not become valid by waiting. The literal {@code "*"} marks every
 * error non-retryable — used by {@code apply_migration}, where blind retry of
 * partially-applied DDL can compound damage.
 */
public record RetryPolicy(
        int maxAttempts,
        double initialIntervalSeconds,
        double backoffCoefficient,
        double maxIntervalSeconds,
        Set<String> nonRetryableErrors
) {
    public static RetryPolicy defaults() {
        return new RetryPolicy(3, 2.0, 2.0, 60.0,
                Set.of("ValidationError", "PolicyViolationError", "HumanRejectedError"));
    }

    /** Delay before {@code attempt} (1-indexed), capped at {@code maxIntervalSeconds}. */
    public double backoffFor(int attempt) {
        if (attempt <= 1) return 0.0;
        double raw = initialIntervalSeconds * Math.pow(backoffCoefficient, attempt - 2);
        return Math.min(raw, maxIntervalSeconds);
    }

    public boolean isRetryable(String errorType) {
        return !nonRetryableErrors.contains("*") && !nonRetryableErrors.contains(errorType);
    }
}
```

### 3.3 Kahn's algorithm — ordering, cycle detection, and the ready frontier

One algorithm serving three requirements. Cycle detection must **name the offending nodes**;
"graph has a cycle" is not actionable.

```java
package com.sdlc.orchestrator.graph;

/**
 * Pure graph algorithms. No I/O, no executor, no concurrency — unit-testable in isolation.
 * This is where hand-rolled orchestrators actually break, so test it hard.
 */
public final class GraphAlgorithms {

    /**
     * Valid execution order via Kahn's algorithm.
     *
     * <p>Operates on the <b>edge set only</b>. Retry self-loops are invisible here by
     * design — they live in the node state machine, not the DAG — which is the formal
     * reason bounded retry does not make the graph cyclic.
     *
     * @throws GraphValidationException naming the nodes in the cycle
     */
    public static List<String> topologicalOrder(GraphSpec graph) {
        Map<String, Integer> inDegree = new HashMap<>();
        for (NodeSpec n : graph.nodes()) inDegree.put(n.id(), n.dependsOn().size());

        Deque<String> queue = inDegree.entrySet().stream()
                .filter(e -> e.getValue() == 0).map(Map.Entry::getKey).sorted()
                .collect(Collectors.toCollection(ArrayDeque::new));

        List<String> order = new ArrayList<>();
        while (!queue.isEmpty()) {
            String id = queue.poll();
            order.add(id);
            for (String dep : graph.dependentsOf(id)) {
                if (inDegree.merge(dep, -1, Integer::sum) == 0) queue.add(dep);
            }
        }
        if (order.size() != graph.nodes().size()) {
            Set<String> inCycle = new TreeSet<>(inDegree.keySet());
            inCycle.removeAll(order);
            throw new GraphValidationException(List.of(
                "Cycle detected. Nodes in or behind the cycle: " + inCycle));
        }
        return order;
    }

    /**
     * Successive waves of mutually-independent nodes. Each wave IS a parallelism
     * opportunity, derived from the edge set rather than declared.
     *
     * <p>For the SDLC graph this yields:
     * {@code [requirements] [design] [apply_migration] [implementation]
     *         [documentation, testing] [release_readiness]}
     */
    public static List<Set<String>> parallelFrontiers(GraphSpec graph) { /* peel in-degree-0 sets */ }

    /**
     * Verify each node's declared {@code can_run_parallel} against computed frontiers
     * (DEC-0011). The declaration documents intent and is VERIFIED; it never schedules.
     * Catches the dangerous edit: adding a dependency between two nodes still flagged
     * parallel, which a declaration-driven scheduler would run concurrently in violation
     * of that dependency.
     *
     * @return mismatch descriptions; empty means consistent
     */
    public static List<String> validateParallelismClaims(GraphSpec graph) { /* ... */ }

    /** Longest weighted path = the latency floor. Relax edges in topological order, O(V+E). */
    public static CriticalPath criticalPath(GraphSpec graph, Map<String, Double> durations) { /* ... */ }
}
```

### 3.4 StructuredScope — the fan-out wrapper (replaces `asyncio.gather`)

Isolates the concurrency API so `StructuredTaskScope` can drop in later.

```java
package com.sdlc.orchestrator.exec;

/**
 * Structured fan-out over virtual threads.
 *
 * <p>Wraps {@code Executors.newVirtualThreadPerTaskExecutor()} (JEP 444, final in Java 21)
 * rather than {@code StructuredTaskScope}, which remains a preview API through JDK 26 and
 * would force {@code --enable-preview} on anyone running the tests (DEC-0013). This class
 * is the single point of change when it finalizes.
 *
 * <p><b>Failures do not cancel siblings</b> — the Java equivalent of asyncio's
 * {@code return_exceptions=True}. Testing and documentation are independent branches: a
 * testing failure must not discard completed documentation work. Each result is routed to
 * success/failure handling individually.
 */
public final class StructuredScope {

    public record Outcome<T>(String label, T value, Throwable error) {
        public boolean succeeded() { return error == null; }
    }

    /** Run all tasks concurrently, await all, and return every outcome. */
    public static <T> List<Outcome<T>> forkAll(Map<String, Callable<T>> tasks, Duration timeout)
            throws InterruptedException {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Map<String, Future<T>> futures = new LinkedHashMap<>();
            tasks.forEach((label, task) -> futures.put(label, executor.submit(task)));

            List<Outcome<T>> outcomes = new ArrayList<>();
            for (var entry : futures.entrySet()) {
                try {
                    outcomes.add(new Outcome<>(entry.getKey(),
                            entry.getValue().get(timeout.toMillis(), TimeUnit.MILLISECONDS), null));
                } catch (ExecutionException e) {
                    outcomes.add(new Outcome<>(entry.getKey(), null, e.getCause()));
                } catch (TimeoutException e) {
                    entry.getValue().cancel(true);
                    outcomes.add(new Outcome<>(entry.getKey(), null, e));
                }
            }
            return outcomes;
        }
    }
}
```

### 3.5 The executor main loop and the failure ladder

```java
/**
 * Executes a GraphSpec, producing a governed, audited Run.
 *
 * <p>Owns orchestration mechanics — traversal, state, gates, retries, rollback, lineage,
 * metrics — and is the ONLY component permitted to mutate run state. It has zero authority
 * over content: it never judges whether a design is good, only whether the declared exit
 * gate passed. That separation is what lets the lineage be trusted.
 *
 * <p><b>Concurrency (DEC-0013):</b> virtual threads are genuinely concurrent, unlike the
 * asyncio model this replaced. All mutation of {@code run} happens under {@code stateLock}.
 */
public class GraphExecutor {
    private final ReentrantLock stateLock = new ReentrantLock();

    public Run runGraph(String rawRequest, String mode) throws InterruptedException {
        // initialise run, lineage 'run_started'
        while (true) {
            List<String> ready = computeReady();
            if (ready.isEmpty()) break;                 // terminal, blocked, or deadlocked
            List<String> gated = ready.stream()
                    .filter(this::needsApprovalAndNotYetApproved).toList();
            if (!gated.isEmpty()) {
                enterGate(gated.get(0));                // persist + RETURN: process exits
                return run;
            }
            dispatchFrontier(ready);                    // parallelism happens here
        }
        finalise();
        return run;
    }
}
```

Failure ladder — Step Functions' documented Retry-then-Catch ordering:

```java
/**
 * retry -> rollback -> safe-stop.
 *
 * <pre>
 *   non-retryable error?     -> skip straight to rollback
 *   attempts remaining?      -> RETRYING, backoff, re-dispatch
 *   rollbackAction defined?  -> compensate, ROLLED_BACK
 *   otherwise                -> SAFE-STOP
 * </pre>
 *
 * <p>Special case — {@code onPersistentFailure} on the testing node: persistent test
 * failure usually means the CODE is wrong, not that tests are flaky. Rather than burning
 * retries on a node correctly reporting a real problem, mark {@code implementation} STALE
 * and push work upstream. Bounded by {@code maxFeedbackLoops}.
 *
 * <p><b>If rollback itself fails, stop.</b> Do not retry the rollback or improvise:
 * compensation failing means our model of system state is wrong, and acting further on a
 * wrong model is how a small incident becomes a large one.
 */
```

### 3.6 Gate checkers — registry, so gates stay declarative

```java
/**
 * A checker answers one question about run state. Returning a message rather than throwing
 * lets the executor collect ALL failures in one pass, so a retrying agent fixes everything
 * at once instead of discovering problems serially.
 */
@FunctionalInterface
public interface ConditionChecker {
    CheckResult check(Run run, NodeSpec node, Map<String, Object> params);
}

public record CheckResult(boolean passed, String message) {
    public static CheckResult pass() { return new CheckResult(true, ""); }
    public static CheckResult fail(String msg) { return new CheckResult(false, msg); }
}
```

`CheckerRegistry` maps the YAML `type` string to an implementation and **throws on unknown
types** — fail-closed. A gate that silently skips a condition it does not understand is
worse than no gate, because it still looks enforced.

All 20 checkers from `orchestrator/gates.py` must be ported. The ones carrying real weight:

- `ambiguitiesResolvedOrEscalated` — **must be able to fail**, or `scenarios/ambiguous.md`
  is theatre. Each ambiguity needs `resolution`+`assumption`+`rationale`, or
  `escalatedToHuman: true`.
- `noTestWeakening` — compares test/assertion counts against the previous run.
- `recommendationConsistentWithEvidence` — blocks "go" alongside failing tests.
- `apiContractConformance` — this is the one that catches the bug in the worked trace.

⚠️ `conditionalRequired` evaluates guards like `run_context.mode == 'brownfield'`. **Write a
tiny restricted evaluator** — field lookup, comparison operators, literals. Do **not** wire
in a scripting engine (Nashorn, Groovy, SpEL with full evaluation). The graph is a data file
defining a sandbox; arbitrary code execution from it would let a graph edit escape the
sandbox the graph exists to define.

### 3.7 Append-only lineage

```java
/**
 * Appends lineage entries. The only writer of the audit trail.
 *
 * <p><b>Append-only is enforced structurally</b>, not by convention: the file is always
 * opened with {@code StandardOpenOption.APPEND} and no method offers update or delete.
 * The class simply provides no way to rewrite history.
 *
 * <p>Flushes per entry — a deliberate durability-over-throughput trade, since buffered
 * lineage lost in a crash would be missing exactly the entries explaining the crash.
 *
 * <p>Ordering rule: <b>lineage is written before state</b>. An extra entry for a transition
 * that did not complete is harmless and self-evident; a missing entry for one that did is a
 * hole in the audit trail.
 */
public class DecisionLogger {
    private final Object writeLock = new Object();   // single-writer under virtual threads

    public void append(LineageEntry entry) throws IOException {
        synchronized (writeLock) {
            Files.writeString(lineagePath, entry.toJsonLine() + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }
}
```

### 3.8 Gate persist-and-exit — the governance-critical mechanism

```java
/**
 * Surfaces pending approvals and records human decisions (DEC-0007).
 *
 * <p>The pause is a FILE ON DISK plus a process that has EXITED. That is what makes it
 * provable: a reviewer can inspect pending_approval.json, kill the machine, and still
 * resume. A gate that blocks in memory is indistinguishable from Thread.sleep().
 *
 * <p>Gates sit at NODE BOUNDARIES, never mid-node, so no partial execution is ever
 * suspended and node authors owe no idempotency guarantee.
 */
public class GateManager {

    /**
     * Check the approved artifacts have not changed since the decision.
     *
     * <p>This is what makes approval binding meaningful. Without it, a re-planned artifact
     * would silently inherit approval granted for its predecessor.
     */
    public ValidityResult verifyApprovalStillValid(ApprovalRecord record,
                                                   Map<String, Artifact> current) { /* ... */ }
}
```

`Run.save()` must be **atomic**: write to a temp file, then
`Files.move(tmp, target, ATOMIC_MOVE, REPLACE_EXISTING)`. A torn `state.json` makes a paused
run unresumable — losing exactly the state the gate exists to protect.

### 3.9 Artifact hashing — stable, or re-planning misfires

```java
/**
 * SHA-256 over canonical JSON. Key ordering MUST be stable
 * ({@code MapperFeature.SORT_PROPERTIES_ALPHABETICALLY} +
 * {@code SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS}) — otherwise map iteration order
 * registers as a content change and triggers spurious re-planning and approval invalidation.
 */
public static String computeHash(Object content) { /* first 16 hex chars */ }
```

---

## 4. THE URL SHORTENER

Full spec in `src/README.md`. Java specifics:

**Short codes (DEC-0010):** `java.security.SecureRandom`, **never `java.util.Random`**.
7 chars base62, `UNIQUE` constraint, bounded collision retry (max 5, then throw). Random over
a counter because sequential codes are enumerable — anyone can walk the space and harvest
every "unlisted" link, and unpredictability cannot be retrofitted.

**Redirect (DEC-0009):** **302, not 301**, with `Cache-Control: no-store`. A cached 301 means
later clicks never reach the origin, which destroys click analytics *and* keeps a
disabled link redirecting. Expired/disabled → **410 Gone** (the resource existed and was
deliberately retired), unknown → 404.

**URL validation** — the most security-sensitive class in the service. A shortener is an open
redirector by design. Per OWASP SSRF guidance:
- scheme allowlist: `http`/`https` only
- block loopback `127.0.0.0/8` `::1`, RFC1918 `10/8` `172.16/12` `192.168/16`, link-local,
  and specifically the metadata endpoint `169.254.169.254`
- **resolve DNS via `InetAddress.getAllByName()` and validate every resolved IP** — a public
  hostname resolving to `127.0.0.1` otherwise walks straight through
- do not follow redirects during validation
- reserved-word list for custom aliases (`api`, `admin`, `health`, `static`)
- self-shortening prevention

Use `InetAddress.isLoopbackAddress()`, `isSiteLocalAddress()`, `isLinkLocalAddress()`, plus an
explicit metadata-IP check.

**Storage:** plain JDBC behind a `LinkStore` interface — the seam that makes "swap to
Postgres" a swap rather than a rewrite. Try-with-resources on every `Connection`/
`PreparedStatement`. Parameterized queries only.

**Rate limiting:** token bucket per API key/IP in a servlet `Filter`; stricter on create than
redirect; `429` + `Retry-After`.

---

## 5. BUILD ORDER

Dependency-ordered. Each step is testable before the next depends on it. **Write tests
alongside each step, not at the end.**

1. `pom.xml`, package skeleton, `.gitignore`. `mvn compile` green.
2. **Decision log DEC-0012/0013, delete Python, update docs** (§0). Do this early — the
   lineage law says decisions get recorded when made, not retroactively.
3. `model/` — records and enums. Pure data, no behaviour.
4. `graph/GraphLoader` + `GraphValidator` — all 9 validation checks from the
   `load_graph` docstring. **Fail-closed, and collect every failure rather than stopping at
   the first.**
5. `graph/GraphAlgorithms` — topo sort, frontiers, parallelism validation, critical path.
   Pure functions. **Test hardest here.**
6. `gates/` — registry, then all 20 checkers, then `PolicyEngine`.
7. `lineage/` + `metrics/` — instrumentation before the loop that calls it.
8. `exec/StructuredScope`, then `GraphExecutor` main loop, then `FailureLadder`.
9. `gates/GateManager` persist-and-exit + `GraphExecutor.resume()`.
10. Re-planning — `markStaleFrom()`.
11. `cli/OrchestratorCli` — `run`, `resume`, `replan`, `status`, `validate`, `metrics`.
12. `agents/` — the six stage agents. **Start with deterministic stub implementations** that
    produce well-formed artifacts, so the orchestrator is provably correct before any model
    call is introduced. This matters: if agents are stubs, a failing test means the
    *orchestrator* is wrong, which is what you are actually building.
13. The shortener under `shortener/`.
14. End-to-end: greenfield run reproducing `docs/example-run.md`.

---

## 6. TESTS — the 18 assertions

`docs/example-run.md` §9 lists them. All must pass. The ones usually skipped and most
load-bearing:

- **#8/#9 — gate survives process death.** Run to the gate; assert `pending_approval.json`
  exists and **exit code is 0**; **discard the executor instance entirely**; construct a
  fresh one and `resume()`. Without discarding the instance this test would pass for a
  `Thread.sleep()`.
- **#10 — stale approval.** Mutate an approved artifact between pause and resume; assert the
  approval is **rejected** and the node re-gated.
- **#11 — retry exhaustion → rollback → safe-stop**, exit code 1.
- **#12 — `UPSTREAM_FAILED`, not `FAILED`**, for dependents of a terminal failure.
- **#13 — join refuses to fire** on partial success; `release_readiness` never runs.
- **#14 — `successRate` excludes skipped and upstream-failed** from the denominator. Fixture
  from §7: 2 completed, 1 failed, 3 upstream-failed, 1 skipped → **0.5**, not 2/7.
- **#16/#17 — re-plan** stales only descendants with matching triggers (`apply_migration` is
  **not** staled by a `requirement_spec` change), and preserves lineage for untouched nodes.
- **#18 — lineage is append-only**: snapshot the file, append, assert the snapshot is a
  strict prefix.

Also verify the graph facts, which were computed from the YAML, not asserted:
7 nodes, acyclic; order `requirements → design → apply_migration → implementation →
documentation → testing → release_readiness`; wave 4 = `{testing, documentation}`;
backoff 2.0s / 4.0s / 8.0s.

---

## 7. RULES

From `CLAUDE.md` — these bind you:

1. **Stop and ask** before: schema changes, deletions, release/tagging, `git commit`/`push`,
   anything irreversible. Deleting the Python files in §0.2 is pre-approved by this prompt;
   nothing else is.
2. **`docs/decision-log.md` is append-only.** Never edit an entry. Reverse by appending.
3. **Never weaken a test to make the suite pass.** No `@Disabled` to get green, no relaxed
   assertions. You are implementing a `noTestWeakening` gate — hold yourself to it.
4. **Never claim something works when it is a stub.** Update `orchestrator/README.md`'s status
   table in the same change that implements something.
5. **Log nontrivial decisions with rationale** as you go.
6. **Fail closed** — unknown gate type, unparseable graph, missing checker → throw.
7. **Never execute agent-written code in-process.** Compile checks run in a subprocess,
   otherwise the thing being governed runs inside its governor.

---

## 8. WHERE TO START

Report back after step 5 (`GraphAlgorithms` + its tests) with: what compiles, what the tests
assert, and the computed topological order and frontiers. That is the earliest point where
the orchestrator's correctness is demonstrable, and I want to see it before you build on it.

Work through the build order. Ask when you hit a gate.

## PROMPT ENDS

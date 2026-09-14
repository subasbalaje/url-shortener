# Scenario 3 — Ambiguous: "make it better, faster, safer"

**Scenario type:** under-specified request.
**Per DEC-0001,** this falls under "complete and improve it" and operates on the
existing codebase.

**The request** (candidate #4 from research-notes §7):

> *"Make it better, faster, safer."*

**Why this request was chosen.** It is under-specified along **three independent axes
at once**, with no way to proceed without either a human decision or an explicitly
stated assumption. That is precisely the behaviour Requirement 1 grades.

**What makes this scenario structurally different** — and it is the most important
difference of the three:

| | Greenfield / Brownfield | Ambiguous |
|---|---|---|
| Requirements exit gate | passes on attempt 1 | **FAILS on attempt 1** |
| Ambiguity handling | resolved with stated assumptions | **escalated to a human** |
| First human gate | `apply_migration` or `release_readiness` | **`requirements` — before any design work** |
| Nodes run before the first gate | 2–3 | **1** |

In the other two scenarios the gates fire *after* substantial work. Here the system
**stops at the first node**, because doing design work on a requirement nobody
understands is the expensive mistake this whole scenario exists to prevent.

---

## 1. The Requirements Agent surfaces the ambiguity

### Attempt 1 — the agent guesses, and the exit gate rejects it

The agent's first output resolves all three terms by picking the most common
interpretation:

```json
{
  "normalised_problem": "Improve service performance, reliability and security.",
  "acceptance_criteria": [
    { "id": "AC1", "text": "Reduce redirect latency", "verifiable_by": "perf test" },
    { "id": "AC2", "text": "Add rate limiting", "verifiable_by": "integration test" }
  ],
  "assumptions": [],
  "ambiguity_report": { "ambiguities": [] }
}
```

```
[..] node=requirements attempt=1
  exit_gate (4 conditions, all evaluated):
    field_non_empty(acceptance_criteria)                 PASS
    all_items_have_field(acceptance_criteria, verifiable_by)  PASS
    ambiguities_resolved_or_escalated(ambiguity_report)  FAIL
       -> "An ambiguity was neither resolved with a stated assumption nor
           escalated to a human. Silent resolution is a governance failure."
    field_non_empty(requirement_spec.assumptions)        FAIL
       -> "Assumptions list missing; assumptions must be explicit, not implied."
  exit_gate FAILED (2 of 4)
  RUNNING -> FAILED   error_type=GateFailure
  FAILED -> RETRYING  backoff 1.0s
```

**This is the gate doing the work the assignment grades.** The agent's output was not
*wrong* — reducing latency and adding rate limiting are reasonable readings of
"faster" and "safer". It was **undefensible**: it silently chose among interpretations
and presented the choice as the requirement. `ambiguity_report.ambiguities == []` for
a request as vague as this is itself the evidence of silent resolution.

Note the gate evaluated all four conditions and reported both failures together, so
attempt 2 can fix both at once.

### Attempt 2 — the agent enumerates instead of guessing

```json
{
  "normalised_problem": "UNDER-SPECIFIED. Three independent axes, each with multiple
                         valid interpretations and materially different scope.",
  "ambiguity_report": {
    "ambiguities": [
      {
        "id": "AMB1", "term": "better",
        "interpretations": [
          { "reading": "Developer experience — clearer errors, better docs",
            "effort": "S", "impacted": ["src/api.py", "docs/"] },
          { "reading": "Feature completeness — custom aliases, expiry, bulk create",
            "effort": "L", "impacted": ["src/api.py", "src/models.py", "schema"] },
          { "reading": "Operability — health checks, structured logs, metrics",
            "effort": "M", "impacted": ["src/main.py", "new observability module"] }
        ],
        "escalated_to_human": true,
        "reason": "These share no implementation surface. Choosing wrongly wastes
                   the entire iteration."
      },
      {
        "id": "AMB2", "term": "faster",
        "interpretations": [
          { "reading": "Redirect read latency (p95/p99)", "effort": "M",
            "impacted": ["src/cache.py", "src/storage.py"] },
          { "reading": "Creation-path throughput (writes/sec)", "effort": "M",
            "impacted": ["src/storage.py"] },
          { "reading": "Cold-start / deploy time", "effort": "S",
            "impacted": ["src/main.py"] }
        ],
        "escalated_to_human": true,
        "reason": "Read and write optimisation pull in opposite directions here:
                   more aggressive caching helps reads and worsens write-visibility
                   latency. Cannot optimise both without knowing which matters."
      },
      {
        "id": "AMB3", "term": "safer",
        "interpretations": [
          { "reading": "Abuse prevention — malicious target URLs, phishing",
            "effort": "M", "impacted": ["src/validation.py"] },
          { "reading": "Authentication/authorisation on the API",
            "effort": "L", "impacted": ["src/api.py", "schema", "new auth module"] },
          { "reading": "Data protection — PII handling, encryption at rest",
            "effort": "M", "impacted": ["src/storage.py", "src/models.py"] },
          { "reading": "Operational safety — rate limiting, quotas, circuit breaking",
            "effort": "M", "impacted": ["src/api.py", "new middleware"] }
        ],
        "escalated_to_human": true,
        "reason": "'Safer' spans security, privacy and operational resilience.
                   These are different disciplines with different acceptance tests."
      }
    ]
  },
  "assumptions": [
    { "id": "A1",
      "text": "The request seeks incremental improvement to the existing service,
               not a rewrite.",
      "rationale": "'Make IT better' presupposes the current system persists.
                    Stated because a rewrite reading, while possible, would make
                    every interpretation above moot." },
    { "id": "A2",
      "text": "All three axes are wanted, not a choice of one.",
      "rationale": "The request lists them conjunctively ('better, faster, safer').
                    Stated explicitly because if only one is wanted, scope drops
                    by roughly two thirds." }
  ],
  "recommended_scope": {
    "proposal": "AMB1 -> operability; AMB2 -> redirect read latency;
                 AMB3 -> abuse prevention",
    "rationale": "Highest value per unit risk on the existing codebase. Abuse
                  prevention addresses the standing open-redirect risk already
                  listed as residual in the greenfield readiness report; redirect
                  latency is the service's defining performance characteristic;
                  operability is the cheapest of the 'better' readings and
                  unblocks measuring the other two.",
    "explicitly_deferred": ["authentication (AMB3.2)", "feature work (AMB1.2)"],
    "deferral_reason": "Both are large enough to warrant their own requirement
                        and their own approval."
  }
}
```

```
[..] node=requirements attempt=2
  exit_gate (4 conditions):
    field_non_empty(acceptance_criteria)                 PASS
    all_items_have_field(..., verifiable_by)             PASS
    ambiguities_resolved_or_escalated                    PASS
       -> 3 ambiguities, all escalated_to_human with reasons
    field_non_empty(assumptions)                         PASS (2 assumptions)
  exit_gate PASSED
```

**The distinction the gate enforces.** Attempt 2 does not refuse to have an opinion —
it offers `recommended_scope` with rationale. What changed is that the alternatives
are **visible**, the trade-offs are **named**, and the choice is **the human's to
confirm**. That is the difference between an agent that handles ambiguity and one
that hides it.

---

## 2. The escalation gate

Three escalated ambiguities mean the requirement cannot proceed unresolved:

```
  [GATE] node=requirements — escalation required
         rollback_action: escalate_to_human
         reason: 3 ambiguities marked escalated_to_human; proceeding would
                 commit the run to an interpretation nobody chose.
  node -> BLOCKED_ON_GATE ; run -> AWAITING_APPROVAL
  write pending_clarification.json ; persist state.json ; EXIT
```

**This is the earliest possible stop.** One node has run. No design, no code, no
tests. The cost of stopping here is ~15 seconds of agent time; the cost of *not*
stopping is an entire iteration spent building the wrong thing — and in an agentic
system, that iteration happens fast and looks productive the whole way.

`pending_clarification.json` presents the human with a decision, not a puzzle:

```json
{
  "run_id": "r-20260913-0912-c4e1",
  "node_id": "requirements",
  "type": "clarification_required",
  "reason": "The request is under-specified along 3 independent axes.",
  "ambiguities": [ "...AMB1, AMB2, AMB3 with interpretations and effort..." ],
  "recommended_scope": {
    "proposal": "operability + redirect latency + abuse prevention",
    "rationale": "Highest value per unit risk; addresses the open-redirect risk
                  already flagged as residual in the greenfield readiness report."
  },
  "respond_by": "write clarification.json with either
                 { \"accept_recommendation\": true } or
                 { \"selections\": { \"AMB1\": \"...\", \"AMB2\": \"...\",
                                     \"AMB3\": \"...\" } }"
}
```

**Human responds:**

```json
{
  "approver": "mukesh",
  "accept_recommendation": false,
  "selections": {
    "AMB1": "operability",
    "AMB2": "redirect read latency",
    "AMB3": "abuse prevention AND rate limiting"
  },
  "comment": "Agree on the first two. On 'safer' I want both abuse prevention and
              rate limiting — the greenfield readiness report flagged missing rate
              limiting as a residual risk and it should not ship publicly without
              it. Authentication stays deferred."
}
```

The human **overrode the recommendation** on AMB3 — expanding it — and gave a reason
tied to a specific prior artifact. That is oversight functioning as designed: the
agent framed the decision well enough that a human could disagree usefully.

---

## 3. Decomposition, after the ambiguity is resolved

Only now is decomposition possible. Resolved scope:

| Axis | Resolution | Acceptance criteria |
|---|---|---|
| better → operability | `/health` with dependency checks; structured logs with request ids; `/metrics` | AC1–AC3 |
| faster → redirect latency | Cache-hit path optimisation; index review; measure p95/p99 | AC4–AC5 |
| safer → abuse + rate limiting | Reputation check hook; **token-bucket rate limiting** | AC6–AC8 |

| id | Criterion | Verifiable by |
|---|---|---|
| AC1 | `/health` reports DB connectivity and returns 503 when down | integration test |
| AC2 | Every request logs a correlation id, propagated through the redirect path | unit test |
| AC3 | `/metrics` exposes request counts and latency histogram | integration test |
| AC4 | Redirect p95 improves ≥20% on a warm cache vs. current baseline | perf test |
| AC5 | Cache hit ratio ≥90% under the 80/20 access distribution | perf test |
| AC6 | Creation rate-limited per API key; returns 429 with `Retry-After` | integration test |
| AC7 | Redirects rate-limited per IP at a higher threshold than creation | integration test |
| AC8 | Known-malicious target URLs rejected at creation | unit test |

AC4 is stated **relative to a measured baseline**, which is only possible because
"faster" was pinned to one axis. Against the original request, "faster" had no
testable meaning at all — the clearest evidence that the escalation was worth its cost.

---

## 4. Orchestration

```
wave 0: [requirements]         <-- ⏸ ESCALATION GATE (attempt 2, after gate rejection)
        ... human clarifies ...
wave 1: [design]
wave 2: [apply_migration]      -> SKIPPED (no schema change: rate-limit state is
                                            in-process; observability adds no tables)
wave 3: [implementation]
wave 4: [documentation, testing]
wave 5: [release_readiness]    <-- ⏸ HUMAN GATE
```

**Two human gates, in a different distribution from brownfield.** Brownfield's gates
are mid-run (migration) and end-of-run (release). Here the first gate is at the
*very first node*, before any work is committed — governance applied where the risk
actually is, which in an ambiguous request is at the point of interpretation.

`apply_migration` skips again: rate-limit counters live in process memory and the
observability work adds no tables. Three scenarios, three different behaviours from
this one node — skipped, gated-and-run, skipped-for-a-different-reason.

---

## 5. Validation approach

Ambiguous-request validation has a concern the other two scenarios do not: **did we
build what was actually agreed?** The risk is not only defective code but *correct
code solving the wrong problem*.

| Risk | Validation |
|---|---|
| **Built the wrong interpretation** | Every AC traces to a specific resolved ambiguity; the human's `clarification.json` is retained in lineage as the authority for scope |
| **Scope creep beyond the agreement** | `release_readiness` checks acceptance criteria against the *resolved* scope; deferred items (auth, feature work) must remain absent |
| "Faster" unmeasurable | AC4 fixed to p95 against a recorded pre-change baseline |
| Rate limiting locks out legitimate traffic | AC6/AC7 boundary tests at and around the threshold; redirect limit deliberately higher than creation |
| Observability leaks PII into logs | Unit test asserting no target URLs or IPs in structured log output |
| Cache optimisation serves stale targets | Existing disable/expire tests run as regression; TTL bounds asserted |
| Abuse check adds creation latency | Reputation hook is fail-open with a timeout — a slow external check must not block creation |

**Assumptions carried into the readiness report:** A1 (incremental, not rewrite) and
A2 (all three axes wanted) remain *assumptions*, not facts. They are listed explicitly
so the final human review can catch it if either was wrong — which is exactly what the
assignment's Requirement 8 asks for.

---

## 6. What this scenario proves

1. **Ambiguity is surfaced, not silently resolved** (Req 1). The exit gate **rejected**
   a plausible-looking first attempt precisely because it resolved silently.
2. **The gate has teeth.** It is not decorative: it fired, blocked progress, and forced
   a materially better second attempt.
3. **Escalation happens at the cheapest possible moment** — one node in, before any
   design or code.
4. **The human genuinely decided**, overriding the recommendation on one axis with a
   reason grounded in a prior artifact.
5. **Assumptions survive into the final summary** as assumptions, not as facts.
6. **This traversal is distinct from both other scenarios**: different gate placement,
   a gate failure and retry at the root node, and a different reason for skipping
   `apply_migration`.

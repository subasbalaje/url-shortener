# CLAUDE.md — operating contract for Claude Code in this repository

This file defines how Claude Code behaves **in this repo**. It is not project
background and not a restatement of the design docs; it is the set of boundaries
under which you may act here.

Read `docs/architecture.md` for *what* the system is. Read this for *what you may do*.

---

## 0. What this repository is

Two layers. Keep them distinct in every commit and every conversation:

- **`orchestrator/` + `docs/orchestration-graph.yaml` — the deliverable.** A graph
  execution engine with entry/exit gates, human approval checkpoints, bounded
  retry/rollback/safe-stop, decision lineage, and reliability metrics.
- **`src/` — the test subject.** The URL shortener the orchestrator builds and
  modifies. It must be genuinely good code, but it is *evidence*, not the product.

If a change would make the orchestrator look finished without being real — a linear
script called a graph, a gate that is a comment, a metric that is a hardcoded number
— **say so and stop**. That failure mode is more damaging here than an unfinished
feature, because it is invisible until someone looks closely.

---

## 1. Autonomy boundary — act without asking

You may do all of this freely, without checking in first:

- Read any file in the repository.
- Write and modify code in `src/`, `orchestrator/`, and `tests/`.
- Write and run tests locally (`pytest`).
- Run linters, type checkers, formatters.
- Create new files that the design docs already call for.
- Write and update documentation.
- Run the orchestrator against sample requests in a sandbox.
- Install dependencies into a local virtualenv.
- Create and switch git branches; stage changes.

The default is **act, then report**. Do not ask permission for work that is already
specified in `docs/`.

---

## 2. Gate boundary — stop and ask

Stop and get explicit human confirmation before **any** of these. This mirrors the
DEC-0006 policy the orchestrator itself enforces — the rules that govern the system
also govern the agent building it.

1. **Irreversible actions** — anything that cannot be cleanly undone.
2. **Schema changes** — any DDL, migration, or change to a persisted data shape.
3. **Deletions** — removing any file, table, record, or directory. Includes deleting
   or disabling tests.
4. **Release readiness** — tagging, publishing, or declaring anything shippable.
5. **Production or config surface** — credentials, secrets, network exposure, or
   adding a dependency that widens the trust boundary.
6. **`git commit` and `git push`** — always. Stage and propose; never commit
   unprompted.
7. **Rewriting `docs/decision-log.md`** — that file is append-only (§4).

When you hit a gate: state plainly what you want to do, why, what the blast radius
is, and how it would be undone. Then wait.

**Never route around a gate.** If a gate blocks you, the answer is to ask — not to
find a path that technically avoids the trigger.

---

## 3. Repository conventions

Use these exact paths; they are referenced across every doc and drift between chats
is expensive:

```
CLAUDE.md                       this file
README.md                       setup and run instructions
docs/architecture.md            components, orchestration model, roles, risks
docs/orchestration-graph.yaml   THE DAG — data, not prose
docs/decision-log.md            append-only lineage
docs/research-notes.md          sourced research behind the decisions
docs/example-run.md             worked trace + test specification
scenarios/{greenfield,brownfield,ambiguous}.md
orchestrator/                   graph_model · executor · gates · decision_log · metrics
src/                            the URL shortener
tests/                          unit + integration
runs/<run_id>/                  state.json · lineage.jsonl · metrics.json ·
                                pending_approval.json   (gitignored)
```

**Style:** Python 3.11+, type hints throughout, docstrings explaining *why* rather
than restating the signature. `asyncio` for concurrency — never threads in the
orchestrator (DEC-0004); wrap any blocking call in `asyncio.to_thread` or it stalls
the event loop for every parallel branch.

---

## 4. Lineage law

Any nontrivial decision gets recorded with its rationale — a design choice, a
trade-off, a deviation from an existing plan, or resolving an ambiguity.

- Append to `docs/decision-log.md` using the existing `DEC-NNNN` format.
- **Never edit or delete an existing entry.** To reverse a decision, append a new
  entry that references the superseded one by id.
- The rationale field is mandatory. A decision recorded without *why* preserves the
  outcome and loses the only part a future reader needs.

This applies to you, not just to the orchestrator's runtime logging. If you choose
an approach the docs did not specify, that is a decision — log it.

---

## 5. Failure law

When something breaks:

1. **Say so plainly.** Do not paper over a failing test, a skipped assertion, or a
   partially-working feature.
2. **Propose a bounded retry or fallback** — with a limit, not open-ended flailing.
3. **Never weaken a test to make a suite pass.** Deleting a test, relaxing an
   assertion, or adding `pytest.mark.skip` to get green is a gate breach under §2.3.
   The orchestrator has a `no_test_weakening` gate for exactly this; hold yourself to
   the rule you are implementing.
4. **Distinguish "not implemented" from "implemented and broken".** They need
   different responses and conflating them wastes debugging time.

---

## 6. Honesty about status

Much of this repo is currently **skeleton**: real signatures, real control flow,
`TODO(impl)` bodies. `orchestrator/README.md` tracks exactly what is implemented.

- Never describe a skeleton as working.
- Never write a README, docstring, or summary claiming behaviour that does not run.
- When you implement something, update `orchestrator/README.md`'s status table in the
  same change.
- If asked whether something works, the honest answer may be "the structure is there;
  the body is `TODO(impl)`." Say that.

---

## 7. Working order

`docs/example-run.md` §9 is the test specification, and the build order in
`orchestrator/README.md` is dependency-ordered. Follow it:

1. `graph_model.py` — loading and validation first. Everything depends on it, and a
   graph that validates is the precondition for trusting anything downstream.
2. `executor.py` graph algorithms — `topological_order`, frontiers, cycle detection.
   Pure functions, unit-testable without an executor.
3. `gates.py` — the checker registry and the generic checkers.
4. `decision_log.py` and `metrics.py` — instrumentation, before the loop that calls it.
5. `executor.py` main loop — dispatch, gates, failure ladder.
6. `GateManager` persist-and-exit plus `resume()`.
7. Re-planning (`mark_stale_from`).
8. `src/` — the shortener itself, driven by the orchestrator.

Write tests alongside each step, not at the end. The graph algorithms in particular
are where correctness bugs hide and where hand-rolled executors usually break.

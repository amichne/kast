---
type: Explanation
title: Semantic Evidence Pressure-Test Gaps
description: Observed gaps between Kast's exact compiler identity, bounded graph evidence, and its public evidence contract.
tags: [internal, compiler, kotlin, semantic-graph, evidence, coverage, pressure-test]
---

# Semantic Evidence Pressure-Test Gaps

**Status:** Open assessment. No pathway in this document is selected.

This document records gaps found during a live pressure test on 2026-08-02.
It separates behavior that breaks an existing contract from behavior that
protects an intentional invariant but has an unwanted effect.

This is an explanation and design input. It is not a fix plan. A later design
decision can select a pathway after it defines compatibility, migration, and
verification requirements.

The existing [compiler evidence explanation](../../explanation/compiler-evidence.md)
defines the intended evidence model. The [graph query flow](flows/graph-queries.md)
defines the generation and coverage boundary.

## Classification rule

This assessment uses two categories:

- **Incorrect behavior** contradicts a stated contract or loses identity or
  evidence that an earlier successful operation already established.
- **Intentional behavior with an unintended effect** protects a stated
  invariant, but the resulting public behavior is hard to use, hard to
  interpret, or too expensive for an agent.

The second category is not a softer name for a defect. It preserves the reason
for the current choice and makes its cost explicit.

## Evidence boundary

The pressure test used development build `0.20.3-20-gcb0e4bd1c` from the
canonical Kast workspace. The headless runtime was `READY`, and the reference
index reported ready. No runtime start, graph refresh, source edit, or database
write was part of the test.

The persisted source evidence reported:

| Fact | Observed value |
|---|---:|
| Generation | 2980 |
| Source files | 772 |
| Indexed | 733 |
| Excluded | 4 |
| Limited | 35 |
| Pending | 0 |
| Failed | 0 |
| Stale | 0 |

Two positive probes establish what must remain possible:

1. An exact compiler identity for `IdeaProjectIndexer.indexProject` returned
   six semantic references from nine same-spelled text matches. It excluded
   the method declaration and an unrelated local lambda with the same name.
2. An exact compiler identity for `AnalysisBackend` produced a depth-three
   impact projection with 212 relationship records over 75 source paths. The
   result contained 114 `CALL`, 42 `TYPE_REF`, 28 `INHERITANCE`, and 28
   `UNKNOWN` records. All records were `HIGH` confidence with a `K2_RESOLVED`
   basis.

The impact projection required 53 four-record pages. All pages were consumed.
The returned count was 212, the final page reported `truncated: false`, and no
continuation remained. Minimum reported index completeness was
`0.9702072538860104`.

The same snapshot also reported limits. Direct `AnalysisBackend` references
were a known minimum of 59. Caller, implementation, and type-hierarchy
operations degraded with `SOURCE_SET_EXCLUDED` and
`FAMILY_SEARCH_INCOMPLETE`. All public summary, topology, and community
projections failed with `GRAPH_EVIDENCE_INCOMPLETE`.

These facts make the boundary precise: Kast can prove many positive semantic
relationships in this snapshot. It cannot claim complete repository-wide
coverage.

## Incorrect behavior

### Discovery candidates do not always round-trip

**Observed behavior:** Discovery returned
`WorkspaceIndexingScope.Companion.resolve` as a candidate. Exact lookup of the
returned fully qualified name then returned `NOT_FOUND`. An overloaded
`parsed` lookup returned `AMBIGUOUS`, but discovery did not return a callable
signature or another selector that exact lookup could accept. Several
reasonable receiver and signature forms also returned `NOT_FOUND`.

**Why this is incorrect:** The public workflow is discovery, exact selection,
then relationship traversal. A displayed candidate is not useful identity if
the next operation cannot select it. This is distinct from correct ambiguity:
refusing to guess is correct; failing to provide a usable discriminator is not.

**Ideal behavior:** Every discovery candidate has one stable value that exact
lookup accepts without reconstructing Kotlin syntax.

Possible pathways:

- Return an opaque selector handle for each discovery candidate. This removes
  signature parsing from clients, but it increases discovery payload size and
  binds candidate issuance to a backend generation.
- Return one canonical callable signature and make exact lookup accept that
  grammar. This is readable and portable, but the grammar becomes a public
  compatibility contract.
- Accept an explicit identity tuple made from fully qualified name,
  declaration file, declaration offset, and kind. This reuses facts already in
  discovery results, but it is verbose and exposes source movement to clients.

### Exact callable identity is lost before impact projection

**Observed behavior:** Exact lookup resolved the top-level function
`closeSourceIndexStoreAfterIndexing` with a compiler-issued selector, file,
offset, and kind. Impact projection for that selector returned
`IMPACT_OVERLOAD_GRANULARITY_UNAVAILABLE`.

**Why this is incorrect:** The selector already distinguishes one declaration.
The impact boundary must not reduce that identity to a name that can collide
with overloads. A later operation lost information that an earlier operation
proved.

**Ideal behavior:** The same overload-safe identity crosses compiler
resolution, persistence, and graph projection.

Possible pathways:

- Store and query one canonical compiler-derived key for each callable. This
  gives the strongest end-to-end identity, but it can require a graph schema
  and index migration.
- Add a generation-bound mapping from selector identity to the current graph
  node key. This limits graph schema change, but the mapping must remain
  atomic with graph updates.
- Return a separate qualified occurrence-based impact result when a canonical
  graph node is absent. This can preserve some value, but it must not claim the
  same coverage as canonical graph impact.

### Empty impact results omit absence evidence

**Observed behavior:** Impact for `GitRemoteParser` returned `AVAILABLE`, a
`totalCount` of zero, and `truncated: false`. The result did not include the
cardinality and coverage fields needed to decide whether zero meant proven
absence or no known relationship under incomplete evidence.

**Why this is incorrect:** Coverage belongs to the result. A zero result is the
case that most needs an explicit proof boundary. `AVAILABLE` plus zero can look
like a complete negative answer even when the source index is limited.

**Ideal behavior:** Empty and non-empty results carry the same evidence
envelope: generation, scope, cardinality, coverage, limitations, truncation,
and continuation state.

Possible pathways:

- Require the full evidence envelope on every impact response. This is
  uniform, but it changes the response schema for all clients.
- Add a typed absence value such as `PROVEN` or `UNPROVEN` with reasons. This
  makes negative proof direct, but it adds a second interpretation layer next
  to coverage.
- Use distinct empty outcomes such as `EMPTY` and `QUALIFIED_EMPTY`. This is
  compact, but clients still need structured coverage to explain the result.

### Graph rejection drops the evidence envelope

**Observed behavior:** Public graph projections returned only
`GRAPH_EVIDENCE_INCOMPLETE`, a generic message, and `next: kast refresh`.
Read-only diagnostics for the same generation could report the source counts
shown above and deterministic graph inventory. The rejection did not state the
generation, requested scope, coverage counts, or limitation codes.

**Why this is incorrect:** The graph query contract states that every result
retains generation, scope, coverage, bounds, and limitations. A rejected proof
must explain which evidence predicate failed. Hiding the graph is correct when
admission fails; dropping the reasoned evidence is not.

**Ideal behavior:** A graph rejection returns the same evidence envelope as a
successful or qualified result, plus one typed failed predicate and one
recovery action that can change that predicate.

Possible pathways:

- Embed the complete coverage envelope in each graph error. This is direct,
  but it makes human error output larger.
- Return a compact error with a stable evidence snapshot identifier that a
  read-only public inspection operation can resolve. This keeps errors short,
  but it adds a second request and snapshot lifetime rules.
- Add a public graph preflight result that returns admission and coverage
  without computing topology. This makes the boundary explicit, but clients
  must call it or handle an equivalent embedded result.

## Intentional behavior with unintended effects

### Runtime readiness is separate but not visible enough

**Intentional behavior:** Runtime readiness, reference-index readiness, and
graph coverage are separate facts. A healthy exact-root runtime can be
`READY` while persisted graph evidence is incomplete. This separation is
correct and must remain.

**Unintended effect:** The compact public status reported `ready: true` and
`referenceIndexReady: true`, but it did not show graph state. Every broad graph
command then failed. A user can read the status as permission for a command
that has a separate admission gate.

**Ideal behavior:** One status view presents independent capability states
without collapsing them into one boolean.

Possible pathways:

- Add a capability matrix for runtime, references, exact symbol operations,
  and graph projections. This is explicit, but it makes the default status
  longer.
- Keep compact status and add graph admission to graph-command preflight and
  failures. This preserves concise output, but the full state remains
  operation-local.
- Provide compact and detailed status modes with the same typed fields. This
  serves both humans and agents, but both projections must stay compatible.

### Global fail-closed graph admission hides useful positive evidence

**Intentional behavior:** Broad topology and community claims fail closed when
the graph cannot prove the required source scope. This prevents a partial
graph from looking complete.

**Unintended effect:** One global incomplete state made module, package, and
symbol projections unavailable even though exact impact queries and read-only
inventory contained many stable positive facts. The public interface had no
middle state between complete graph proof and rejection.

**Ideal behavior:** Strict proof remains the default. Any weaker view has a
different type and cannot support complete-negative, component, community, or
full-impact claims unless those claims remain valid under missing evidence.

Possible pathways:

- Preserve global admission and improve only status, evidence, and recovery.
  This has the lowest semantic risk, but useful topology stays unavailable
  until coverage is repaired.
- Add explicit strict and qualified modes. Qualified mode can return positive
  facts with limitations, but users may ignore the qualifier.
- Add a separately named `OBSERVED` or positive-only projection for proven
  scopes. This protects the meaning of `QUALIFIED`, but it adds another
  evidence state and requires precise rules for missing boundary edges.

### Conservative family coverage removes high-value traversals

**Intentional behavior:** Reference, caller, implementation, and hierarchy
operations return `DEGRADED` and a known minimum when a source set or requested
relationship family is incomplete. They do not turn zero records into proof of
absence.

**Unintended effect:** Explicit implementation and hierarchy operations for
`AnalysisBackend` returned no useful records, while bounded impact contained
28 inheritance records. The correct specialized operation was less useful
than the broader graph projection, and the limitation codes did not identify
the affected source sets or files.

**Ideal behavior:** Specialized relationships remain compiler-authoritative,
and each limitation identifies its affected scope and possible recovery. A
graph-derived fallback must have a separate evidence type.

Possible pathways:

- Expand persistent relationship indexing across the required source sets.
  This improves later queries, but increases indexing time, storage, and
  failure surface.
- Index one missing relationship family or source scope on demand. This limits
  steady-state cost, but adds latency and generation changes to a read path.
- Return persisted graph relationships as an explicit qualified fallback.
  This provides useful positive evidence, but it cannot replace compiler
  family-search completeness.

### Fixed small pages make complete bounded proof expensive

**Intentional behavior:** Impact pages are small and continuation-bound. This
limits response size and prevents silent truncation.

**Unintended effect:** A 212-record bounded projection required 53 sequential
requests. The public command did not expose a bounded page-size choice or an
aggregate result. Completion was correct but expensive in latency and agent
tokens.

**Ideal behavior:** Clients can reduce round trips without bypassing response
limits, generation binding, or continuation checks.

Possible pathways:

- Accept a requested page size up to a server-owned cap. This is simple, but
  large pages can still create poor terminal and model behavior.
- Add a server-side aggregate summary for counts, edge kinds, depths, and
  unique paths. This answers many impact questions cheaply, but it does not
  replace record traversal.
- Add a streaming or consume-all mode with explicit total and byte caps. This
  reduces client orchestration, but interruption and partial-output semantics
  become part of the public contract.

### Typed rejection can look like shell success

**Intentional behavior:** Some relationship failures are typed domain outcomes
on standard output. The process can complete its transport and projection work
even when the requested semantic operation is rejected.

**Unintended effect:** A tampered selector returned
`SELECTOR_HANDLE_REJECTED` with reason `TAMPERED`, but the command exited with
status zero. Shell automation that checks only process status can accept a
semantic rejection as success.

**Ideal behavior:** The public contract states both the typed outcome and the
process-status rule, and offers one safe default for unattended automation.

Possible pathways:

- Map rejected operation outcomes to non-zero process status. This matches
  common shell expectations, but can break clients that treat typed results as
  successful protocol exchanges.
- Add a `fail-on-rejected` mode for automation. This preserves compatibility,
  but clients must opt into the safe shell behavior.
- Keep status zero and require structured-outcome inspection in generated
  clients and examples. This has no process-level compatibility cost, but it
  leaves ad hoc shell use exposed.

### Conservative labels are safe but not actionable enough

**Intentional behavior:** Kast retains `UNKNOWN` edges and broad limitations
instead of inventing a more precise relationship. This is safer than false
specificity.

**Unintended effect:** The impact projection contained 28 `UNKNOWN` records,
and relationship operations reported `SOURCE_SET_EXCLUDED` and
`FAMILY_SEARCH_INCOMPLETE` without the affected scope. A user can preserve the
qualification but cannot decide what evidence is missing or which action could
improve it.

**Ideal behavior:** Conservative classification remains, and every limitation
has a typed reason, affected scope, and supported recovery class.

Possible pathways:

- Add reason codes to each `UNKNOWN` edge and structured details to each
  limitation. This is precise, but increases payload and schema size.
- Keep compact primary results and offer a detailed diagnostic projection for
  selected records or limitations. This limits ordinary output, but adds a
  second lookup.
- Group unknown and limited evidence separately with aggregate reasons. This
  improves prioritization, but record-level cause can remain unavailable.

## What is not a gap

The pressure test also confirmed behavior that pathways must preserve:

- Ranked discovery can return several candidates. That is not an identity
  error when each candidate can later be selected exactly.
- Same-spelled text is not semantic identity. Excluding the unrelated
  `indexProject` lambda was correct.
- Four-record pages are not silent truncation when each page has a valid
  continuation and the final page is terminal.
- `READY` and incomplete graph evidence are not contradictory. They are
  separate gates; the visibility of those gates is the gap.
- `DEGRADED`, known-minimum cardinality, and explicit limitations are evidence
  states, not false failures.
- An `UNKNOWN` edge is not automatically incorrect. Missing reason and scope
  detail is the usability gap.
- Generation-bound continuations and rejection of mismatched or changed
  generations are required consistency checks.

## Ideal interaction outline

An ideal public interaction would keep the following order and proof:

1. Discovery returns ranked candidates. Every candidate includes one exact,
   downstream-accepted identity.
2. Exact lookup returns one compiler identity and one authenticated selector.
3. Status exposes runtime, reference, relationship, and graph states as
   separate facts.
4. Each relationship or graph operation returns one common evidence envelope,
   including empty and rejected results.
5. Strict operations fail closed. A weaker positive-only or qualified mode is
   available only if its evidence type prevents stronger claims.
6. Pagination remains generation-bound, but clients can request bounded
   summaries or larger safe pages.
7. Typed semantic rejection and process status have one documented automation
   rule.

The invariant is the important part: exact compiler identity, semantic edge
provenance, and explicit limits must remain connected. The open choice is how
much qualified evidence to expose and where to expose it.

## Decision inputs for a later design

A later decision should compare the pathways against these questions:

- Which operations must support complete negative proof?
- Which source sets and relationship families are required for that proof?
- Can a positive-only graph projection be made impossible to confuse with a
  complete architecture claim?
- What compatibility promise already exists for selector handles, callable
  signatures, response fields, and process status?
- What page, byte, latency, and indexing budgets must an agent workflow meet?
- Which recovery actions can change each typed limitation without an unrelated
  side effect?

This assessment does not answer those questions and does not select a pathway.

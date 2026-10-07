# Static query execution

The target workload is a current semantic index for 500–750 modules and roughly
30 million Kotlin lines. The acceptance ceiling is 60 seconds for a complete
query, including its retained result and completion evidence. Compilation,
project import, and initial indexing are measured separately. This is an
acceptance target, not a measured capacity claim.

## Execution and composition

Use exact declaration identities and the existing typed pipeline. Apply cheap
name, kind, directory, and source-set constraints before collecting candidates.
`WHERE` filters a stream; `PROJECT_BINDING` selects a named cell; relation
expansion maps each admitted declaration to its related rows. Set operations
combine by exact identity, and joins preserve both cells and their evidence.
These operators already express much of the proposed map/flatMap algebra.

`WALK` expands a deterministic priority frontier and suppresses repeated node
expansion while preserving incoming edge evidence. Its completion proof is
relative to the requested maximum depth. A depth bound cannot prove unrestricted
transitive closure. A composite relation expression per hop also needs a separate
typed contract; the current walk selects one relation kind.

Automatic execution now drains symbols, occurrences, traversal records, and
binding rows under one invocation allowance. It follows issued continuations,
including empty pages, without rerunning the source search. Binding names and
mode survive accumulation. Mixed row families or binding modes fail closed.
Value paths and impact witnesses retain their separate investigation ledgers.

Presentation size is separate from semantic completion. A bounded preview may
point to retained rows and independent evidence pages. Reading a retained result
does not run the semantic query again. An exhausted upstream prefix cannot become
complete merely because its preview fits.

`COMPLETE_ONLY` selects `COMPILER_RESOLVED_STATIC_V1` on the original question.
Unsupported outputs and unproven completion are closed rejection variants.
Rejected prefixes retain their full evidence when bounded storage admits them;
storage rejection is explicit. Evidence reads retain the policy verdict and
original coverage separately. A historical producer checkpoint does not grant a
new strict execution allowance.

## Callback evidence

The strict completion policy admits a finite static callback graph from exact
direct and bound argument flows. Typed nodes distinguish named callables,
anonymous bodies, and supplier-specific formals. Typed edges distinguish supply,
forwarding, invocation, and a body's call to a named target. Graph admission
checks exhaustive scans, exact owners, the original workspace basis, and absence
of unresolved obligations or recursive formal routes. The enclosing query must
separately exhaust its observation inventory. Existing flow projections retain
the derivation witnesses without converting lexical containment into a named
call edge.

A callback parameter summary contains detached compiler evidence for one exact
formal parameter and semantic basis. The relation request may reuse an exhaustive
summary for several supplied callbacks. Each reuse separately validates its
supplier binding, anonymous body, and owner activation obligations. The cache is
request-owned, contains no PSI, and shares the existing retention budget.

This does not establish runtime activation. Stored, returned, externally supplied,
unresolved, or recursively forwarded callback routes retain their typed
obligations. Recursive forwarding remains a typed rejection. A recursive symbolic
summary graph requires a different completeness contract before it can replace
that boundary.

## Snapshot relation indexes

The retained SQLite topology relation compiler builds exact symbol-candidate and
relation-adjacency indexes once per admitted immutable snapshot. A compiler
identity may have several location-distinct candidates; the index preserves all
of them. Relation direction, edge kind, scope, and exact endpoint admission remain
part of the query. Unrelated edges do not consume eligible result capacity.

Topology remains outside the shipped runtime composition. Its existing proof
does not cover every anonymous, unresolved, or external invocation. Runtime use
requires an explicit coverage model and architecture admission; an index alone
cannot supply that proof.

## Validation boundaries

Run the repository's release-aware routine gate so packaged metadata uses a
current numeric release version:

```shell
mise exec -- python3 distribution/release/run_product_gate.py
mise exec -- ./gradlew knowledgeImpact verifyKnowledgeBase
```

The routine gate checks production rules, generated contracts, packaging, and
architecture. It does not establish live IntelliJ semantic behavior or enterprise
query latency. Report native tests that the runner skips separately.

Focused production-rule tests compare automatic and explicit page draining,
including empty pages, independent evidence pagination, retained reads, and row
families. Formal-summary tests use two suppliers for one formal, reject a different
basis or parameter, and exercise retention limits. Snapshot relation tests compare
independent expected edges at several page grants with unrelated and wrong-kind
negative controls.

The next acceptance boundary is a current imported Kotlin project: callers,
callees, and callback flow across modules, with an independently enumerated
expected answer and unsupported-flow negative controls. Then run the same query
and exact model on the representative enterprise corpus. Record hardware,
compiler/project versions, snapshot identity, all grants, completion status,
elapsed time, examined work, retained bytes, and emitted bytes. Compare matched
outputs before interpreting a latency difference. Report indexing and freshness
cost separately. A test fixture or dormant index benchmark cannot establish the
enterprise ceiling.

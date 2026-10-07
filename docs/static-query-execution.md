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

A retained policy rejection is published only when its fitted rejection matches
the prepared page exactly. Host freshness, deadline, and lifetime failures still
revoke unpublished evidence. Publishing the evidence preserves the rejected
verdict.

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

The opt-in native acceptance fixture is
`experiments/host-observation/static-callback-fixture`. Its three modules separate
lambda suppliers, two forwarding functions, and the terminal parameter
invocation. `static_callback_oracle.py` authors exact declarations, UTF-16 source
sites, supplier bindings, forwarding sites, and invocation sites independently of
semantic responses. Changed or additional Kotlin sources reject the fixture.
Alpha supplies two distinct lambda bodies at two wrapper call sites in one
relation request. Each summary reuse must retain that supplier's own body and
binding; beta checks a separate root and target.

`qualify_callback_tracing.py --static-cross-module` reuses the existing installed
schema admission, public MCP session, and retained-result drainer. It requires an
exact candidate build receipt, loaded-class and artifact hashes, a current native
model pin, and an explicitly selected fixture. It runs semantic result allowances
of 4 and 32 and retained presentation grants of 1,
checks the complete supplier-rooted `CALLEES` derivations, and checks that callback
body calls do not become named `CALLERS` edges. Recursive and external escape
routes must retain their typed rejection and original evidence. A formal-rooted
parameter invocation must reject with `CALLABLE_VALUE_UNPROVEN`.

The alpha route needs two retained forwarding witnesses, one terminal invocation,
and one optional summary for reuse. A semantic allowance of 1 cannot retain that
route. A separate control requires its `RESULT_LIMIT_REACHED` rejection and
retained incomplete evidence. Increasing presentation capacity never supplies
the missing semantic proof.

A Gradle CLI candidate also needs the source-owned
`distribution/cli/one-shot-observation-v1` session marker staged at
`share/kast/one-shot-observation-v1`. The candidate receipt checks its exact
source and destination hashes. This candidate composition qualifies native
semantic execution; it does not qualify managed installation or release delivery.

Callable references are a separate exclusion control. Their reference sites are
excluded from these named-call relations; this suite does not establish
callable-reference transfer completeness. It also does not qualify an unrestricted
walk, recursive summaries, runtime activation, or topology activation.

The native receipt records actual formal-body scans and supplier-specific summary
hits, misses, instantiation rejections, and retention outcomes. A completed scan
means the native PSI inventory drained, not that callback activation was proven.
Missing counters reject qualification; they are never interpreted as zero.
Retained row and evidence reads must report zero callback work. Semantic witnesses
and verdicts must remain equal across the two grants before interpreting work or
timing evidence.

Run the same query
and exact model on the representative enterprise corpus. Record hardware,
compiler/project versions, snapshot identity, all grants, completion status,
elapsed time, examined work, retained bytes, and emitted bytes. Compare matched
outputs before interpreting a latency difference. Report indexing and freshness
cost separately. A test fixture or dormant index benchmark cannot establish the
enterprise ceiling.

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
of unresolved obligations. A bound argument's `EXHAUSTED_GRAPH` forwarding
witness inventories every reachable exact formal and compiler-confirmed edge,
including edges that close a recursive component. Forwarding through local
immutable aliases retains the ordered compiler transfer chain on the forwarding
edge: each local binding and read carries its exact owner, source site, destination
site, and role. Direct forwarding carries an explicit empty transfer list. Mutable
aliases retain their escape obligation. Each formal body is scanned once. The enclosing query must separately exhaust its observation inventory.
Existing flow projections retain
the derivation witnesses without converting lexical containment into a named
call edge.

A callback parameter summary contains detached compiler evidence for one exact
formal parameter and semantic basis. Its finite graph retains all forwarding
edges; each terminal invocation retains one simple rooted path. The relation
request may reuse an exhaustive summary for several supplied callbacks. Each
reuse separately validates its supplier binding, anonymous body, and owner
activation obligations. The cache is
request-owned, contains no PSI, and shares the existing retention budget.

This does not establish runtime activation. Mutable storage, unmodeled captures,
external transfers, and unresolved callback routes retain their typed obligations. An exhausted closed
recursive graph with no terminal invocation is a complete static empty invocation
inventory. It does not prove that the supplied callback executes, or that the
recursive code terminates at runtime. Missing graph inventory, unproven forwarding,
and exhausted resource grants remain typed rejections.

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
lambda suppliers, the forwarding component, and a terminal parameter invocation.
The component includes a self cycle, self recursion with a direct parameter
invocation, and mutual recursion with a terminal route into the invocation module.
This preserves acyclic Gradle module dependencies while exercising recursive
formal edges. `static_callback_oracle.py` authors exact declarations, UTF-16 source
sites, supplier bindings, forwarding sites, and invocation sites independently of
semantic responses. Changed or additional Kotlin sources reject the fixture.
Alpha and the self-recursive invocation case each supply two distinct lambda
bodies at two wrapper call sites in one relation request. Each summary reuse must
retain that supplier's own body and
binding; beta checks a separate root and target. The alias-forwarding case routes a
formal through two local immutable bindings before passing it to the invocation
module. Its oracle requires all four binding/read transfers, including exact
compiler owner and source ranges. A mutable alias is the rejection control.
These additions require a new source-matched native receipt; earlier receipts
cannot qualify them.

`qualify_callback_tracing.py --static-cross-module` reuses the existing installed
schema admission, public MCP session, and retained-result drainer. It requires an
exact candidate build receipt, loaded-class and artifact hashes, a current native
model pin, and an explicitly selected fixture. It runs semantic result allowances
of 8 and 32 and retained presentation grants of 1,
checks the complete supplier-rooted `CALLEES` derivations, and checks that callback
body calls do not become named `CALLERS` edges. The recursive cases must retain
their exhaustive formal and edge inventories, including closing edges. The
closed self cycle must have no terminal invocation evidence. External escape
routes must retain their typed rejection and original evidence. The legacy formal-rooted parameter invocation has a mutable supplier route and
must preserve its exact `STORED_CALLBACK` supplier-inventory rejection. The separate
immutable fixture proves complete empty and nonempty formal supplier inventories.

The matched complete allowances are 32 and 128 results. Atomic selected-supply
inventories retain evidence in addition to lexical callback observations. A cold
host's eight-result control must reject with `RESULT_LIMIT_REACHED` and retain its
original complete coverage as policy evidence. Run this control before relation
reads on a freshly restarted candidate: an admitted cached summary can legitimately
fit the smaller grant. The separate one-result control cannot retain the alpha
route's forwarding witnesses. Presentation has its own one-record allowance;
increasing presentation capacity never supplies missing semantic proof.

Formal and forwarding storage includes all detached endpoint identity fields.
An optional summary shares immutable records only when the same request's
retention owner already admitted those exact objects; it then charges the new
containers and references. Independent summaries retain their full storage
charge. Encoded response fitting applies separately under the configured
transport ceiling, which defaults to 65,536 bytes. Alias forwarding retains four
additional transfer witnesses and can exceed that default. Native qualification
may explicitly configure a larger `KAST_READ_HOST_RESPONSE_BYTES` envelope on
both the owned IDE and MCP client; the outer `KAST_READ_PROVIDER_OUTPUT_BYTES`
envelope must also admit that value. Its pin retains the exact configuration. Preserve the
default-budget rejection alongside the larger-envelope result.

`--static-max-elapsed-ms` and `--static-max-work-units` declare the same time and
work allowance for both complete runs and retained presentation. The default is
20,000 for each. Full dependency revalidation remains charged on every relation
read, including reuse; increasing a grant does not remove work from the receipt.

A Gradle CLI candidate also needs the source-owned
`distribution/cli/one-shot-observation-v1` session marker staged at
`share/kast/one-shot-observation-v1`. The candidate receipt checks its exact
source and destination hashes. This candidate composition qualifies native
semantic execution; it does not qualify managed installation or release delivery.

Named callable references retain a separate callable-value observation. The
reference site does not become a named `CALLERS` or `CALLEES` edge. The native
suite covers plain, bound-receiver, and unbound-receiver references and requires
the exact compiler target, receiver binding, supplying call,
complete formal graph, and terminal invocation in both query directions. Earlier
receipts that checked only named-call exclusion do not establish this transfer
proof; the expanded suite requires a new source-matched receipt. It also does not qualify an unrestricted
walk, runtime activation, or topology activation.

The native receipt records actual formal-body scans, inventoried formal and edge
counts, completed and rejected fixed-point scans, and supplier-specific summary
hits, misses, instantiation rejections, and retention outcomes. Every cold complete
supplier-rooted case must scan each authored formal once. An admitted cross-query
summary reuse must report admitted project-summary use, no extraction, and zero
additional formal scans or graph inventory work. Each supplier still requires a
successful binding check. Source observations and selected-supply observations can
instantiate the same partition separately; the reuse counter counts admitted uses,
not distinct cache entries. The second alpha query must establish project reuse;
an entirely cold run cannot qualify that claim. A completed scan
means the native PSI inventory drained, not that callback activation was proven.
Missing counters reject qualification; they are never interpreted as zero.
Retained row and evidence reads must report zero callback work. Semantic witnesses
and verdicts must remain equal across the two grants before interpreting work or
timing evidence. Fixture-integrity and scripted runner tests validate this oracle
and acceptance boundary; only an exact pinned native receipt establishes live K2
behavior for these cases.

## Immutable-flow and cross-generation acceptance inputs

`immutable-callback-fixture` is a separate four-module input corpus for the next
qualification boundary. Its independent module has no callback-module dependency.
The authored oracle distinguishes named references, bound and unbound receivers,
local immutable aliases, anonymous functions, default omission and override,
generic forwarding, factory captures, transparent returns, captured local aliases,
finite branch alternatives, and a supplied value that is never invoked. Mutable
storage, mutable captures, external transfer, forwarded captures, and receiver
factories are exact typed rejection controls. Returned nonlocal getters and
implicit operator calls are additional unsupported-body controls. These are acceptance expectations;
native support requires a receipt for the selected source and case.

`immutable_callback_qualification.py` checks the authored callback target and
supplier inventory separately from public schema admission. A `complete` response
with only direct named-call rows cannot establish a callback answer. The reader
requires callback observations, exhaustive unqualified flows, connected immutable
transfers, both finite branch targets, the correct default/explicit selection, and
the exact empty or two-supplier inventory for the isolated formal fixtures. These
checks supplement the existing exact source-site and graph oracle; they are not a
standalone qualification of the wider native model.

`immutable_factory_qualification.py` independently anchors the factory producer,
call site, formal, selected argument, returned value, and captured invocation to
the authored source. Two calls to the same factory must preserve their distinct
alpha/beta captured targets. Captured local aliases retain both binding and read
transfers with exact owners and sites. Factory tables reject missing, unused,
forward, or cyclic references. Every returned anonymous body requires an exhaustive
`body_calls` inventory. Named calls retain exact source occurrences and compiler
targets; captured invocations must match their capture formal and closure owner.
Changing `{ block() }` to `{ betaTarget() }` must remove activation of the supplied
alpha value and retain the body's beta call. A missing body inventory cannot qualify
a complete result. Native returned-body proof inventories immediate explicit call
expressions, exact captured-formal invocations, and named compiler targets.
Compiler-backed capture checks reject unmodeled nonlocal properties and getters.
Operations, subjectful `when` equality, array access, interpolated templates, loops,
destructuring, delegates,
and object or class declarations reject with `UNSUPPORTED_CALLBACK_SUPPLY` until
their implicit execution has an owned proof. Constant strings and simple explicit
calls remain supported. The selected-supply reader requires every declared
function formal and counts a supplied target only when its summary proves an
invocation; supplying a value alone does not establish activation.

`qualify_immutable_callbacks.py` runs that matrix against a pinned candidate via
public MCP. Its receipt names the selected cases and the narrower
`CALLBACK_TARGETS_AND_SUPPLIER_INVENTORIES` scope, preserves source/build/harness
hashes, and checks the native host, fixture, model, and limits again at the end.
Positive cases retain correlated semantic-fact counters; rejected controls retain
their exact completion failure and evidence handle. A selected subset never
stands in for the full matrix or the mutation and reuse qualification.

The mutation matrix starts from the original fixture for each case. It changes a
body, changes a returned closure, adds an overload or override, changes an empty
invocation inventory, or adds a supplier to a previously empty supplier inventory.
Each case also names the independent query whose fact partition must survive.
Source preimages are exact and reversible. Fresh extraction at the changed source
is a differential control; the authored expected target set remains the oracle.
A changed answer is not necessary for invalidation: adding an override must
revalidate the relevant static dispatch evidence even when the compiler-resolved
base declaration is unchanged.

`immutable_reuse_qualification.py` compares the complete semantic answer,
including relation observations, supplier inventories, factory contexts and
transfers. Target-set equality alone cannot pass. It admits each nested live
basis against its response before normalizing host/epoch identifiers, and
normalizes handles only when companion identity evidence exists. Paths, ranges,
compiler identities and qualifications remain part of equality. Its work gates
require explicit positive store activity; they do not themselves run a mutation
or establish native reuse.

`immutable_document_edit.py` creates a new owned copy and prepares a native
ideScript without launching a host. The script requires the selected host PID,
creator token, exact fixture source path, and document preimage before applying
an unsaved edit. It commits PSI without saving the document and records only
before/after/saved hashes in its result. Its separately prepared rollback restores
the exact document preimage. A rejected native operation retains a finite cause;
a missing or malformed receipt never establishes an edit. Preparation and Python
receipt tests establish the ownership boundary only. A native receipt is still
required for the PSI edit and fresh-query outcome.

The separate `SAVED` mode also requires the exact saved file preimage, commits the
owned document, saves it through the IDE, and checks the resulting disk hash.
Its receipt is `APPLIED_SAVED`; an unsaved receipt cannot satisfy it. This mode
supports positive cross-edit reuse trials. Dirty-document rejection belongs to
the dependency-capture boundary. The public MCP readiness path can save selected
project documents before semantic execution, even with IDE autosave disabled. An
`APPLIED_UNSAVED` edit receipt alone therefore cannot qualify dirty-document cache
rejection: the trial must also prove the document remained dirty at capture. Saved and unsaved trials each restore their exact preimages.

Three fact families have separate eligibility boundaries. Complete formal
summaries depend on their module dependency closure. Exhaustive reverse supplier
inventories depend on the full workspace, including the absence of additional
suppliers. Complete initial named `CALLERS` and `CALLEES` partitions admit only
named facts: `CALLERS` depends on the full workspace and `CALLEES` on the subject's
module dependency closure. Mixed callback evidence, omissions, incomplete results,
and continuation pages are ineligible for this named-fact cache. Continuations and
retired references are never rebound to a new read authority. Each reused fact is
instantiated against independently revalidated current inputs before publication.

Project fact counters distinguish partition extraction, reuse, and invalidation;
dependency revalidation and rejection; and generation publication and rejection.
Their presence or an explicit zero does not prove the store ran. Qualification
must require positive expected store activity, one admitted current generation,
independently equal answers, and zero semantic work for retained reads. An
unwired counter or fixture-only success cannot satisfy cross-query or cross-edit
reuse acceptance.

Run the same query
and exact model on the representative enterprise corpus. Record hardware,
compiler/project versions, snapshot identity, all grants, completion status,
elapsed time, examined work, retained bytes, and emitted bytes. Compare matched
outputs before interpreting a latency difference. Report indexing and freshness
cost separately. A test fixture or dormant index benchmark cannot establish the
enterprise ceiling.
